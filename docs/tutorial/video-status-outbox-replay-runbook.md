# Outbox FAILED 受审计重放 Runbook（T07 临时运维 / T10 极窄子集）

> 状态：T07 实现提交 `eec1e01`，**等待父代理验收**；本工具已实现单事件查询与重放，
> 但计划表暂标“待验收”，不宣称生产上线或完整 T10 能力。
>
> 适用范围：**单个** `video_status_outbox` 事件、`status = 3 (FAILED)`、且能证明安全重放的情况。
>
> **这不是完整 T10。** 已实现：单个 FAILED 事件的安全只读查询 + 受审计重放到 PENDING。
> 明确**未实现**（仍属 T10）：DLQ 分页查询/重放、`video_status_consumed_event.process_status = 3`
> （REPAIR_REQUIRED）修复、对账（业务真值 / 事件账本 / DB 基线 / Redis 派生值）、人工确认忽略、
> 批量处置、`status 0/1` 的人工干预。遇到这些情况本工具会**拒绝**并给出原因，不会偷偷改状态。
>
> 本工具**不会**新建 eventId、不会改 payload/vid/type、不会写 `video_status` 或消费账本、不碰 Redis。

---

## 1. 能力边界（先看这一节）

| 入口 | 方法 | 行为 |
|---|---|---|
| `/api/admin/video-status/outbox/{eventId}` | GET | 只读安全状态：status/statusName、retry_count、lease_recovery_count、confirm_writeback_count、created_at/next_retry_at/sending_at/sent_at、`lastErrorPresent`/`lastErrorHint`、consumedProcessStatus、replayEligible + 阻断原因。**不返回 payload、leaseToken 与 last_error 原文** |
| `/api/admin/video-status/outbox/{eventId}/replay` | POST | 受审计重放：FAILED → PENDING，同一事务写 `COMPLETED` 审计（含 before/after 元数据） |

- 两个接口都在 `/api/admin/**` 上，沿用既有 `LoginInterceptor` + `AdminInterceptor`，
  方法上还有 `@AdminOnly`；没有新增 actuator 或未鉴权端口。
- `last_error` 原文**永不通过 HTTP 返回**：历史版本曾把异常/请求细节截断写入该列，可能含 payload 片段或凭证。
  GET 只给 `lastErrorPresent`（布尔）与固定提示 `lastErrorHint`；需要原文时由管理员**受控查库**诊断。
- POST 请求体只有 `operationId`（≤64，幂等键）与 `reason`（必填）。**operator 不能由请求体提供**，
  一律取登录态的 `currentUserId`；缺失时直接 401。
- 错误统一走 `BusinessException` / `GlobalExceptionHandler`：
  `400` 参数非法、`401` 未登录（缺操作人）、`404` eventId 不存在、
  `409` 状态/窗口/并发/幂等冲突、`422` payload 非法或与行不一致。

## 2. 允许重放的必要条件（全部满足才执行）

1. 行存在且 `status = 3 (FAILED)`；
2. **双重安全年龄**：`created_at`（DB 时钟，`TIMESTAMPDIFF(SECOND, created_at, NOW(3))`）
   与 payload 的 `occurredAt`（生产端 JVM 时钟）都必须**严格小于**
   `app.video-status.consumer-recovery-auto-replay-max-age-seconds`（默认 604800 秒 = 7 天）——
   **正好等于上限也拒绝**；`occurredAt` 在未来（> now+5min）直接拒绝（防伪造新鲜度）；
3. `payload` 可解析为 `VideoStatusChangedEvent`、`event.validate()` 通过，
   且 payload 的 `eventId / vid / type` 与数据库行完全一致；
4. **消费账本必须完全不存在**：只要 `video_status_consumed_event` 已有该 eventId 的行（0/1/2/3 任一），
   一律拒绝——`2` 表示已完成无需重投，`0/1` 交自动恢复（T09），`3` 交 T10。
   临时工具不假设“consumed 0/1/2 下重放一定安全”，也不改统计/consumed/current；
5. `operationId` 未被其它（eventId, action, operator, **reason**）组合占用。

不满足就**保持 FAILED 隔离**，人工核对业务影响后走 T10/业务补偿流程，不要用裸 SQL 改状态。

## 3. 标准操作流程

### 步骤 1：只读定位

```
GET /api/admin/video-status/outbox/{eventId}
```

检查：`statusName`、`retryCount`、`leaseRecoveryCount`、`confirmWritebackCount`、`lastErrorPresent`、
`consumedProcessStatus`、`replayEligible` / `replayBlockedReason`。

失败原因原文需要**受控查库**获取（不要在工单/聊天里粘贴），因为历史 `last_error`
可能混入 payload 片段或凭证：

```sql
SELECT event_id, last_error FROM video_status_outbox WHERE event_id = '<eventId>';
```

常见 T07 语义：

- `lease recovery budget exhausted`：租约过期恢复耗尽（先修 confirm 超时/回写失败/反复重启的根因）；
- `confirm writeback budget exhausted`：confirm ACK 已成功但 `markSent` 连续 10 次回写失败；
  先查 T07 回写/DB 根因，再评估是否重放；不要把它误认为发布失败；
- `PUBLISH_*`：发布失败耗尽；
- `PAYLOAD_JSON_INVALID` / `EVENT_INVALID` / `EVENT_MISMATCH_*`：非法事件，**不可安全重放**。

### 步骤 2：评估是否真的需要重放

| 情况 | 处置 |
|---|---|
| payload 非法 / 与行不一致 | 不要重放；保持 FAILED，人工核对业务影响（T10） |
| `consumedProcessStatus` 非空（0/1/2/3） | 不要重放；`2` 已完成无需重投，`0/1` 交自动恢复（T09），`3` 需要 T10 修复 |
| `created_at` 或 `occurredAt` 达到/超过安全窗口（含边界） | 不要重放；走 T10 对账/业务补偿 |
| 已 `SENT` / 仍在 `PENDING`/`SENDING` | 不要人工干预；GET 会给出明确原因 |
| FAILED + payload 合法一致 + 双窗口内 + **无任何 consumed 行** | 可以执行步骤 3 |

### 步骤 3：受审计重放

```
POST /api/admin/video-status/outbox/{eventId}/replay
{
  "operationId": "OPS-2026-000123",
  "reason": "publish confirm 超时耗尽预算，根因已修复，需重投原事件"
}
```

- `operationId` 建议用工单号：**同一 operationId 且 eventId/action/operator/reason 全部相同时**重复提交
  不会重复动作，返回 `duplicate=true`、`replayApplied=false`，before/after 从审计元数据原样重放，
  `current*` 字段给出查询时的实时状态；
- 同一 operationId 若换 eventId / 换 operator / **换 reason** 复用，都会被 409 拒绝
  （reason 变了不允许伪装成原结果，防止用新原因复用旧动作）；
- 并发写同一 operationId 时唯一键拒绝，事务整体回滚，返回 409 并说明“未重复执行”。

成功响应关键字段：

- `replayApplied=true`、`duplicate=false`；
- `retryCountBefore/After`、`leaseRecoveryCountBefore/After`、`statusNameBefore/After`
  与写入审计 `last_error` 的 JSON 元数据一致；
- `currentStatusName/currentRetryCount/currentLeaseRecoveryCount` 是实时状态，与历史 before/after 分开。

### 步骤 4：核对审计与状态（直接查库，只读）

```sql
SELECT operation_id, event_id, action, operation_status, reason, operator, last_error, created_at, completed_at
FROM video_status_consumption_repair_log
WHERE operation_id = 'OPS-2026-000123';

SELECT event_id, aggregate_id, event_type, status, retry_count, lease_recovery_count,
       confirm_writeback_count, next_retry_at, sending_at, lease_token, last_error
FROM video_status_outbox
WHERE event_id = '<eventId>';
```

期望：审计 1 行且 `operation_status = 1 (COMPLETED)`、`action = REPLAY_OUTBOX_TO_PENDING`
（与状态重置**同事务**提交）；新审计的 `last_error` 是 `T07_REPLAY_META_V2` JSON，
含 `statusBefore/retryBefore/leaseRecoveryBefore/confirmWritebackBefore` 与
`statusAfter/retryAfter/leaseRecoveryAfter/confirmWritebackAfter`；历史 V1 行缺少
confirmWriteback 字段时按未知保留，不补造数据；outbox `status = 0`、
`retry_count = lease_recovery_count = confirm_writeback_count = 0`、
`next_retry_at / sending_at / lease_token = NULL`、`event_id` 与 `payload` 不变。

### 步骤 5：观察重投与消费

- 看 relay 日志/`PUBLISH` 指标与 backlog 采样：事件应被重新投递；
- 消费端对同一 `eventId` 幂等（`video_status_consumed_event` 唯一键 + Redis processed），
  重复投递不会双计数。**重放不等于“保证成功”**：若 publish 继续失败，会重新累计
  `retry_count`，再次进入 FAILED，需要回到步骤 1。

## 4. 回滚与事故处理

- 重放动作本身**没有逆操作**：它只是把事件放回 PENDING。要“撤回”只能再次评估该事件，
  或按业务补偿流程处理；不要用改 eventId / 改 payload / 直接 UPDATE 状态的方式撤销。
- 事务边界：状态重置（`status=3` 的 CAS，必须影响 1 行）、审计插入（必须影响 1 行）
  与 before/after 元数据在同一 MySQL 事务内，任一失败/并发冲突整体回滚 ——
  不会出现“状态改了但没审计”或“有审计但状态没改”。
- 回滚本次工具本身：删除/停用 repair controller+service+mapper 即可；它不改变 relay 的发布/回写语义。
  不要回滚 T07 的 `VideoStatusOutboxMapper` 新方法或两份 migration 而保留新 relay，否则会编译/运行失败。
- `last_error` 在重放后**保留**（失败证据），成功 publish 后由既有 `markSent` 清空。

## 5. 为什么不允许“简单改状态 / 新建 eventId”

- 新建 eventId 会绕过消费端 eventId 幂等，可能造成重复计数；
- 直接把 FAILED 改成 PENDING 而不校验 payload/窗口/消费账本，等于把非法事件重新灌进链路；
- 把 `retry_count` 手工归零而不留审计，会抹掉真实失败证据。
  本工具把预算归零定义成“管理员重放后的新预算”，并在响应与 `repair_log` 中留痕。

## 6. 测试与验收证据

- `VideoStatusOutboxRepairAuthorizationTests`（纯 mock）：管理员映射 / `@AdminOnly` / GET+POST 路径、
  DTO 参数边界（operationId ≤64、reason 必填、**无 operator 字段**）、缺登录态 401、
  operator 取 `currentUserId`、唯一键冲突 → 明确 409、GET/POST VO 不含 payload/leaseToken/last_error 原文。
- `VideoStatusOutboxReplayIsolatedTests`（真实 MySQL 13307，`-DvideoStatusIT=true`）：
  审计与状态同事务（外层回滚后仍 FAILED 且零审计）、**审计插入失败真实回滚**（mapper 仅在
  `insertCompletedRepairLog` 抛异常，状态重置随之回滚）、同一 operationId+reason 不二次动作/不重复审计、
  **同一 operationId 换 reason → 409 且原审计不变**、**consumed 0/1/2/3 全部拒绝且账本/统计不变**、
  **created_at 与 occurredAt 双窗口（含等于边界）及未来 occurredAt 拒绝且零审计**、
  **before/after 持久化到 repair_log.last_error 且 duplicate 从元数据重放**、
  非法 payload/eventId 不一致/SENT/PENDING/SENDING 均拒绝、GET 安全字段（last_error 只给布尔+固定提示）。

T07 聚焦验收（父代理 2026-10-22 独立复跑）：

```bash
./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests' test
```

结果：**61 tests，0 failures / 0 errors / 0 skipped**，日志 `/tmp/improvement-t07-verify.log`。
这批准入仍属隔离测试环境，不是生产验收，也没有做真实进程崩溃演练。

## 7. 与 T10 的关系

| T10 能力 | 本工具 |
|---|---|
| Outbox FAILED 单个查询 | ✅（安全字段，不含 last_error 原文） |
| Outbox FAILED 单个受审计重放 | ✅（本 runbook） |
| FAILED / DLQ 分页查询与批量处置 | ❌ T10 |
| 任何 consumed 记录（含 `process_status = 3`）修复 | ❌ T09/T10（本工具遇到任何 consumed 行直接拒绝） |
| 对账与人工确认忽略 | ❌ T10 |
| status 0/1 的人工干预 | ❌ 仍由自动投递/租约恢复负责 |

因此：**不要**用本工具宣布 T10 完成；T07 的验收要点是“FAILED 在 T10 之前已有可执行、可审计的临时运维入口”，
而不是“失败事件处置已闭环”。
