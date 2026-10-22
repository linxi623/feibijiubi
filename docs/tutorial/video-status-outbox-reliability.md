# 视频统计 Outbox 发布、回写与租约可靠性（T07）

> 状态：T07 已在测试环境验收（2026-10-22）；实现已合入主分支 `95af60d`（候选 `eec1e01`）。
> 父代理全链路回归 87 tests，0 失败/错误，1 skipped（日志 `/tmp/improvement-main-regression3.log`）。
> 该结果不宣称生产验收，也不包含真实进程崩溃或两 JVM 演练。
>
> 范围：`VideoStatusOutboxRelay` 的发布/confirm/回写分类、CAS 行数检查、单条异常隔离、
> 租约恢复、confirm 后回写预算，以及配套 mapper/XML/schema 变更。
> 不在范围：FAILED 批量处置与对账（T10）、消费重试与 ACK 边界（T08）、
> status0 补偿（T09）、dirty 公平扫描（T11）。
>
> **语义边界**：整条链路仍是**至少一次投递 + eventId 幂等**。MQ 已确认但 DB 回写失败时
> 允许重复投递，由消费端去重吸收；本文不承诺跨 RabbitMQ 与 MySQL 的 exactly-once，
> 也没有引入分布式事务或两阶段提交。

## 1. 旧实现的问题

改动前 `publishOne` 把四件事塞进同一个 `try`：

```text
JSON 反序列化 → publish(等 confirm) → markSent
catch (Exception) → 按“发布失败”统一处理：markPending 或 markFailed
```

由此产生四类错误行为：

1. **非法 payload 被当成发布失败**：JSON 解析/`event.validate()` 失败也会消耗
   `retry_count`，重试 10 次后才进入 FAILED，期间每轮都白跑一次；
2. **confirm 成功后的回写失败被当成发布失败**：`markSent` 抛异常会走
   `markPending + retry_count+1`，重试预算被“数据库抖动”消耗；极端情况下还会被
   `markFailed` 判成终态，而消息其实已经到达 Broker；
3. **catch 内的数据库再失败会中断整轮**：`markPending` 自己抛异常时异常冒出
   `relay()`，同一轮剩余事件全部没机会处理；租约恢复异常同样直接冒出；
4. **不检查 CAS 行数**：`markSent/markPending/markFailed` 返回 0 行（租约已过期、
   已被新 owner 接管）时既不诊断也不区分，只能等下一轮租约恢复兜底；
   返回多行（唯一键/条件异常）则完全静默。

## 2. 失败分类矩阵（T07 契约）

判定顺序就是下表顺序，互斥：

| 阶段/判定 | 写库动作 | 预算变化 | 终态/后续 | PUBLISH 指标 |
|---|---|---|---|---|
| payload 反序列化抛异常 / 结果为 null | `markInvalidFailed(eventId, leaseToken, lastError)` | 三份预算都不变 | FAILED，等 T10 处置 | FAILURE |
| `event.validate()` 失败 | 同上 | 三份预算都不变 | FAILED | FAILURE |
| event 与 outbox 的 eventId/vid/type 不一致 | 同上 | 三份预算都不变 | FAILED | FAILURE |
| publish 发送失败（SEND_FAILED） | `markPending(...)` 或达上限 `markFailed(...)` | `retry_count +1` | 指数退避重试 / FAILED | FAILURE |
| Broker NACK / 不可路由 | 同上 | `retry_count +1` | 同上 | FAILURE |
| confirm 超时/中断/异常完成（结果不确定） | 同上 | `retry_count +1` | 同上（消息可能已到达，靠幂等） | FAILURE |
| confirm ACK 成功、`markSent` 成功 | `markSent(...)` | 不变 | SENT | **SUCCESS** |
| confirm ACK 成功、`markSent` 抛异常 | `markConfirmedPending(eventId, leaseToken, nextRetryAt, lastError)` | `confirm_writeback_count +1`；第 10 次改走 `markConfirmWritebackExhausted` | 前 9 次 1 秒后回 PENDING 重投；第 10 次 FAILED，原因 `confirm writeback budget exhausted` | **SUCCESS** |
| confirm ACK 成功、`markSent` 返回 0 行 | 只诊断，不再改状态 | 不变 | 保持现状，等租约恢复 | **SUCCESS** |
| confirm ACK 成功、`markConfirmedPending` 也抛异常 | 只记日志 | 不变 | **保留 SENDING**，等租约过期恢复 | **SUCCESS** |
| 任意 CAS 更新返回 0 行 | 只 WARN 诊断旧 owner/租约失效 | 不变 | 不再改状态 | 由原阶段决定 |
| 任意 CAS 更新返回 >1 行 | 只 ERROR（严重） | 不变 | 不再改状态 | 由原阶段决定 |

关键不变量：

- **唯一 id 不变**：relay 从不重写 `eventId`，也不允许用 Outbox 行覆盖 payload 里的
  eventId；语义不一致直接判非法，而不是“修正后发布”。
- **租约 token 是唯一写入口**：所有状态回写都带 `lease_token = #{leaseToken}` 与
  `status = 1` 的 CAS；旧 owner 超时后无法修改新 owner 的状态。
- **已 confirm 的消息不因单次回写失败直接判 FAILED**：`markSent` 失败只影响“持久化记录”，
  不影响“消息是否已到达”这一事实；但同一行第 10 次回写仍失败时，用独立
  `confirm_writeback_count` 预算形成可诊断终态，避免每秒永久重投。
- **三份预算互相独立**：发布失败用 `retry_count`，租约恢复用 `lease_recovery_count`，
  confirm 后回写失败用 `confirm_writeback_count`；任一预算的递增都不消耗另外两份。
- **人工重放重开预算**：只有受审计的 `POST /api/admin/video-status/outbox/{eventId}/replay`
  会在同一事务里把状态重置为 PENDING，并把三份预算归零、保留 before/after 审计元数据。

## 3. 类型化发布异常

`VideoStatusEventPublisher#publish` 不再 `throws Exception`，改为抛
`VideoStatusPublishException`（`RuntimeException`），原因有限可枚举：

| Reason | 含义 | 消息是否可能已到达 Broker |
|---|---|---|
| `SEND_FAILED` | `convertAndSend` 直接抛异常（连接/通道/序列化） | 不确定，按未发出重试 |
| `NACK` | Broker 明确拒绝 | 否 |
| `UNROUTABLE` | ACK 但无法路由到队列（returned message） | 否（未入队） |
| `CONFIRM_TIMEOUT` | 等 confirm 超时 | **是，结果不确定** |
| `CONFIRM_INTERRUPTED` | 等 confirm 被中断（保留中断标记） | **是，结果不确定** |
| `CONFIRM_FAILED` | confirm future 异常完成 | **是，结果不确定** |

`SEND_FAILED/NACK/UNROUTABLE` 与 `CONFIRM_*` 都计入发布失败重试预算；区别只在诊断语义：
`CONFIRM_*` 说明消息可能已经进入队列，后续重复投递必须依赖 eventId 幂等，不能当成
“没发出去”做补偿。

异常 `message` 只包含 reason 名称；relay 记录日志时也只输出 reason code 与异常类名，
**不输出 payload、JSON 片段、broker 原文或连接凭据**。

## 4. 回写与 CAS 诊断

所有回写统一走 `diagnoseCas(operation, outbox, rows)`：

- `rows == 1`：正常，按阶段记录结果；
- `rows == 0`：WARN，说明租约已过期/已被新 owner 接管/状态已终结，**只诊断、不再改状态**；
  不再尝试“补救性”第二次状态修改，避免旧 owner 覆盖新 owner；
- `rows > 1`：ERROR（严重），说明 `event_id + lease_token + status` 条件本应唯一却命中多行，
  需要立即排查数据或 SQL 条件。

`last_error` 列只写 `reason[:异常类名]`，长度截断到 200 字符。例如：

```text
PAYLOAD_JSON_INVALID:BusinessException
EVENT_MISMATCH_TYPE
PUBLISH_CONFIRM_TIMEOUT:VideoStatusPublishException
CONFIRM_WRITEBACK_FAILED:DataAccessResourceFailureException
confirm writeback budget exhausted
```

## 5. 单轮隔离与预算（保留 T06 契约）

- 每轮仍**逐条领取** `claimBatch(1)`，租约窗口只覆盖本条事件；
- 每轮仍受 `outbox-batch-size` 与 `round-budget-ms` 约束，预算检查发生在“下一次领取之前”；
- **单条事件任何异常都被隔离**：`publishOne` 内部按失败类型处理，外层再兜一层
  `ISOLATED_ROW_FAILURE`，保证一条坏消息不会中断本轮剩余事件；
- **租约恢复异常不静默丢**：`recoverExpiredSending` 抛异常时记录
  `LEASE_RECOVERY_FAILED`，保留 SENDING 状态，本轮仍可安全尝试 claim；
- **claim 自身失败结束本轮**：记录 `CLAIM_FAILED` 后 `break`，不空转预算、不重复抛异常；
- **线程被中断（confirm 中断）结束本轮**：`publisher` 保留中断标记，relay 不再领取新工作，
  剩余事件留在 Outbox 表等下一轮。

## 6. 租约恢复与 confirm 回写预算

`recoverExpiredSending(expiredBefore)` 的方法签名保持不变，语义由 mapper/SQL 收敛为：

- 只处理 `status = 1 AND sending_at < expiredBefore`；
- 维护独立的 `lease_recovery_count`，**不增加 `retry_count` 或 `confirm_writeback_count`**；
- 未耗尽时回 PENDING，`next_retry_at = NOW(3) + 1s`；
- 第 10 次过期直接置 FAILED，`last_error = lease recovery budget exhausted`，
  形成可处置终态而不是无限恢复；
- 单次恢复有 `ORDER BY sending_at, id LIMIT 1000` 上限，避免一次扫描锁住过多行。

`markConfirmedPending`/`markConfirmWritebackExhausted` 维护独立的
`confirm_writeback_count`：前 9 次回写失败回到 PENDING，第 10 次直接 FAILED，
原因固定为 `confirm writeback budget exhausted`；同样不碰 `retry_count` 与
`lease_recovery_count`。

相关 migration 均在当前分支内（不是外部并行分支）：

- `database/migrations/t07-video-status-outbox-lease-recovery.sql`：新增
  `lease_recovery_count`；
- `database/migrations/t07-video-status-outbox-confirm-writeback.sql`：新增
  `confirm_writeback_count`；
- `database/feibijiubi.sql` 建表语句同步包含两列。

历史数据不回填、不归零；迁移只加列，重复执行幂等。SQL 赋值顺序要求把自增列放在所有
`CASE` 表达式之后，避免第 9/10 次判定错位；该边界由 `VideoStatusOutboxLeaseTests` 逐次验证。
详见 [租约预算与迁移](video-status-outbox-lease-migration.md)。

## 7. 指标与日志口径

- `PUBLISH / SUCCESS` 的语义是 **confirm ACK 成功**，不是“DB 回写成功”。
  因此 `markSent`/`markConfirmedPending` 失败时指标仍是 SUCCESS，持久化问题通过
  `CONFIRM_WRITEBACK_FAILED` 日志、`last_error` 与 `confirm_writeback_count` 观察。
- 非法 payload/事件、publish 失败、confirm 失败都记 `PUBLISH / FAILURE`。
- 指标是**本实例**的 operation 计数，不是全局值；全局积压仍看 backlog 采样
  （见 `video-status-observability.md`）。
- 日志字段固定为 `eventId`、`leaseToken`（仅 CAS 诊断）、`retryCount`、`reason`、`class`、
  `operation`、`rows`；不打印 payload、JSON、密码或 broker 原文。

## 8. FAILED 的单事件查询与受审计重放（T07 临时运维 / T10 极窄子集）

当前分支已提供受管理员鉴权的单事件工具，但**不是完整 T10**：

| 入口 | 方法 | 行为 |
|---|---|---|
| `/api/admin/video-status/outbox/{eventId}` | GET | 只读安全状态：status/statusName、retryCount、leaseRecoveryCount、confirmWritebackCount、时间字段、`lastErrorPresent`/`lastErrorHint`、consumedProcessStatus、replayEligible 与阻断原因；不返回 payload、leaseToken、last_error 原文 |
| `/api/admin/video-status/outbox/{eventId}/replay` | POST | 单事件受审计重放：仅 FAILED 且通过安全检查时重置为 PENDING，同事务写 COMPLETED 审计（before/after 含三份预算） |

两个接口都在 `/api/admin/**` 下，沿用 `LoginInterceptor` + `AdminInterceptor`，方法另有
`@AdminOnly`；没有新增 actuator 或未鉴权端口。POST 的 operator 只取登录态 `currentUserId`，
请求体只有 `operationId` 与 `reason`。任何已存在的 consumed 行、非法 payload、超出安全窗口、
非 FAILED 状态或并发冲突都会被拒绝并给出原因；不会新建 eventId，也不会改 payload/vid/type。
完整操作步骤见 [Outbox FAILED 重放 Runbook](video-status-outbox-replay-runbook.md)。

T10 第一阶段已合入失败分页/详情/预检查/确认已阅和单视频只读对账；仍属 T10 未完成部分：
DLQ 摄入与处置、`process_status=3` 受控重放、批量处置/限速和自动修复。只读对账不返回 SAFE，
“确认已阅”也不是忽略或修复。

## 9. 测试与验收证据

T07 聚焦命令（由父代理在 2026-10-22 独立复跑）：

```bash
./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests' test
```

结果：**61 tests，0 failures / 0 errors / 0 skipped**；日志
`/tmp/improvement-t07-verify.log`。实现提交为 `eec1e01`。该批次覆盖发布/confirm 分类、
CAS 行数、单条异常隔离、租约恢复预算、confirm 回写预算、mapper/迁移和重放边界。

**不包含**：生产环境演练、真实进程崩溃/重启恢复演练、真实网络断连、两个 JVM 的多实例接管、
或跨 RabbitMQ/MySQL exactly-once 证明。T07 是**测试环境验收**，不等于生产验收。

## 10. 验收结果与边界（如实说明）

1. **计划表已勾选**：`video-status-improvement-plan.md` 的 T07 行已按测试环境验收改为“已完成”；
   先前 3 failures 的整组回归历史保留在回归续作文档中。
2. **未做真实进程崩溃演练**：租约恢复与预算由 SQL/测试验证，不是“杀进程后真实接管”演练；
   真实两 JVM、网络分区和系统容量仍交 T16。
3. **未验证 exactly-once**：本设计明确是至少一次 + 幂等，不宣称 exactly-once；
   重复投递窗口依赖消费端 eventId 去重。
4. **T10 整项未完成**：第一阶段失败诊断/确认已阅与只读对账已合入主分支 `6fc5e7f`，但本工具仍只覆盖单个 Outbox FAILED 事件的安全查询与重放，不覆盖批量处置、DLQ 摄入或 consumed status3 受控重放。
5. **未改既有配置键**：T07 没有新增 `app.video-status.*` 键；`round-budget-ms` 等沿用 T06。

## 11. 回滚

- 回滚 relay/publisher 即可恢复旧行为；mapper 新增方法不影响旧调用方（旧
  `markPending/markFailed` 语义保持“发布失败才加 retry_count”）。
- 若只回滚 relay 而保留新 mapper：新增回写方法成为未使用语句，不影响既有投递。
- 若只回滚 mapper 而保留新 relay：会因缺少方法导致启动/编译失败，必须成对回滚。
- 数据库列按迁移脚本的 rollback 段处理：先停旧投递、统一回滚实例，再 DROP 本任务新增列；
  禁止删除 Outbox 行或清空幂等证据。
