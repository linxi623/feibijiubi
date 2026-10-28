# T10-B1：consumed status3 单事件受控重放（proven-only，候选，未父验收）

> 状态：**候选实现，等待父代理验收**；T10 整项仍未完成（DLQ 摄入/处置、批量处置、刷库型
> status3 修复、自动修复与收敛仍未实现），也不代表生产验收。
>
> 本文只描述 `video_status_consumed_event.process_status=3 (REPAIR_REQUIRED)` 的**单事件**
> 人工重放：`POST /api/admin/video-status/failures/{eventId}/replay?source=CONSUMED`。

## 1. 要解决什么

status3 有两个完全不同的来源，处置方式也完全不同：

| 来源 | 产生位置 | `redis_applied_at` | 真正的缺口 | 本接口是否处理 |
| --- | --- | --- | --- | --- |
| A. RECEIVED 超龄 | `VideoStatusConsumptionServiceImpl.handleReceived` → `markRepairRequired(0→3)` | `NULL` | 事件登记后没能进入 Redis 应用（或应用了但 `markRedisApplied` 回写丢失） | **只看能不能证明已应用** |
| B. 刷库失败 | `VideoStatusBatchFlushServiceImpl` → `markFlushRepairRequired(1→3)` | 非空 | Redis 已累计、DB 未落库 | **拒绝**，交刷库重试/门闩内重建（T12） |

来源 B 一旦重置为 RECEIVED 再重投，消费端会先看到 processed 幂等 Key 判 `DUPLICATE`，
既补不了 DB 落库，还会再次回到 status3。所以本接口把 `redis_applied_at IS NOT NULL` 作为
硬拒绝条件，而不是"顺带修一下"。

## 2. 为什么必须 proven（不提供 unproven 模式）

`redis_applied_at IS NULL` 无法区分"从未应用"和"已应用但回写丢失"。唯一能区分的是 Redis 的
processed 幂等 Key（`RedisKeyUtils.processedKey(eventId)`）：

- Key 存在且**剩余 TTL 足够**：已应用被证明 → 重投必然被判 `DUPLICATE`，不会重复累计；
- Key 缺失（`getExpire = -2`）：可能是从未应用，也可能是 current 已应用而 processed 单独丢失。
  **缺失不等于未应用**，因此拒绝；
- Key 存在但无过期时间（`-1`）或读不到：无法证明重试窗口内仍可去重，拒绝；
- 剩余 TTL 不足：重试退避期间 processed 可能先过期，拒绝。

最小剩余 TTL 由 `VideoStatusProperties.getStatus3ReplayRequiredProcessedTtlSeconds()` 计算：
`MQ 最大重试退避（10/30/120s 重试队列之和）+ max(outbox 租约, status0 补偿租约, confirm 超时)`。
它比 T09 更保守，因为投递改由 Outbox relay 完成。

与 T09 的关系：T09 自动补偿的"proven 路径"判据与本接口一致；T09 的 `allow-unproven-replay`
是**操作员断言**（默认关闭的三闸门），本接口不复用也不替代它，没有对应的放行开关。
因此本接口**不覆盖**任意 Redis 数据丢失，也不宣称 current 与 processed 同时丢失时安全。

## 3. 投递策略：复用 Outbox relay，而不是提交后直接 publish

请求线程只做一件事：在一个 MySQL 事务里改 DB。事务内**不 publish，也不等 MQ confirm**。

```
事务（同一 commit）:
  consumed_event:  3 REPAIR_REQUIRED → 0 RECEIVED
                   WHERE process_status=3 AND redis_applied_at IS NULL AND payload_hash=?
  outbox:          2 SENT → 0 PENDING（清 next_retry_at / sending_at / lease_token）
  repair_log:      插入 COMPLETED 审计 + before/after 元数据
提交后:
  既有 relay 定期领取 PENDING → publisher confirm → markSent
  失败按既有 retry_count 退避、租约恢复重投；消费者判 DUPLICATE → markRedisApplied(0→1) → 刷库 FLUSHED
```

这样"提交后进程崩溃"不会丢任务：Outbox 行仍是 PENDING，由任一同角色实例的 relay 继续投递。
它也不需要新增队列、不扩张消费协议（同一 eventId 的重复投递本来就被幂等吸收）。

前提是同一 eventId 的 Outbox 行**存在且为 SENT**：

- 行不存在 → 拒绝（没有可证明的持久化重投路径，不允许退化成请求线程直接发送）；
- 行为 PENDING/SENDING/FAILED → 拒绝（先按 outbox 接口处置该行）。

副作用是：若某实例组没有启用 relay（`async-enabled` + `outbox-relay-enabled`），
事件会一直停在 PENDING。这是可见、可诊断、可继续被领取的状态，而不是"看起来成功却没有发送"。

### 3.1 锁顺序与旧 owner 回写

本接口是**唯一**同时写两张表的事务，因此必须说清锁顺序：

1. `video_status_consumed_event` 目标行 `SELECT ... FOR UPDATE`（同 eventId 重放串行）；
2. `video_status_outbox` 同 eventId 行 `SELECT ... FOR UPDATE` → CAS `WHERE status=2`；
3. 最后插入 `repair_log` 审计行。

既有代码里没有任何事务会"先锁 outbox 再锁 consumed"：relay 只锁 outbox（`FOR UPDATE SKIP LOCKED`
领取，其余 `markSent`/`markPending`/`markFailed` 都是带 token 的单行 CAS），消费者与批量落库只锁
consumed。因此不存在反向锁序，也没有跨表死锁环。

旧 relay owner 不可能覆盖本接口的重置：

- relay 的回写 CAS 全部要求 `status=1 AND lease_token=?`（`markSent`）、
  `status=1 AND lease_token=?`（`markPending` / `markFailed`）；
- 本接口只在 `status=2 SENT` 时命中，并同时 `sending_at=NULL, lease_token=NULL, status=0`；
- 旧 owner 的 token 已经不再存在且行不是 1/SENDING，它的 CAS 必然 0 行，relay 侧只会记一条
  "回写未命中"诊断日志，不会改状态；
- 若当时真有 relay 正在发送（`status=1`），本接口直接 409 `CONSUMED_OUTBOX_NOT_SENT` 回滚，
  不会与在途发送竞争。

### 3.2 processed TTL 必须在"发送前"足够

重放的去重完全依赖 processed Key。若它在消息真正被消费前过期，消费端会把它当成新事件再次累计
（Redis 多算一次；DB 仍只落一次，因为 DB 只在 FLUSHED 时累计），因此判定在事务内、写之前完成：

| processed 状态 | 处置 |
| --- | --- |
| 剩余 TTL ≥ `mqRetryBackoff + max(outbox 租约, 补偿租约, confirm 超时)` | 允许一次重放 |
| 剩余 TTL 不足 | 409 `CONSUMED_PROCESSED_TTL_INSUFFICIENT`，不写任何状态 |
| `-2` 缺失 | 409 `CONSUMED_PROCESSED_EVIDENCE_MISSING`，不当作未应用 |
| `-1` 无过期 / null | 409 `CONSUMED_PROCESSED_TTL_UNKNOWN` |
| Redis 读取异常 | 409 `CONSUMED_EVIDENCE_CHECK_FAILED`，绝不 fail-open |

这个余量覆盖的是**配置内的**投递与重试窗口（relay 轮询 + confirm + 三次重试退避 + 租约接管），
不是"relay 停用数天"。剩余风险必须写明：如果 relay 长期不可用、消息一直停在 PENDING 直到
processed 过期才被投递，消费端会重新应用一次 —— Redis `current` 会多算一次，而 DB 仍只在该
事件 FLUSHED 时落一次（同一 eventId 只有一行消费账本），随后需要按既有重建/核对流程修正 Redis；
如果延迟已经跳过了 `consumed_at` 的安全窗口，消费端会拒绝应用并转回 status3，不会重复累计。
这类基础设施长时故障属于 T16，本接口不声称覆盖。

### 3.3 不清零 Outbox 三份预算：临近耗尽时再投递一次失败就会 FAILED

本接口**不**清零 Outbox 的 `retry_count` / `lease_recovery_count` /
`confirm_writeback_count`（这与 T07 单事件 Outbox 重放会归零三份预算的行为不同）。理由与第 5 节
一致：这三份计数是历史失败证据，重放只重置状态，不重置“这个 eventId 已经用掉多少失败预算”。

后果必须在操作前说清：

- 三份预算各自的上限都是 10 次，命中即 FAILED：发布失败预算耗尽时 `last_error` 记录当次发布
  失败原因，租约与 confirm 回写预算耗尽时分别是固定文案 `lease recovery budget exhausted` /
  `confirm writeback budget exhausted`。如果该 eventId 的
  预算已经接近上限，本接口重放后的这次投递**只要再失败一次**就会直接进入 FAILED，而不是像预算
  充足时那样退避重试；
- 进入 FAILED 后**没有安全自动兜底**。不能改用 T07 的
  `POST /api/admin/video-status/outbox/{eventId}/replay`：该工具对任何已存在消费账本的 eventId
  （`process_status` 0/1/2/3 都算）一律 409 拒绝，而本接口刚刚把账本从 3 改成 0，账本必然存在；
- 也不允许用裸 SQL 清零三份预算、直接改 `status`，或换一个新 eventId 重新灌入同一业务事实：
  那会绕过幂等与审计，抹掉“预算耗尽”这个诊断信号，并可能让同一业务事实被重复累计；
- 正确动作是**停下诊断**：用 `GET .../failures/{eventId}` 与
  `GET /api/admin/video-status/reconciliation?vid=` 定位根因（发布层、confirm 回写、租约接管、
  刷库），修复根因后再按后续块（DLQ/批量处置，尚未实现）或新流程处置。本接口只提供一次受控
  重放，不提供“重置预算”“换 eventId”或“无限重试”的语义。

## 4. 拒绝矩阵（HTTP 读写的同一套判定）

`GET .../precheck?source=CONSUMED` 与 `POST .../replay` 调用同一个判定方法
（`VideoStatusStatus3ReplayService#evaluate`），拒绝码一致：

| reasonCode | HTTP | 含义 |
| --- | --- | --- |
| `CONSUMED_TARGET_NOT_REPAIR_REQUIRED` | 409 | 目标不是 status3（0/1/2） |
| `CONSUMED_STATUS3_FLUSH_ORIGIN_NOT_REPLAYABLE` | 409 | 刷库型 status3（`redis_applied_at` 非空） |
| `CONSUMED_OVER_SAFE_WINDOW` | 409 | `consumed_at`（DB 时钟）或 `occurredAt`（JVM 时钟）超出安全窗口，等于上限也拒绝 |
| `EVENT_OVER_NORMAL_REPLAY_WINDOW` | 409 | `occurredAt` 超过固定 30 天普通重放上界（T14-S2）；默认 7 天窗口更严，所以默认配置下仍先返回 `CONSUMED_OVER_SAFE_WINDOW` |
| `CONSUMED_OUTBOX_LEDGER_MISSING` | 409 | 同 eventId 的 Outbox 行不存在 |
| `CONSUMED_OUTBOX_NOT_SENT` | 409 | Outbox 行不是 SENT |
| `CONSUMED_PAYLOAD_INVALID` | 422 | Outbox payload 无法解析 |
| `CONSUMED_EVENT_INVALID` | 422 | payload 不是合法事件（`validate()` 失败） |
| `CONSUMED_EVENT_MISMATCH` | 422 | eventId/vid/type/delta 与消费账本或 Outbox 行不一致 |
| `CONSUMED_FINGERPRINT_MISMATCH` | 422 | 语义指纹 ≠ 账本 `payload_hash`（重投会被判 NonRetryable 进 DLQ） |
| `CONSUMED_PROCESSED_EVIDENCE_MISSING` | 409 | processed Key 缺失（不等于未应用） |
| `CONSUMED_PROCESSED_TTL_UNKNOWN` | 409 | processed Key 无过期时间或无法判断 |
| `CONSUMED_PROCESSED_TTL_INSUFFICIENT` | 409 | processed 剩余 TTL 不足 |
| `CONSUMED_EVIDENCE_CHECK_FAILED` | 409 | 读取 processed 失败（Redis 异常，绝不 fail-open） |
| `CONSUMED_REPLAY_BUDGET_EXHAUSTED` | 409 | 该 eventId 人工重放次数已达上限 |

另外：目标行不存在 → 404；`source` 不是 `CONSUMED` → 400；未登录 → 401（拦截器 + 控制器双保险）；
`operationId`/`reason` 非法 → 400。

所有拒绝都发生在任何写之前：不写 `process_status`、不写 Outbox、不写审计、不动计数/Redis。

预检查 `replay` 子结构里的 `redisAppliedEvidencePresent` 只表示 `redis_applied_at` 是否存在
（存在即刷库型，直接拒绝）。因此 `eligible=true` 时它必为 `false`，`false` 本身不是放行条件：
真正的放行完全由上面的判定链决定。

## 5. 状态改动与"不做什么"

CAS 重置语句只允许满足全部条件的行命中（影响行数必须恰好为 1）：

```sql
UPDATE video_status_consumed_event
   SET process_status = 0, last_attempt_at = NOW(3)
 WHERE event_id = ? AND process_status = 3
   AND redis_applied_at IS NULL AND payload_hash = ?
```

明确**不**做的事：

- 不新建 eventId、不改 payload、不客户端构造事件（请求体只有 `operationId` + `reason`）；
- 不刷新 `consumed_at`：安全窗口仍以原登记时间为准，重放不"延长"事件年龄；
- 不清零 `consumer_retry_count`：历史重试是诊断证据（与 T07 对 outbox 预算归零的语义不同）；
- 不清空 `last_error`：失败详情是证据，正常 `0→1` 的 `markRedisApplied` 才会清空它；
- 不清零 Outbox 的 `retry_count` / `lease_recovery_count` / `confirm_writeback_count`；
- 不改计数、不改 Redis、不删除任何证据行。

## 6. 审计与幂等

- 动作名 `REPLAY_CONSUMED_TO_RECEIVED`（<=32 字符），写入既有的
  `video_status_consumption_repair_log`，`operation_status=1 COMPLETED`；
- `last_error` 存 `T10_STATUS3_REPLAY_META_V1` JSON：`processStatusBefore/After`、
  `consumerRetryCountBefore/After`、`consumedAgeSeconds`、`replayAttemptNo`、
  `outboxStatusBefore/After`、`processedTtlSeconds`。**不含** payload、指纹原文、lease token；
- 状态写、Outbox 退回、审计插入在同一事务：任一步影响行数不为 1 或抛异常都整体回滚；
- 同一 `operationId` + 同 eventId/动作/操作人/原因重复提交 → 返回 `duplicate=true`、
  `replayApplied=false`，before/after 从审计元数据原样重放；元数据不完整时 409（不猜）；
- 同目标并发在行锁后读取审计被吸收为 duplicate；`operation_id` 唯一键冲突整体回滚并返回 409。

## 7. 重放预算是有限的

同一 eventId 的 `REPLAY_CONSUMED_TO_RECEIVED` 审计行数就是重放次数，达到
`status3-replay-max-attempts`（默认 3，启动期校验 1..100）后拒绝。计数查询是**有界**的：
`SELECT COUNT(*) FROM (SELECT id ... WHERE event_id=? AND action=? LIMIT maxAttempts+1) x`，
走既有 `idx_video_status_repair_event(event_id, id)`。

预算只能限制次数，不能判定根因：如果刷库再次数据异常，事件会重新落回 status3，需要先定位
（刷库越界/脏数据/重建）而不是继续重放。

## 8. 运维边界

- 重放后**必须**观察收敛：Outbox 回到 SENT、consumed 到 1 再到 2 (FLUSHED)、
  `video_status` 只累计一次。若 relay 未启用，事件停在 PENDING 是预期可见状态。
- 无新增开关：本接口不受 T09 三闸门控制，也没有"允许无证据重放"的模式；事故处置时若要
  完全禁止人工重放，只能通过管理员权限/网关层限制入口。
- 重放前先看 Outbox 三份预算（`retry_count` / `lease_recovery_count` /
  `confirm_writeback_count`）：本接口不清零它们，临近耗尽时再失败一次即 FAILED。进入 FAILED 后
  不能靠 T07 重放救回（存在消费账本会 409），也不允许清零预算或换 eventId；只能停下诊断根因。
  详见第 3.3 节。
- 不提供批量、DLQ、刷库型 status3 或强制重建入口（后续块）。
- 本接口不修改 Lua/flush/rebuild 协议，也不改变 relay/消费者契约。

## 9. 验证证据（候选，未父验收）

- 纯单元（无基础设施）：`VideoStatusStatus3ReplayServiceTests` 覆盖上表每条拒绝条件、
  CAS 影响行数非 1、审计冲突、operationId 幂等形状冲突、预算上限、预检查与重放同码、
  payload 哨兵不出现在响应/审计元数据；
- 真实隔离 IT（`compose.test.yaml` 的 MySQL 13307 / Redis 16379 / RabbitMQ 15673）：
  `VideoStatusStatus3ReplayIsolatedTests` 覆盖 proven 路径经**真实 relay → 真实消费者 → 真实刷库**
  收敛到 FLUSHED 且只累计一次、各拒绝分支零变化、并发同 operationId 只应用一次、
  审计写入失败时真实 MySQL 事务整体回滚、预算耗尽、预检查不写状态；
- Mockito 不用于证明事务语义：回滚由真实 MySQL + 代理 mapper 失败注入证明。

以上都是测试环境证据，不是生产验收，也不是进程崩溃/两 JVM/Redis 数据丢失演练（T16）。
