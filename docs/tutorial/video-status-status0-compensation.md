# T09 第一阶段：status0 安全窗口原 eventId 补偿

> 状态：**第一阶段实现已合入主分支 `97c7357`（候选 `aab1c64`），并于 2026-10-22 完成测试环境验收，默认关闭。**
> 父代理在主分支实跑普通回归 356 tests（0 失败/错误，108 skipped，`/tmp/t09-main-unit.log`）与
> opt-in 隔离回归 62 tests（0 失败/错误，1 skipped，`/tmp/t09-main-it.log`）；候选早前 43 tests
> （含 8 个真实隔离 IT）0 失败/错误/跳过（`/tmp/t09-parent-final.log`）。
> 这是**测试环境验收，不是生产验收**；无证据模式保持受控/默认关，不批准生产开启。
> 本文说明本阶段的代码边界、接口、迁移和启用闸门。

## 1. 要解决的问题

消费事件先登记为 `process_status=0`（RECEIVED），再应用 Redis，最后标记 status 1。
如果进程在“登记成功、Redis 尚未应用或标记未回写”之间中断，事件会停在 RECEIVED，
既没有进入重试队列，也不会被落库调度处理，需要安全补偿。

本阶段只处理 **status0**，并坚持：

- 使用原 `event_id`、原 payload、原 vid/type，不创建新事件、不改统计真值；
- 不绕开 `register`、语义指纹、DB 年龄与 `occurredAt` 年龄校验；
- 领取只是短事务 + 租约 CAS，**MQ confirm 绝不在领取事务内**；
- 发送后崩溃时租约自然过期，下一轮仍以同一 eventId 重投，重复由消费端幂等吸收。

## 2. 安全窗口与 processed 证据

`status0-compensation-min-age-seconds`（静默期）必须小于
`consumer-recovery-auto-replay-max-age-seconds`（安全窗口），而后者在
`VideoStatusProperties.validate()` 中已被强制小于 Redis processed Key 的 TTL。
因此补偿永远只在 processed 幂等证据仍可能存在的时间范围内工作。

真正发 MQ 之前还会做三件事：

1. 解析原 payload 并 `event.validate()`；eventId/vid/type/delta 必须与 consumed 行一致；
2. 用数据库时钟 `TIMESTAMPDIFF(SECOND, consumed_at, NOW(3))` 判断年龄，
   再检查 payload 的 `occurredAt`（未来超过 now+5 分钟或超过窗口都拒绝）；
3. 重新计算语义指纹，必须与登记时的 `payload_hash` 完全一致；
4. **只读检查 Redis processed Key 的剩余 TTL**。

### 2.1 processed 剩余 TTL 校验

仅用 `hasKey` 不够：Key 当前存在，但如果在 MQ 重试期间先过期，消费者再次投递时
就可能重新累计。因此发送前要求：

```
processed 剩余 TTL >= MQ 最大重试退避 + max(补偿租约, publish confirm)
```

最大重试退避按 `consumer-max-retries` 和 T08 的 10/30/120 秒分级队列计算；默认
`consumer-max-retries=5`、租约 30 秒、confirm 5 秒时余量为 430 秒。剩余 TTL 小于该值、
无过期时间（-1）或读取失败时一律拒发。**即使 DB/occurredAt 仍在窗口内，长期保留的
processed 证据一旦已经过期或余量不足，也不能发送。**

### 2.2 两种证据模式（默认保守）

- **可证明模式（默认路径）**：processed 存在且剩余 TTL 足够时，才重新投递原 eventId。
  再次投递会被 Lua 判 `DUPLICATE`，不会重复累计。
- **受控未证明模式（默认关闭）**：只有同时满足
  `status0-compensation-enabled=true`、`status0-compensation-integrity-confirmed=true`、
  `status0-compensation-allow-unproven-replay=true` 三个条件时，才允许在安全年龄内
  对 processed 缺失的原 eventId 重放。该开关是**操作员声明**：已确认本次事故没有
  Redis 数据丢失、没有 current/processed 部分写事故；它不能自动检测“单独丢 processed”，
  也不把缺失判定为“未应用”。事故处置时必须先关闭 `status0-compensation-enabled` 总开关。

processed 缺失的语义始终是 `EVIDENCE_UNKNOWN`：可能从未应用，也可能 current 已应用而
processed 单独丢失。默认不发送、不改 status3、不清 current、不伪造证据，只释放租约、
设置冷却并写 `STATUS0_PROCESSED_EVIDENCE_MISSING` 诊断。只有上述三闸门同时为真才会
发送，并在结果中单独计为 `unprovenRepublished`，日志写入
`STATUS0_UNPROVEN_REPLAY_AUTHORIZED`，便于审计。

preview 不访问 Redis，因此每一项都明确返回 `evidenceStatus=EVIDENCE_UNKNOWN`；
`decisionGate` 说明当前配置下由哪个闸门授权/阻断：
`PREVIEW_ONLY_MASTER_SWITCH_OFF`、`BLOCKED_INTEGRITY_NOT_CONFIRMED`、
`PROVEN_PROCESSED_EVIDENCE_ONLY` 或 `ALLOW_UNPROVEN_REPLAY`。
preview 的静默期/租约/冷却判断同样使用同一条 SQL 返回的 `NOW(3)`，不与 JVM 时钟混用；
超龄事件计数最多扫描 `status0-compensation-over-safe-window-count-cap` 条，达到上限时
返回 `overSafeWindowLowerBound=true`，只声称“至少这么多”，不对全表做无界 COUNT。

严格边界：

- `current + processed` 同时丢失：默认不自动累计，也不能声称安全；
- `processed` 单独丢失（current 已生效）：默认不自动重放；只有操作员显式开启受控
  未证明模式才重放，可能重复计数由操作员承担；
- 任意 Redis 数据丢失、进程崩溃后的 exactly-once、两 JVM 演练仍不是本阶段保证，
  继续归 T12/T16。

## 3. 领取、租约与回写

迁移 `database/migrations/t09-video-status-status0-compensation.sql` 新增：

- `compensation_attempts`：补偿领取次数，与 consumer retry 预算独立；
- `compensation_next_attempt_at`：失败/证据不足后的冷却；
- `compensation_lease_token` / `compensation_lease_until`：多实例领取租约；
- `last_compensation_at`：诊断时间；
- `idx_video_status_consumed_compensation (process_status, last_attempt_at, id)`。

一轮流程：

1. 短事务按 id keyset 扫描候选（RECEIVED、过静默期、DB 年龄在窗口内、冷却到期、
   租约空闲、未超尝试上限），**每次只领取一条**并 CAS 写入 DB 时钟计算的租约、
   `compensation_attempts + 1`，提交后立即处理；不会预领一整批等租约过期；
2. 事务外做 payload/指纹/年龄/processed 校验；
3. 通过则调用现有 `VideoStatusEventPublisher`，同步等待 confirm；
4. confirm 成功后 CAS `markStatus0CompensationRepublished`；0 行只记日志；
   该回写只清理补偿租约/冷却，**不增加 `consumer_retry_count`**，补偿预算仍由
   独立的 `compensation_attempts` 管理；
5. 发送失败或证据不足：`releaseStatus0Compensation` 释放租约、用 DB 时钟设置冷却，
   `process_status` 保持 0。

扫描 cursor 始终按“本轮实际扫描到的最后 id”推进，而不是按 CAS 成功条数。
SELECT 后原消费者 touch 了 `last_attempt_at` 时，claim CAS 会因静默期复核失败；
本轮直接跳过并前进 cursor，后续轮次从尾部继续，不会反复回头导致尾部饿死。

单轮还受现有 `round-budget-ms` 的单调时钟（`System.nanoTime`）预算约束：
每次领取下一条之前都重新检查总开关、完整性闸门、线程中断和预算。预算用尽或
线程被中断时结束本轮，但**保留实际已扫描到的 cursor**，下一轮从断点继续，
不会重置为 null 从头部重扫造成尾部饥饿。已在处理中的一条不会被强杀，仍由
publish confirm timeout、SQL 超时和租约自然到期界定。

崩溃恢复：confirm 后、回写前崩溃时，租约到期后同一事件会被再次领取并重投；
消费者按 eventId/指纹幂等。不会产生新 eventId，也不会直接改 status。

`compensation_attempts` 达到上限（默认 5）后不再自动领取，只保留 RECEIVED 供只读
preview 诊断，不自动转 status3。超龄事件同样只计数告警，不盲目发送。

## 4. 配置与默认闸门

```yaml
app:
  video-status:
    status0-compensation-enabled: false              # 总开关，默认关闭
    status0-compensation-integrity-confirmed: false   # 事故闸门/完整性确认
    status0-compensation-allow-unproven-replay: false # 受控未证明模式，默认关闭
    status0-compensation-min-age-seconds: 60
    status0-compensation-batch-size: 20
    status0-compensation-fixed-delay-ms: 5000
    status0-compensation-lease-seconds: 30
    status0-compensation-cooldown-seconds: 120
    status0-compensation-max-attempts: 5
    status0-compensation-over-safe-window-count-cap: 1000
    # 既有单轮调度预算，status0 补偿同样复用（单调时钟，不参与业务年龄判断）
    round-budget-ms: 5000
```

- `status0-compensation-lease-seconds` 必须 >= `publish-confirm-timeout-seconds` + 5 秒，
  保证一条领取的租约覆盖 confirm 和回写余量；
- `round-budget-ms` 是既有调度预算配置，status0 单轮复用它；它只决定“还要不要
  领取下一条”，不强制中断已在处理中的发布/回写；
- 超龄计数上限只影响 preview/日志，不改变任何业务状态；

- 默认只允许 `VideoStatusStatus0CompensationService.preview(...)` 只读诊断，管理员
  `GET /api/admin/video-status/status0-compensation/preview?limit=1..100&afterId>=0`
  直接调用该服务方法；它不领取租约、不发送 MQ、不写 Redis/DB，默认关闭时仍可访问；
- 不提供运行期 pause/启停写接口，也不接配置中心；本阶段只有只读 preview GET，
  没有新增任何写接口；
- `enabled=true` 但没有 `integrity-confirmed=true` 时启动失败；
- `allow-unproven-replay=true` 不单独生效，必须与 enabled + integrity 同时为真；
  事故停机可只关闭总开关，应用仍可启动；
- 启用前必须由运维明确确认 processed 证据与 Redis 丢失边界；证据不确定时不得开启；
- 调度器使用独立单线程 `videoStatusCompensationScheduler`，固定批次，不共享
  relay/flush/recovery 线程，也不把每条事件提交到线程池。

## 5. 事故停机与配置生效边界

yaml 配置**不是热更新**：修改 `status0-compensation-*` 后必须重启对应实例才生效。
不存在运行期 pause/启停 endpoint，也不依赖配置中心推送；不要声称可以跨实例热停。

事故处置步骤：

1. 对**所有承担补偿角色的实例**设置 `status0-compensation-enabled=false` 并逐个重启；
   不能只停一台就认为全局已停，也不能假称一次配置变更会跨实例即时生效；
2. 等待已领取的 inflight 发布/回写结束，或等 `status0-compensation-lease-seconds`
   自然到期；不要强杀正在进行的 publish confirm；
3. 确认所有实例保持关闭后再做证据核对。已领取但未完成的租约到期后只会在
   总开关重新打开时才会被再次领取；关闭期间 preview 仍可只读诊断。

## 6. 本阶段未完成 / 下一步

- 未实现 processed 缺失分支的自动对账；即使受控未证明模式也只是重放，不检测单丢证据，
  后续仍需要 T10 对账或 T12 受控重建；
- 单测覆盖两模式、TTL 余量边界、Redis 错误、尝试预算、租约 CAS、DB 时钟参数、
  CAS 冲突下的 keyset cursor 前进、preview 计数封顶/lower bound、租约与 confirm 校验、
  年龄边界、单轮时长预算/中断保留 cursor，且不依赖基础设施；
- 已补齐显式 opt-in 的真实隔离 IT
  `VideoStatusStatus0CompensationIsolatedIntegrationTests`（统一使用 `-DvideoStatusIT=true`，
  复用现有 `IsolationGuard` 与 `compose.test.yaml` 的 MySQL/Redis/RabbitMQ）：
  “Redis 已应用但未 mark status1 原 eventId 收敛一次”“登记后未应用在三闸门受控模式收敛一次”
  “默认/事故闸门下 processed 单丢拒发且不累计”“current+processed 同时丢失默认不补偿”
  “两个 claim 线程并发只有一 token 成功”“过期租约接管后旧 token CAS 失败”
  “超龄不重投”“publish 成功但回写失败后同一 eventId 可再次收敛”。用例只新建自己的
  vid/event 行，不删除历史数据，也不做全库清理；
- 本工作区不重复运行真实 IT，父代理统一执行：
  `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusStatus0CompensationTests,VideoStatusStatus0CompensationIsolatedIntegrationTests' test`；
- 主分支最终验收（父代理本次实跑）：普通回归 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` 为
  **356 tests，0 失败/错误，108 skipped**（`/tmp/t09-main-unit.log`）；opt-in 隔离回归
  `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusStatus0CompensationTests,VideoStatusStatus0CompensationAuthorizationTests,VideoStatusStatus0CompensationIsolatedIntegrationTests,VideoStatusIsolatedIntegrationTests,VideoStatusOutboxReliabilityIsolatedTests' test`
  为 **62 tests，0 失败/错误，1 skipped**（`/tmp/t09-main-it.log`）。候选早前 43 tests，0 失败/错误/跳过，
  其中 8 个为真实隔离 IT，日志 `/tmp/t09-parent-final.log`。
- 失败历史（保留）：早前验证曾用错开关导致目标测试被跳过；mapper 参数绑定/时区处理也曾失败，修正后才有上述结果。文档与日志不记录任何凭证。
- preview 接口与响应字段见 `docs/api.md`；preview 固定返回 `evidenceStatus=EVIDENCE_UNKNOWN`，不访问 Redis。
- 验收边界：以上为**测试环境验收，不是生产验收**，也不是真实进程崩溃/两 JVM 演练。无证据（unproven）重放模式仍受三闸门控制且默认关闭，不保证任意 Redis 数据丢失或 `current + processed` 同时丢失的安全；生产开关、容量与跨进程恢复仍交 T16。T10 进行中：第一阶段失败诊断/确认已阅与只读对账已合入主分支 `6fc5e7f`（父代理 opt-in IT 121 tests、普通单测 416 tests 均通过），但 DLQ、consumed status3 受控重放和自动修复仍未实现，整项不勾选，也不是生产验收。
