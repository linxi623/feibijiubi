# T10 第一阶段：失败事件只读诊断与受审计确认已阅（候选）

> 状态：**仅第一阶段候选，不是 T10 完成**。DLQ 摄入/处置、consumed status3 安全重放、
> 批量处置、业务/事件账本/DB/Redis 对账仍待后续任务。本文不宣称生产验收。

## 1. 为什么先做“只读 + 审计确认”

失败事件本身已经是证据。管理员第一步需要安全地看见“哪些事件失败、失败原因大类是什么、
当前能不能安全处置”，而不是立刻改库或重发。T10 第一阶段先建立：

- keyset 分页，避免大表 OFFSET；
- 只返回安全字段，禁止 payload、leaseToken、last_error 原文出 HTTP；
- status3 预检查保守，永远不伪造 SAFE；
- 确认已阅（ACKNOWLEDGE）只写审计，不改变目标状态、计数、Redis，也不删除证据。

“确认已阅”不是“忽略后已处置完成”。接口路径、DTO、动作名都使用 `acknowledge` /
`ACKNOWLEDGE_FAILURE`，避免让管理员误以为失败事件已被修复、重放或对账。

## 2. 接口

统一在 `/api/admin/**`，由既有登录拦截器 + 管理员拦截器保护，每个方法都有 `@AdminOnly`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/video-status/failures?source=OUTBOX&afterId=0&limit=20` | Outbox FAILED（status=3）分页 |
| GET | `/api/admin/video-status/failures?source=CONSUMED&afterId=0&limit=20` | consumed status0/3 分页诊断 |
| GET | `/api/admin/video-status/failures/{eventId}?source=OUTBOX` | 安全详情 |
| GET | `/api/admin/video-status/failures/{eventId}/precheck?source=CONSUMED` | 处置预检查 |
| POST | `/api/admin/video-status/failures/{eventId}/acknowledge?source=CONSUMED` | 受审计确认已阅 |

`afterId` 是上一页最后一条的 `id`；`limit` 为 1..100。响应不包含 payload、lease token 或
`last_error` 原文，只给 `lastErrorPresent` 固定提示与安全原因码。

POST 请求体只有：

```json
{
  "operationId": "ticket-20261022-001",
  "reason": "已核对业务事实，保留证据并确认已阅"
}
```

操作人只取 `LoginInterceptor` 写入的 `currentUserId`，请求体没有 operator。

## 3. 哪些状态可以确认

| source | 可诊断 | 可确认 | 说明 |
| --- | --- | --- | --- |
| OUTBOX | status=3 FAILED | 是 | 只写审计，不重放、不改 outbox 状态 |
| CONSUMED | status=3 REPAIR_REQUIRED | 是 | 只写审计，不改 process_status、不新建 eventId |
| CONSUMED | status=0 RECEIVED | 是（只读） | **不能确认**，仅用于诊断 |

consumed status0 表示事件已收到但还没有足够证据判断是否已应用。它不能被“确认”成已处置，
也不能被本接口领取、重发或转 status3。对 status0 调用 `acknowledge` 返回 409，且不写审计。
其它 consumed 状态（1/2）同样拒绝确认。

## 4. status3 预检查为什么不返回 SAFE

对 `video_status_consumed_event.process_status=3`：

- `redis_applied_at` 为空只能说明“没有这条时间证据”，**不能证明 Redis 未应用**；
- 超过安全窗口也不能证明“没应用过所以可以重放”；
- 本阶段不做强制重建、不改 status、不创建新 eventId。

因此预检查总是 `REQUIRES_CONTROLLED_REPAIR`，并给出可诊断原因码，例如：

- `CONSUMED_REDIS_APPLIED_EVIDENCE_MISSING`
- `CONSUMED_OVER_SAFE_WINDOW`
- `CONSUMED_REDIS_EVIDENCE_MISSING_AND_OVER_SAFE_WINDOW`
- `CONSUMED_REQUIRES_CONTROLLED_REPAIR`

status0 只返回 `DIAGNOSTIC_ONLY_EVIDENCE_UNKNOWN`，且 `canAcknowledge=false`。

## 5. 确认已阅的幂等与事务

- 复用 `video_status_consumption_repair_log`，不新增表；
- 动作名 `ACKNOWLEDGE_FAILURE`，`operation_status=1`（COMPLETED）；
- `operation_id` 唯一；相同 operationId + 相同目标/来源/动作/操作人/原因才返回 duplicate；
- 相同 operationId 但目标、source、动作、操作人或原因不同一律 409；
- 审计元数据 `T10_ACKNOWLEDGE_META_V1` 只记录 source 和 before/after 状态；
  **before 与 after 必须相同**，证明该动作没有修改目标；
- 不写 payload、令牌或 last_error 原文；
- 整个 acknowledge 在事务中，审计写失败/唯一键冲突整体回滚；
- 目标状态、计数、Redis 完全不变，失败证据不删除。

响应同时返回 `statusBefore/statusAfter` 与 `processStatusBefore/processStatusAfter`，调用方可以
直接核对 before == after；重复请求返回 `duplicate=true` 且 `acknowledgedApplied=false`。

## 6. 验证边界

本阶段单测只验证服务与接口边界，不连接 MySQL/Redis/MQ，也不运行 `contextLoads`。
真实分页 SQL、事务回滚和跨进程并发仍应在独立 `compose.test.yaml` 环境做后续验证。
这些单测通过不代表 T10 整体完成或生产验收。

## 7. 仍未完成

- RabbitMQ DLQ 摄入、分页、处置、重放；
- consumed status3 受控重放与安全证明；
- 批量操作与工单系统对接；
- 业务关系真值 / 事件账本 / DB 基线 / Redis 派生值对账；
- 需要强制重建时的维护流程。
