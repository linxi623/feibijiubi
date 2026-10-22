# T10 第一阶段：失败事件只读诊断与受审计确认已阅（已合入，T10 未完成）

> 状态：**第一阶段已合入主分支 `6fc5e7f`，T10 整项仍未完成**。DLQ 摄入/处置、
> consumed status3 安全重放、批量处置仍待后续任务；只读对账已作为同一阶段的另一入口合入，
> 见 `video-status-reconciliation.md`。本文不宣称生产验收。

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

`source` 默认 `OUTBOX`、大小写不敏感；`afterId` 是上一页最后一条的 `id`（`>=0`）；
`limit` 为 1..100、默认 20。服务端最多读 `limit+1` 行判断 `hasMore`，`nextAfterId` 仅在
`hasMore=true` 时给出。响应不包含 payload、lease token 或 `last_error` 原文，只给
`lastErrorPresent` 固定提示与安全原因码。

列表查询要求索引 key 顺序精确匹配：OUTBOX 为 `(status,id)`，CONSUMED 为
`(process_status,id)`。缺索引或顺序不符时返回 **HTTP 503（code=503）且不扫描**，并提示执行
`database/migrations/t10-video-status-failure-admin-indexes.sql`。其它未处理数据库异常按全局规则
返回 HTTP 500。

Outbox 的 `retry_count`、`lease_recovery_count`、`confirm_writeback_count` 只用于生成安全原因码
并辅助诊断；历史累计值不代表当前失败的真实原因。

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

status0 只返回 `DIAGNOSTIC_ONLY_EVIDENCE_UNKNOWN`，且 `canAcknowledge=false`。Outbox 预检查同样
永远返回 `REQUIRES_CONTROLLED_REPAIR`，不会因为计数未达预算或时间窗看起来正常就认证 SAFE。

## 5. 确认已阅的幂等与事务

- 复用 `video_status_consumption_repair_log`，不新增表；
- 动作名 `ACKNOWLEDGE_FAILURE`，`operation_status=1`（COMPLETED）；
- `operation_id` 唯一；相同 operationId + 相同目标/来源/动作/操作人/原因才返回 duplicate；
- 相同 operationId 但目标、source、动作、操作人或原因不同一律 409；
- 审计元数据 `T10_ACKNOWLEDGE_META_V1` 只记录 source 和 before/after 状态；
  **before 与 after 必须相同**，证明该动作没有修改目标；
- 不写 payload、令牌或 last_error 原文；
- 整个 acknowledge 在事务中，审计写失败/唯一键冲突整体回滚；
- 目标状态、计数、Redis 完全不变，失败证据不删除；
- 相同 operationId、相同目标/来源/动作/操作人/原因重复提交时返回 `duplicate=true`、
  `acknowledgedApplied=false`；同目标并发请求在目标行锁后再次读取审计，可被吸收为 duplicate；
  不同目标，或来源/动作/操作人/原因不一致的 operationId 复用返回 409；
- 唯一键冲突或审计写入异常整体回滚，审计写失败不会留下半条确认。

响应同时返回 `statusBefore/statusAfter` 与 `processStatusBefore/processStatusAfter`，调用方可以
直接核对 before == after。`acknowledge` 不是“忽略”：它不会把事件标记为已修复，也不会删除证据；
失败事件仍需后续 DLQ/status3 受控处置。

## 6. 验证边界

父代理 2026-10-22 在独立测试环境实跑一组 T10/T07/T09 用例（日志
`/tmp/improvement-t10-main-it.log`）：**121 tests，0 failures / 0 errors / 0 skipped**，其中包含
`VideoStatusFailureAdminAuthorizationTests`、`VideoStatusFailureAdminIsolatedTests`、
`VideoStatusFailureAdminServiceTests`，以及 T07 replay 和 T09 compensation 用例；普通单测日志
`/tmp/improvement-t10-main-final-unit.log` 为 **416 tests，0 failures / 0 errors，130 skipped**
（skipped 为 opt-in 隔离开关关闭的用例）。

隔离测试已覆盖真实分页 SQL 使用索引、分页/详情不泄露敏感字段、status0 拒绝确认、同 operationId
并发只产生一条完成审计、审计写入失败回滚且目标不变。以上是**测试环境证据，不是生产验收**，
也不代表 T10 整体完成；跨进程/真实 DLQ/任意数据库故障仍不在本阶段保证范围。

## 7. 仍未完成

- RabbitMQ DLQ 摄入、分页、处置、重放；
- consumed status3 受控重放与安全证明（本阶段只允许确认已阅，不重放/不改状态）；
- 批量操作与工单系统对接；
- 自动修复与收敛证明（只读对账入口只展示非原子观测，不给出 SAFE）；
- 需要强制重建时的维护流程（T12）。
