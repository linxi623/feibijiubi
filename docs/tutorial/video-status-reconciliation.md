# 单视频事件对账教程：只读诊断，不证明收敛（T10 第一阶段）

> 状态：实现已合入主分支 `6fc5e7f`，属于 T10 第一阶段。T10 整项仍未完成：DLQ 处置、
> consumed `process_status=3` 受控重放、批量操作和自动修复仍未实现。
> 本文描述的是**测试环境已验证的只读诊断入口**，不是生产验收，也不宣称“对账通过/已收敛”。

## 1. 为什么需要“对账”，但不能给出 SAFE

同一条视频的互动统计会同时出现在多个位置：

- MySQL `video_status` 的八项计数（DB 当前基线）；
- `video_status_consumed_event` 的事件账本（不同 `process_status` 的数量与最近事件窗口）；
- `user_video` 中的业务关系真值（点赞/点踩/投币/收藏）；
- Redis 的 current Hash（异步链路当前派生值）。

这些值由不同事务、不同线程、不同存储分别更新。一次 HTTP 请求里依次读取它们，得到的是
**时间上可能错位的观测值**，不是一致性快照。即使四个数字碰巧相等，也不能证明没有在途事件、
没有截断、没有字段损坏，更不能证明后续自动修复安全。

因此本接口：

- 固定返回 `mode=READ_ONLY_DIAGNOSTIC`、`readOnly=true`、`writePerformed=false`、
  `automaticRepair=false`；
- 固定返回 `observationBoundary=CONCURRENT_OBSERVATION_NOT_ATOMIC`；
- 固定返回 `verdict=NO_SAFE_CONCLUSION_AUTOMATIC_REPAIR_DISABLED`，**永远不返回 SAFE**；
- 不写 MySQL/Redis，不领取租约，不重放/创建事件，不修改任何状态。

“对账”在这里的含义是：帮管理员看清各来源的观测值、边界和不确定性，而不是自动判定谁对谁错。

## 2. 接口与参数

```http
GET /api/admin/video-status/reconciliation?vid=123&limit=100
```

管理员接口，路径在 `/api/admin/**` 下并标注 `@AdminOnly`，未登录/非管理员由既有拦截器拒绝。

| 参数 | 必填 | 取值 | 说明 |
| --- | --- | --- | --- |
| `vid` | 是 | 正整数 | 视频 ID；`<=0` 返回 HTTP 400 |
| `limit` | 否 | `1..1000`，默认 `100` | 账本/事件/关系查询的上界；越界返回 HTTP 400 |

所有查询都带 SQL `timeout` 和 `LIMIT` 上界，不使用无界 `COUNT`/`SUM`。`limit` 越大并不代表
结论越安全；达到上界时只会得到保守的“下界”标记。

## 3. 响应结构

成功响应包在统一 `ApiResponse` 中，顶层数据字段如下：

| 字段 | 含义 |
| --- | --- |
| `vid` / `limit` | 本次请求参数 |
| `mode` | 固定 `READ_ONLY_DIAGNOSTIC` |
| `readOnly` | 固定 `true` |
| `writePerformed` | 固定 `false` |
| `automaticRepair` | 固定 `false` |
| `observationBoundary` | 固定 `CONCURRENT_OBSERVATION_NOT_ATOMIC` |
| `incomplete` | 是否至少有一个来源被截断、缺失、未知或不可采样 |
| `uncertainties` | 明确的不确定项列表，见下文 |
| `verdict` | 固定 `NO_SAFE_CONCLUSION_AUTOMATIC_REPAIR_DISABLED` |
| `db` / `ledger` / `eventProjection` / `relations` / `redis` | 各来源观测 |
| `note` | 中文边界说明 |

`incomplete=true` 不是异常，而是本接口的正常诊断输出：它表示“当前观测不足以支持任何结论”。

## 4. 各来源的精确语义

### 4.1 DB 八项计数 `db`

`rowPresent` 表示 `video_status` 是否存在该 `vid` 的统计行；存在时返回
`playTimes`、`likeTimes`、`unlikeTimes`、`commentTimes`、`coinTimes`、`shareTimes`、
`collectTimes`、`danmuTimes`。

统计行不存在时八项计数全部为 `null`，并加入 `DB_STATUS_ROW_MISSING`，**不按 0 处理**。
这些值只代表本次 SELECT 读到的 DB 当前值。

### 4.2 事件账本状态数量 `ledger`

账本按 `process_status=0/1/2/3` 分别计数。每个状态独立使用 `cap = limit + 1` 作为上限：

- 某状态计数达到 `cap` 时，该状态只是“至少 cap 条”，对应
  `status0Capped`/`status1Capped`/`status2Capped`/`status3Capped` 为 `true`；
- 任一状态被封顶时，汇总 `truncated=true`；
- 未被封顶的状态在该次查询中是精确值，但仍不是 DB 真值，也不能据此推断历史总量。

关键点：**每个状态独立截断**。不能因为 status0 被截断就否认 status1 的精确性，也不能把四个
状态数量相加当作历史 ledger 总量。

### 4.3 最近事件窗口 `eventProjection`

事件窗口按 `(vid,id)` 倒序读取，最多读取 `limit + 1` 行：

- 第 `limit + 1` 行**只用于判断截断**，不进入 `rowCount` 或 `deltaByType` 汇总；
- 实际汇总最近 `limit` 行，`deltaByType` 用 `BigInteger` 防止 long 溢出；
- 未知事件类型计入 `unknownTypeCount`/`unknownTypeDelta`，null delta 计入 `nullDeltaCount`；
- 截断时 `truncated=true`，窗口只是“最近 limit 行”的下界，不能代表全部历史；
- 未截断也不等于 DB 八项计数真值，只是该 vid 的事件投影。

响应还给出 `minEventId`/`maxEventId` 便于定位窗口。若缺少 `(vid,id)` 有效前缀索引，
服务端会**只跳过这个昂贵投影查询**：`indexAvailable=false`、窗口为空、
`uncertainties` 包含 `INDEX_MISSING`、`incomplete=true`；DB 计数、账本、关系和 Redis 仍会
继续执行。缺索引不是 HTTP 500，也不会退化为对大表做 filesort。

### 4.4 业务关系真值 `relations`

从 `user_video` 对单个 `vid` 最多取 `cap` 行，再聚合：

- `sampledRows`、`likedRows`、`unlikedRows`、`coinSum`、`collectRows`；
- 达到 `cap` 时 `truncated=true`、`lowerBound=true`、`usableForConclusion=false`；
- 未截断也只代表读取时刻的业务关系，不代表与事件账本同一事务。

### 4.5 Redis current 采样 `redis`

只对八个计数字段做一次只读的多字段 Hash 读取（`HMGET`）并逐个校验 canonical 非负十进制 `Long`。`status` 明确区分：

| status | 含义 | 计数字段 |
| --- | --- | --- |
| `SAMPLED` | 八个字段都成功解析 | 返回真实采样值 |
| `NOT_SAMPLED_OR_MISSING` | Hash 或八个字段整体缺失 | 全部 `null` |
| `INCOMPLETE_FIELDS` | 只有部分字段存在 | 全部 `null`，`invalidFields` 列出缺失字段 |
| `INVALID_COUNTER` | 负数、前导零、小数、科学计数法、空白或溢出 Long | 全部 `null`，`invalidFields` 列出非法字段 |
| `READ_FAILED` | Redis 读取异常 | 全部 `null` |

Redis 读取异常会被显式记录为 `READ_FAILED` + `REDIS_READ_FAILED` 不确定项，**不会把未知计数
伪造成 0**，也不会因此推断“已收敛”或“需要修复”。

## 5. 何时 `incomplete=true`

常见不确定项：

- `CONCURRENT_OBSERVATION_NOT_ATOMIC`：始终存在，提示各来源不是同一快照；
- `LEDGER_COUNTS_TRUNCATED`：至少一个 ledger 状态达到 cap；
- `EVENT_PROJECTION_TRUNCATED`：事件窗口达到 `limit`；
- `INDEX_MISSING`：缺少 `(vid,id)` 索引，事件投影跳过；
- `EVENT_PROJECTION_UNKNOWN_TYPE` / `EVENT_PROJECTION_NULL_DELTA`：窗口内有未知类型或空 delta；
- `RELATION_SAMPLE_TRUNCATED` / `RELATION_TRUTH_UNAVAILABLE`：关系采样截断或查询无聚合行；
- `DB_STATUS_ROW_MISSING`：DB 统计行不存在；
- `REDIS_NOT_SAMPLED` / `REDIS_SAMPLE_INCOMPLETE` / `REDIS_SAMPLE_INVALID` / `REDIS_READ_FAILED`。

只要出现任一情况，`incomplete=true`，调用方不得据此认定数据一致、已收敛或可自动修复。

## 6. 迁移前置与索引

使用本对账接口前必须先执行：

```bash
mysql <数据库> < database/migrations/t10-video-status-reconciliation-indexes.sql
```

如果同时使用失败列表，还需执行
`database/migrations/t10-video-status-failure-admin-indexes.sql`。两份迁移都是幂等的，只加索引，
不改列、不改事件状态、不清数据：

- `idx_video_status_consumed_vid_id (vid, id)`：单视频事件窗口；
- `idx_video_status_outbox_status_id (status, id)` 与
  `idx_video_status_consumed_status_id (process_status, id)`：失败列表有界 keyset 分页。

两个 T10 索引均已镜像到 `database/feibijiubi.sql`。新库按 schema 初始化即可；旧库仍需手动执行
迁移。大表加索引建议低峰执行并观察 IO/主从延迟。

## 7. 错误与安全边界

- 参数非法（`vid<=0`、`limit<1` 或 `>1000`）：HTTP 400；
- 未授权/非管理员：由登录与管理员拦截器拒绝；
- 数据库未处理异常：按全局异常处理规则包装为 HTTP 500；
- Redis 读取异常：不是 500，而是响应内 `redis.status=READ_FAILED`，计数为 `null`；
- 缺少事件窗口索引：不是 500，而是 `INDEX_MISSING` + `incomplete=true`，其它查询继续；
- 响应不含 payload、`last_error` 原文、租约 token 或任何凭证；
- 接口不写 Redis/MySQL、不重放、不自动修复，也不提供“一键对平”入口。

## 8. 验证记录与未完成项

T10 第一阶段的主分支验证由父代理在独立测试环境完成，日志
`/tmp/improvement-t10-main-it.log`：**121 tests，0 failures / 0 errors / 0 skipped**，包含
`VideoStatusReconciliationAuthorizationTests`、`VideoStatusReconciliationIsolatedTests`、
`VideoStatusReconciliationTests`，以及失败管理、T07 replay、T09 compensation 用例；普通单测
`/tmp/improvement-t10-main-final-unit.log`：**416 tests，0 failures / 0 errors，130 skipped**。

已验证的真实隔离场景包括：真实 MySQL 读取八项计数（含 `Long.MAX_VALUE`）、真实索引迁移幂等、
分页窗口确实走 `(vid,id)` 索引、账本/关系 cap 截断、Redis 缺字段/非法值/读取异常不伪造 0、
缺索引时跳过事件投影且其它查询继续。

仍未完成、因此 **T10 不能勾选**：

- RabbitMQ DLQ 摄入、分页、处置与重放；
- consumed `process_status=3` 的受控重放与安全证明；
- 批量操作、工单/审计流程对接；
- 自动修复、自动收敛判定或生产容量/跨进程故障验收（T12/T16）。

本接口是“看清楚”，不是“修好”。任何修复都必须另行走 T09/T12 的受控、受审计协议。
