# 事件归档与 Redis 容量治理：T14-A 只读核查工具（候选）

> 状态：**T14-A 候选，未验收**（已按父审定论修复“有界 SCAN 实际无界”的问题，见 5.2）。本切片只交付“只读容量核查 + 归档 dry-run 报告”，
> **不删除、不归档、不改任何数据、不改 schema、不新增 HTTP 接口、不新增生产调度器**。
> 保留期与重放窗口政策已于 2026-10-23 由用户批准并记录在 §9.1；**本轮用户确认了各期限的起算点、DLQ 处置 SLA 起点与归档存储方式**
> （§9.1.1，未新增日历日期）。**归档、删除仍然没有任何实现**：小批归档执行、断点续跑、归档后重放不重复计数的演练
> 都还没做，见文末“未做”。**30 天重放守卫已有两个候选切片**：T14-S1（2026-10-24，消费登记前的绝对年龄拒绝）
> 与 T14-S2（同日，T07/T10-B1/T09 三个管理入口接入同一固定上界，§9.3.2），**不构成归档授权**，
> dry-run 仍 `NOT_AUTHORIZED_FOR_DELETION`（§9.3.1、§9.3.2）。
> 本轮只做**文档**：不改 Java/SQL/配置，不执行测试/迁移/DB/Redis/Docker，不提交。
> 字段与接口的真实语义核查见 §9.2（含缺口表），下一步最小可测试切片建议见 §9.3——两者都是**建议、待父审**，不是已批准的实施计划。

## 1. 为什么先做只读核查

T14 的目标是“事件归档 + 幂等保留 + Redis 容量治理”。但清理是**不可逆**动作，而当前仓库还缺三件前提：

1. **保留期政策已批准、起算点也已确认，但没有任何执行实现**：成功终态在线 90 天 / 归档副本 275 天、
   DLQ 30 天处置 SLA、审计 365 天、普通重放窗口 30 天都已决策（§9.1），各期限的**起算点与归档存储**已于本轮用户确认
   （§9.1.1），但归档与删除都还没写；30 天重放守卫已有消费登记（T14-S1，§9.3.1）与三个管理入口
   （T14-S2，§9.3.2）的候选切片，relay 与自动重放/恢复路径仍不拒绝；
2. **没有“旧消息硬拒绝/去重墓碑”**：消费事件表 `video_status_consumed_event` 同时承担幂等证据
   与去重账本，删掉历史行就等于删掉去重证据；
3. **`processed` key 的 TTL 不是删除 DB 幂等证据的充分条件**：`video-status-increment.lua` 写入的
   `video:status:process:v1:<eventId>` 只是 Redis 侧短期幂等标记（TTL 上限 3650 天，可被
   noeviction 下的内存压力或运维操作影响），它存在/不存在都不能证明“这条 DB 行可以安全删除”：
   一旦 Redis 侧证据过期而 DB 行又被删，旧消息重投就可能重复计数。

所以 T14-A 只做**可测、可复现、零副作用**的那一半：把“现在到底占多少、按假设增长多快、
哪些行是终态候选”量出来，并把“不能删”的结论写进报告。

## 2. 交付物与边界

| 项 | 内容 |
|---|---|
| 工具 | `src/test/java/com/feibijiubi/backend/videostats/capacity/`（测试作用域，非生产代码） |
| `VideoStatusCapacityAudit` | 只读审计：`information_schema` 体积估算、终态窗口 dry-run、Redis 只读指标、有界 `SCAN`（**命令次数**上限）+ `MEMORY USAGE` 抽样（独立采样上限） |
| `VideoStatusCapacityEstimator` | 纯计算：容量公式、区间、UNKNOWN 语义（无基础设施即可单测） |
| `VideoStatusCapacityReport` | 报告模型与中文文本渲染（不含连接串/凭证/eventId/payload） |
| `VideoStatusCapacityAuditIsolatedTests` | 真实隔离验收（MySQL 13307 / Redis 16379），9 例，opt-in |
| `VideoStatusCapacityEstimatorTests` / `VideoStatusCapacityReportTests` / `VideoStatusCapacityAuditScanTests` | 纯单测 11 + 2 + 10 例，普通回归即跑；`...ScanTests` 专门锁定 SCAN 的命令次数边界 |
| HTTP / MQ / schema | **无变化**：没有新接口、没有新迁移、没有新队列 |

明确不做（本切片刻意不包含）：`DELETE`/`UPDATE`/`INSERT`/DDL、`KEYS`、全表扫描、
任何 Redis 写命令、`CONFIG SET`、`FLUSHALL`/`FLUSHDB`、删除重建门闩或 `processed` key、
生产调度器、生产保留期的执行/清理器、部署。也**不**使用 Spring Data 的 `Cursor#hasNext()`
（原因见 5.2：它在匹配为空时会自己连续请求 SCAN，等于命令次数无界）。

## 3. 运行方法

先启动独立环境（**永远不要**和正式 `compose.yaml` 合并）：

```bash
docker compose -f compose.test.yaml up -d --wait
```

受限堆串行跑（本机 JDK 21 下 Mockito inline mock maker 需要 `allowAttachSelf`）：

```bash
MAVEN_OPTS='-Xms64m -Xmx256m' ./mvnw -o -DvideoStatusIT=true \
  -DvideoStatusCapacityAssumedDailyEvents=100000 \
  -DvideoStatusCapacityAssumedRetentionDays=30 \
  -DargLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true' \
  -Dtest=VideoStatusCapacityAuditIsolatedTests test
```

报告默认写到系统临时目录（`Files.createTempFile`），测试会打印绝对路径；
也可以用 `-DvideoStatusCapacityReportPath=<path>` 指定（父目录会自动创建）。

不做任何基础设施也能跑公式与渲染用例：

```bash
MAVEN_OPTS='-Xms64m -Xmx256m' ./mvnw -o \
  -DargLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true' \
  '-Dtest=VideoStatusCapacityEstimatorTests,VideoStatusCapacityReportTests' test
```

### 3.1 参数（`-DvideoStatusCapacity*`）

| 参数 | 默认 | 语义 |
|---|---|---|
| `videoStatusCapacityLimit` | 20 | 每个终态窗口最多列出的行数（1..1000，内部 `LIMIT 上限+1` 探测截断） |
| `videoStatusCapacityWindowDays` | 30 | dry-run 的“可见时间窗”：只考虑早于 `now-N 天` 的行（1..3650）。**这是可见窗口假设，不等于 §9.1 已批准的政策**（默认值 30 恰与普通重放窗口相同，但两者无关联）。注意：工具的时间列是 outbox `created_at` / consumed `consumed_at`，**不是** §9.1.1 确认的终态完成时间（`sent_at` / `flushed_at`），所以它只回答“多老的行占多少空间”，**不能**当成 90 天在线保留的实现口径 |
| `videoStatusCapacityAssumedDailyEvents` | 0 | **假设**日事件量；0 = 未提供 → 所有增长估算 UNKNOWN（不是 0） |
| `videoStatusCapacityAssumedRetentionDays` | 0 | **假设**保留天数；0 = 未提供假设 → key 数量/保留占用 UNKNOWN。工具**不会**自动套用已批准的 90/275 天政策（政策不是测量输入） |
| `videoStatusCapacityScanIterations` | 20 | **SCAN 命令次数**硬上限（1..1000）：一次调用 = 一次 `SCAN` 往返，达到即停 |
| `videoStatusCapacityScanCount` | 200 | 单次 SCAN 的 `COUNT` **hint**（1..1000）：服务器可以返回更多或更少的 key，也可能 0 个；不是硬返回上限，也不与轮数相乘得出“访问预算” |
| `videoStatusCapacitySampleKeys` | 100 | `MEMORY USAGE` 采样次数的**独立**硬上限（1..1000）；与命令次数互不推导 |
| `videoStatusCapacityProcessedKeyPattern` | `video:status:process:v1:*` | SCAN 的 `MATCH` 模式，不允许裸 `*` |

## 4. 报告读法（6 节）

1. **表与索引体积（估算）**：`information_schema.tables` 的 `TABLE_ROWS` / `DATA_LENGTH` /
   `INDEX_LENGTH`，覆盖 `video_status_outbox`、`video_status_consumed_event`、
   `video_status_consumption_repair_log`、`video_status`。
2. **终态候选 dry-run**：每个窗口打印 `EVALUABLE` / `NOT_EVALUABLE`、使用的索引、`EXPLAIN`
   访问路径（`type` / `rows` 是估算），候选只给 **id + 时间 + 状态**。
3. **Redis 只读容量**：`used_memory` / `used_memory_peak` / `DBSIZE` / `maxmemory` /
   `maxmemory-policy`，以及 `processed` key 的有界抽样单键字节（min/max/avg）。抽样行会分开打印
   **SCAN 命令次数 / 命令上限**、**SCAN 返回 key 数**、**命中样本数 / 采样上限**、**游标是否归零**；
   `COUNT` 在文字里被明确标成 hint。游标未归零时显示“覆盖截断”，并在“未确定项”里再列一条。
4. **容量公式与估算**：公式原文 + 每条估算的区间与状态。
5. **未做 / 边界**：本工具不清理、终态也不授权删除、未来清理的前提。
6. **未确定项**：所有 UNKNOWN 的原因逐条列出。

## 5. 口径：什么能测、什么只是估算/假设

| 数据 | 来源 | 性质 |
|---|---|---|
| 表行数 / 数据字节 / 索引字节 | `information_schema.tables` | **估算**（InnoDB `TABLE_ROWS` 可偏差 40%–50%）。工具**不执行 `COUNT(*)`**，不扫表 |
| 终态候选 | `FORCE INDEX` + 时间窗 + `LIMIT` 的有界查询 | 精确到窗口内、上限内的行；`truncated=是` 表示还有更多但**不继续读** |
| `processed` 单键字节 | 单页 `SCAN` 游标循环（命令次数上限）+ `MEMORY USAGE` | 抽样实测；样本 < 5 个即 UNKNOWN，不猜、不补 0；样本够但游标未归零时给字节数并标“覆盖截断” |
| Redis 内存 | `INFO memory`（`memory` 段） | 实测，只读 |
| `maxmemory` / policy | `CONFIG GET maxmemory*` | 实测，只读；读失败记 UNKNOWN，不改配置 |
| 日事件量、保留天数 | `-DvideoStatusCapacity*` | **配置假设，不是测量**，报告里逐行标注 |

### 5.1 容量公式

```text
DB 日增字节        ≈ 假设日事件量 × (outbox 平均行+索引字节 + consumed 平均行+索引字节)
Redis 日增字节     ≈ 假设日事件量 × processed 单键实测字节（样本 min～max）
processed key 数量 ≈ 假设日事件量 × 假设保留天数
Redis 保留占用     ≈ Redis 日增字节 × 假设保留天数
Redis 预计耗尽天数 ≈ (maxmemory - used_memory) ÷ Redis 日增字节
```

- DB 日增给**区间**：点估计 × `0.5` ～ 点估计 × `1.5`，理由写进报告（`information_schema`
  估算误差 + payload 长度分布未知）。这是配置假设，不是实测增长。
- `maxmemory=0` 表示未设置上限 → 耗尽天数 **UNKNOWN**，不是 0 天。
- `processed` 样本不足 / 日事件量未提供 / 未提供假设保留天数 → 对应估算 **UNKNOWN**，不是 0
  （已批准政策不会自动代入这两个配置参数）。
- 耗尽天数只折算 `processed` key 增长，**未含** `current` / `dirty` / `hot` 与内存碎片。

### 5.2 有界 SCAN 的确切口径（父审修复）

**历史缺陷（已修）**：旧实现用 Spring Data 的
`connection.keyCommands().scan(ScanOptions…match(...).count(...))` 拿到 `Cursor<byte[]>`，然后
`while (cursor.hasNext())` 取 key，并把 `visited` 记成“取到的 key 数”。问题是：

- `Cursor#hasNext()` 在**当前页匹配为空**（`MATCH` 过滤掉整页）时会**自己继续发 SCAN**，直到
  拿到 key 或游标归零。也就是说 `scanMaxIterations` 只限制“取到 key 的轮数”，真正的命令次数可能
  一直涨到把整个 keyspace 走完 —— 对“SQL 式有界”的承诺是不成立的。
- `COUNT` 只是 hint，不是单页硬返回上限，所以“轮数 × COUNT = 访问 key 预算”这个等式也不成立
  （旧的 `Options#keyVisitBudget()` 因此删掉）。
- 旧报告里的 `SCAN 完整遍历=是/否` 也不可靠：空匹配页被当成“还没完成”，很容易被误读成“已经遍历完”。

**现在的实现**（`VideoStatusCapacityAudit#collectScanRun`）：

1. 一次 `reader.scan(cursor)` = **一条** `SCAN <cursor> MATCH … COUNT …`，用 Lettuce 的单页游标 API
   （`RedisKeyAsyncCommands#scan(ScanCursor, ScanArgs)`，从 `LettuceConnection` 取出；回复等待上限 5 s）。
   **没有** `cursor.hasNext()`，也**没有** `while` 到游标归零的无界循环。
2. 每发一条命令就 `commands++`；`commands == videoStatusCapacityScanIterations` 时立刻返回，
   不再多发一条，也不因为“本页匹配为空”继续追。
3. `COUNT` 原样作为 hint 下发；单页返回的 key 全部计入 `returnedKeys`（可能 > `COUNT`，也可能 0）。
4. `MEMORY USAGE` 采样有**独立**硬上限 `videoStatusCapacitySampleKeys`；达到上限即停，并把
   `scanComplete=false`（游标还没归零）如实写进报告。
5. 只有 `SCAN` 返回的游标字面量是 `"0"` 时 `scanComplete=true`；**空匹配页不算完成**；
   空/缺失游标直接判失败（UNKNOWN），不谎称遍历完成。
6. `SCAN` 读失败立刻停（不再重试、不继续追游标），`MEMORY USAGE` 无返回只计入“无结果个数”，
   两者都不会被补成 0。

**报告字段**：`ProcessedKeySample(sampledKeys, returnedKeys, budget{scanCommands, scanCommandLimit,
sampleKeyLimit, countHint}, scanComplete, min/max/avg, status, note)`。游标未归零时
`scanComplete=false`，`note` 与“未确定项”都会写明“覆盖截断、只覆盖已扫描部分、不补充扫描、
不推断 processed key 总数”。

**证据（命令次数，而不是 key 数）**：

- 纯单测 `VideoStatusCapacityAuditScanTests`（10 例，无基础设施）：多页均空且游标非 0 时严格
  N 次停（`scanCommands == scanCommandLimit`，读取器调用次数 == N，游标非 0 时不额外发一条）；
  零匹配 + 游标 `"0"` 算完成（2 页 = 2 次命令）；少量匹配按页序采样 min/max/avg；单页 25 个 key
  而 `COUNT=5` 时 `returnedKeys=25`、采样仍受 10 上限；采样上限先到而游标非 0 → 截断而非完成；
  第 2 页读失败 → 2 次命令后停、UNKNOWN；空游标/空页面 → 立刻停并 UNKNOWN；
  `MEMORY USAGE` 全部失败 → 记 6 个“无结果”而不是 0 字节。
- 隔离用例 `scanCommandBudgetIsEnforcedAgainstRealRedisAndMatchesCommandStats`：用不匹配任何 key 的
  模式 + `iterations=3, COUNT=1` 跑真实 Redis 16379，断言记录的 `scanCommands == 3`、真实
  `INFO commandstats` 的 `cmdstat_scan` 调用增量 **正好 +3**、`scanComplete=false`、`returnedKeys=0`。
- 默认参数的真实运行（隔离库）现在写成：“SCAN 命令 1/20 次（COUNT=200 只是 hint），返回 key 177 个，
  命中样本 100/100 个，游标归零=否（覆盖截断…）” —— 旧实现不会产生这种诚实的截断描述。

## 6. 禁删判定（本工具最重要的结论）

- 只有 **终态**进入候选：
  - `video_status_outbox.status = 2`（`SENT`）；
  - `video_status_consumed_event.process_status = 2`（`FLUSHED`）。
- `status0(RECEIVED)` / `1(REDIS_APPLIED_PENDING_FLUSH)` / `3(REPAIR_REQUIRED)` 与
  Outbox `FAILED(3)` **永不**列为可删除候选；`VideoStatusCapacityAudit.Window` 的构造器
  直接拒绝 `terminalStatus != 2`，SQL 也只按终态等值过滤，出现其它状态会整体判 `NOT_EVALUABLE`。
- **即使是终态，也一律标 `NOT_AUTHORIZED_FOR_DELETION`**：政策已批准（§9.1）、起算点已确认（§9.1.1），
  但归档/删除执行器、归档成功校验与去重墓碑都还没实现，30 天守卫也只有消费登记侧一个候选切片（§9.3.1）；`processed` 的 TTL
  同样不能作为删除 DB 幂等证据的依据。报告里仍会打印 `RETENTION_PERIOD_UNDECIDED`（工具代码本轮未改），
  它的含义是“清理执行与安全证明尚未实现”，**不是**“政策仍未决策”。
- **终态时间缺失的行永远不是候选**：90 天按完成发送/落库时间起算（§9.1.1），而 `sent_at` / `flushed_at`
  都是可空列。历史行或异常行没有这两个值时**不得**猜成 `created_at` / `consumed_at`，也**不得**回填伪时间；
  它们只能保守地排除在自动删除之外（核查结果见 §9.2）。
- 报告层面 `writePerformed=false`、`deletionAuthorized=false` 固定输出。

## 7. 索引依赖：缺索引就“不可评估”，绝不降级成全表扫描

dry-run 使用 T05 迁移提供的两个索引：

- `idx_video_status_outbox_created (status, created_at, id)`
- `idx_video_status_consumed_created (process_status, consumed_at, id)`

流程是：先按 `information_schema.statistics` 校验索引**列序完全匹配**，再用 `FORCE INDEX` 的
`EXPLAIN` 校验访问路径（`key` 必须是该索引、`type` 不能是 `ALL`），两条都通过才真正查询。
任一不满足 → 该窗口 `NOT_EVALUABLE` + `INDEX_MISSING` / `ACCESS_PATH_NOT_INDEXED`，候选为空，
**不执行**查询、不退回全表扫描。两个索引已镜像在 `database/feibijiubi.sql`，本切片**不新增迁移**；
若某环境缺索引，报告会明确写出来，需要的是先执行 `database/migrations/t05-video-status-observability-indexes.sql`，
而不是让 dry-run 去扫全表。

**与批准口径的索引缺口**：这两个索引是 `(status, created_at, id)` / `(process_status, consumed_at, id)`，
服务的是“创建/接收时间”窗口；§9.1.1 确认的 90 天要按 `sent_at` / `flushed_at` 选行，而这两列上**没有任何索引**
（现有 `(status, id)`、`(process_status, id)`、`(vid, id)`、`(process_status, last_attempt_at, id)` 都不匹配）。
也就是说：按批准口径做选择必须**新增迁移**（`(status, sent_at, id)` 与 `(process_status, flushed_at, id)`），
本轮不建；在此之前任何“按 90 天选行”的实现都会退回全表扫描或误用 created/consumed 时间。

## 8. noeviction 内存告警与安全恢复

隔离与生产 Redis 都按 `noeviction` 运行（`compose.test.yaml` 显式设置）。这意味着**达到 `maxmemory`
时写命令直接报错**，Redis 不会淘汰 key，而这条链路上的写包括：重建门闩 `SET`、`processed` `SET`、
`current` `HSET`、`dirty` `SADD`、热榜 `ZADD`、Outbox 状态回写（MySQL）。

处置顺序（写进报告原文）：

1. 扩容内存，或降低写入速率；
2. 停止输入并排空积压，让消费/刷库把待处理事件收敛；
3. 观察重建门闩与 `processed` 证据是否完整，再恢复流量。

**禁止**的“腾空间”做法：

- 删除重建门闩 `video:status:v1:rebuild-gate:<vid>`：门闩**没有 TTL**，靠 token 所有权与 vid 锁
  协调重建；删掉它等于绕过 token 校验，可能让两个重建同时写 `current`；
- 删除 `processed` key：那是 Redis 侧幂等证据，删了会让重投的旧消息再计一次；
- `FLUSHALL`/`FLUSHDB`；
- 改 `maxmemory-policy`（例如切 `allkeys-lru`）来“自动腾空间”：会静默丢幂等证据与门闩。

## 9. 保留期政策与未来清理的前提（本工具不提供清理入口）

### 9.1 已批准的保留期与重放窗口政策（2026-10-23，用户批准）

- **成功终态在线保留 90 天**：只覆盖 `video_status_outbox.status = 2`（SENT）与
  `video_status_consumed_event.process_status = 2`（FLUSHED）两类行。
- **归档副本再保留 275 天**：在线 90 天结束后，归档副本再保留 275 天。注意这两个期限的**起算点不同**
  （§9.1.1），所以“合计恰好 365 天”只在线归档不延迟、且两边起算点重合时才成立；归档延后时实际总保留更长。
- **DLQ 30 天内必须处置**：这是处理 SLA，**不是保留期/TTL**，不配置自动过期；未解决的一律不自动删除。
- **操作审计保留 365 天**（`video_status_consumption_repair_log` 等审计行）。
- **最大普通事件重放窗口 = 事件发生后 30 天**；超过窗口转人工核查，**拒绝普通重放**。
- **30 天以内不放宽任何守卫**：仍必须满足全部现有 age、`processed` TTL 与证据约束，T07/T09/T10 的
  拒绝条件一条都不减；窗口只是新增的“超期即拒”上界。
- **不包含 T15**：`video_interaction_request` / `video_interaction_window` 不在本政策范围内；它们的保留期
  已由 T15 自己的 30 天清理切片单独决定（request 自 `completed_at` 起 30 天、window 连续 30 天无计数，
  默认关闭、不是 TTL）。旧的“同一 `requestKey` 永不重复计数”已被 T15 决策取代为“记录还在即重放，
  记录被清理后同一 `requestKey` 按新请求处理、仍受 30 分钟窗口约束”，见
  `docs/tutorial/video-interaction-idempotency.md` §12。

### 9.1.1 起算点与归档存储（本轮用户确认；无新日历日期）

本轮用户确认了下列具体口径，**仍然只是决策，没有任何实现**：

| 期限 | 起算点（本轮确认） | 明确不是 |
|---|---|---|
| 在线 90 天 | 事件**完成发送**（outbox `sent_at`，发布 confirm 成功后回写）或**完成刷库**（consumed `flushed_at`，批量落库事务内写入）时起算 | **不是**事件创建时间（`outbox.created_at`），也**不是**消费登记时间（`consumed.consumed_at`） |
| 归档副本 275 天 | **归档副本校验成功**时起算（文件完整性 + 记录数量都校验通过的那一刻） | **不是**事件创建时间，也**不是**“在线 90 天结束时”自动顺延；归档延后则实际总保留 > 365 天 |
| 审计 365 天 | 审计行**创建**时起算（`video_status_consumption_repair_log.created_at`，DB `NOW(3)`） | 不是业务操作发生时间，也不是 `completed_at` |
| DLQ 30 天处置 SLA | 首次**进入死信队列**时起算；**重投不重置**，未解决**不自动删除** | 不是 TTL、不是保留期、不是最后一次重投时间 |
| 普通重放 30 天（既有批准） | 事件发生后 30 天为上限，超过转人工核查 | 不是放宽任何现有守卫；窗口内仍走全部 T07/T09/T10 校验 |

归档存储（本轮确认）：**独立的私有 COS 归档桶**；只有**文件完整性 + 记录数量校验通过**才允许删除在线数据；
**开发阶段只允许在隔离测试存储上演练**，不接生产桶、不接生产凭证。

仍未实现：没有归档执行器、没有删除代码、没有全入口 30 天重放守卫（仅消费登记侧候选切片，§9.3.1）、没有归档校验记录，dry-run 仍输出
`NOT_AUTHORIZED_FOR_DELETION`。

### 9.2 字段与接口核查：真实语义 vs 缺口（本轮只做源码/schema 阅读）

核查范围：`database/feibijiubi.sql`、`database/migrations/`、mapper XML/接口、相关 service，以及 COS 适配层。
**不读 `application.yml`、不连任何环境**。

| 现有字段 / 对象 | 真实语义（代码证据） | 相对本轮确认口径的缺口 |
|---|---|---|
| `video_status_outbox.sent_at DATETIME(3) NULL` | 唯一写点是 `VideoStatusOutboxMapper#markSent`（`status 1→2`），值由 `VideoStatusOutboxRelay#handleConfirmed` 传入 `LocalDateTime.now()`，即**发布 confirm 成功后回写时的 JVM 时钟** | 语义就是“完成发送”，但① 是 **JVM 时钟**，不是 DB `NOW(3)`，多实例/时钟漂移会让 90 天水位与 DB 截止时间不同源；② 可空，缺失时**不得**用 `created_at` 代替；③ 无 `(status, sent_at, id)` 索引 |
| `video_status_outbox.created_at` | 事件创建时间（`insertOutbox` 显式写 DB `NOW(3)`；列默认值也是 `CURRENT_TIMESTAMP(3)`） | **不能**作为 90 天起算点；dry-run 当前仍用它 |
| `video_status_consumed_event.flushed_at DATETIME(3) NULL` | `markFlushed`（`process_status 1→2`，批量落库事务内 `NOW(3)`）与 `markCommitted`（`0→2`，当前**无 Java 调用方**）；`resetRepairToReceived`（T10-B1 重放 `3→0`）会把它**清成 NULL** | 语义就是“落库完成”（DB 时钟），但① 重放后清空 → 它只表示**最近一次**落库完成，不是首次；② 可空，缺失时**不得**用 `consumed_at` 代替；③ 无 `(process_status, flushed_at, id)` 索引 |
| `video_status_consumed_event.consumed_at` | `insertReceived` 用 **`NOW(3)`**（DB 时钟）写入；T10-B1 重放明确**不刷新**它 | 是“登记接收时间”，不是落库完成时间；**不能**作为 90 天起算点；dry-run 当前仍用它 |
| `video_status_consumed_event.redis_applied_at` | `markRedisApplied`（`0→1`，`NOW(3)`） | 是 Redis 应用时间，不是终态完成时间 |
| `video_status_consumption_repair_log.created_at` / `completed_at` | 现有唯一写点 `insertCompletedRepairLog` 两列都写 `NOW(3)`（DB 时钟），即审计行创建时已是 COMPLETED | 满足“365 天从审计行创建起算”，**无字段缺口**；当前没有 STARTED 审计行写入路径，故两列实际相等 |
| 现有年龄守卫（`consumer-recovery-auto-replay-max-age-seconds`，默认 604800 s = 7 天） | 只出现在**重放类入口**与**重复/重投路径** `VideoStatusConsumptionServiceImpl#handleReceived`（且仅当账本行仍为 `RECEIVED`）；比较基准是 `consumed_at`（**登记接收时间**，不是事件发生时间），超龄转 `process_status=3` | ① 首次登记/首次应用**没有**年龄守卫；② 守卫**依赖消费账本行存在**，账本行归档/删除后重投的同一条旧消息会被当作新事件应用；③ 与批准口径“事件发生后 30 天”不同源 |
| DLQ `video.status.dlq.v1` | 只是 RabbitMQ durable 队列（`VideoStatusRabbitConfig#videoStatusDeadQueue`），**无消费者、无 DB 表**；`VideoStatusMessageForwarder` 只投递、不记时间 | **完全缺**“首次进入死信队列时间”的应用可见字段：broker 侧死信元数据（如 `x-death`）我们的代码既不读也不落库，30 天 SLA 起算点当前**不可计算**，“重投不重置”也无法判定 |
| 归档副本（COS） | 不存在：无归档桶/前缀配置、无对象清单、无“归档成功”记录 | 缺校验时间戳、对象 key、字节数、内容哈希、记录条数与校验结果；因此“275 天从校验成功起算”当前无载体 |

COS 适配层（`FileStorageService` / `TencentCosStorageServiceImpl`）是否适合归档：

| 现有接口 | 真实语义 | 缺口 |
|---|---|---|
| `objectExists(objectKey)` | 单桶存在性布尔；COS 异常转 `BusinessException(500)` | 读不到 size/ETag/哈希；错误类型是 Web 层异常，不适合批处理重试分类 |
| `copyObject(sourceKey, targetKey)` | **同桶**复制（`properties.getBucket()` 两侧都写死） | 不能跨到独立私有归档桶 |
| `deleteObject(objectKey)` | 单 key，无返回值，不校验是否真的删除 | 不能作为“删除成功”证据 |
| `uploadImage` / `uploadPrepare` | 图片上传（`MultipartFile`）/ STS 直传视频与封面，桶来自 `TencentCosProperties.bucket` | 不能写任意 JSON/manifest 归档对象；无指向归档桶的 STS |
| `COSClient` 单例 | 构造自 `TencentCosProperties`（secretId/secretKey/region/bucket） | SDK 方法本身带 bucket 参数，技术上同一客户端可访问第二个桶（需凭据授权），但**当前抽象把桶写死**；归档切片需要新的窄接口与独立配置，**不能**直接复用 `FileStorageService` |

结论：**字段层面 90 天的两块时间戳都存在且语义正确，但缺索引、且 `sent_at` 是 JVM 时钟**；审计期限无字段缺口；
**DLQ 首次入队时间与归档校验记录完全不存在**。因此“没有可信终态时间的历史行”只能保守排除，不能猜成
`created_at` / `consumed_at`，也不能回填伪时间——这一点是本轮核查最重要的结论。

### 9.3 下一步最小可测试实现切片建议（建议、待父审，本轮不实现）

> **2026-10-24 更新（T14-S2 候选）**：S1 的剩余三个入口（T07 Outbox 重放、T10-B1 status3 重放、T09 status0 补偿）
> 已由父代理指定的 **T14-S2** 切片接入同一固定上界（§9.3.2），因此 S1 的“四个入口共用同一判定”已经落地；
> **relay 与自动重放/恢复路径仍不拒绝**，所以“全入口拒绝旧消息”仍**不得**宣称（未升级的旧消费者也会放行）。
> **命名提示**：下面作为“建议”的 **S2 = 归档时间字段 + 校验记录** 与本轮交付的 **T14-S2（30 天守卫接入）**
> **不是**同一件事；归档侧 S2 仍未实现。
>
> **2026-10-24 更新（T14-S1 候选）**：S1 已落地**第一个切片**——消费登记前的 30 天绝对年龄拒绝
> （`VideoStatusEventAgeGuard` + `VideoStatusConsumptionServiceImpl#register`）。
> 同时**纠正**本节旧建议第 4 条：30 天窗口**不需要**“严格小于 processed TTL”——它是纯事件年龄上限，从不读
> `processed` 证据，与 `redisEventTtlDays` 无先后约束；只有真正依赖 `processed` 的重放/补偿窗口（7 天自动重放、
> T09）保留“严格小于 TTL”的启动校验。下面的原文保留不改，作为历史记录。

以下两条都是**建议**，需要父代理裁定后才实施；本轮不改代码、不建迁移。

**S1：30 天旧消息拒绝的入口全覆盖（先做守卫，不碰归档/删除）**

1. 把“是否在普通重放窗口内”做成**一个**只读判定（纯函数或单组件），由四个现有重发入口共同调用：
   - 消费应用路径（`VideoStatusEventConsumer` → `VideoStatusConsumptionServiceImpl`）：**现有唯一的年龄守卫在重复/重投路径**
     （`handleDuplicate` → `handleReceived`：拿 `consumed_at`（登记时间，不是事件发生时间）与
     `now - consumer-recovery-auto-replay-max-age-seconds` 比较，超龄转 `process_status=3`）；
     **首次登记/首次应用没有任何年龄守卫**，而且这个守卫**依赖消费账本行存在**——一旦终态行被归档/删除，
     重投的同一条旧消息会被当成全新事件直接应用。这正是 30 天守卫必须**独立于账本行存在性**（按 payload
     `occurredAt`/事件时间或去重墓碑）的理由；
   - T07 Outbox 单事件重放（`VideoStatusOutboxReplayService`，现用 `consumer-recovery-auto-replay-max-age-seconds` + `occurredAt` 双窗口）；
   - T10-B1 consumed status3 重放（`VideoStatusStatus3ReplayService`，同一套窗口）；
   - T09 status0 补偿重发（`VideoStatusStatus0CompensationServiceImpl` / `VideoStatusStatus0CompensationClaimService`，同一套窗口）。
2. **Relay 本身不新增拒绝**：它只投递被重置为 PENDING 的行；若在 relay 拒绝，重放路径会静默丢消息。
3. 可测证据：纯单测矩阵（窗口内 / 恰好 30 天 / 超过 30 天 / 缺 `occurredAt` / 未来时间 / 终态时间缺失）+ 一个 opt-in
   隔离用例（同一个旧 eventId 经四个入口全部被拒，且**零写入**：无 outbox 状态变更、无 consumed 变更、无审计行）。
4. **待父确认的边界（2026-10-24 已裁定，原建议不成立）**：默认 `redisEventTtlDays = 30` 与 30 天窗口同量级，且启动校验要求重放窗口**严格小于**
   `processed` TTL。所以 30 天必须以“严格小于 TTL”的形式落地（或先调大 TTL），边界事件 fail-closed；
   这是产品/配置决策，不在本轮擅自设定。
   **父决策（T14-S1）**：该条**不成立**。30 天守卫是“事件发生时间”的绝对上限，只读 payload `occurredAt`、
   不读 `processed` 幂等证据，因此**不存在**与 `redisEventTtlDays` 的先后约束；它也不放宽任何既有守卫
   （窗口内仍走全部 age / `processed` / T07/T09/T10 校验）。边界取显式数学定义：严格
   `occurredAt.isBefore(now - 30 天)` 才拒，**恰好 30 天允许**，纳秒不截断。

**S2：归档时间字段与校验记录（先做“可证明”，不做执行器）**

1. 下一轮才建的迁移：`(status, sent_at, id)` 与 `(process_status, flushed_at, id)` 索引；以及归档记录表
   （`event_id`、来源表、终态时间、归档对象 key、字节数、内容哈希、记录条数、校验时间、校验结果）。
2. 选择条件：仅终态 + 终态时间**非空** + 未归档 + 终态时间早于“DB `NOW(3)` − 90 天”；终态时间为 NULL 的行
   永久 `NOT_ELIGIBLE`（不回填、不猜）。
3. 校验口径：只有“对象可读 + 字节数/哈希匹配 + manifest 记录数与对象内记录数一致”三者同时成立，才写
   “校验成功时间”；否则永不进入删除候选。
4. 可测证据：纯单测（选择条件、NULL 排除、90 天边界、dry-run 报告标注）+ 一个隔离用例（构造对象/manifest
   不匹配 → 校验失败 → 零 DB 写、仍 `NOT_AUTHORIZED_FOR_DELETION`）。
5. 明确不做：删除执行器、限速分页、断点续跑、生产桶、`sent_at` 写时钟改造（把 `markSent` 改成 DB `NOW(3)`
   属于改现有写路径，需要单独批准）。

另注：T14-A dry-run 工具本轮**未改**，时间列仍是 `created_at` / `consumed_at`，因此它**不是**批准口径的实现；
要么下一轮改工具，要么由 S2 另建只读选择器，二者都需要父裁定。

### 9.3.1 T14-S1 已落地切片：消费登记前的 30 天绝对年龄拒绝（候选，2026-10-24）

**范围：一个入口、一个判定、一个常量。** 不是 S1 的全入口覆盖，也**不是**归档授权。

| 项 | 内容 |
|---|---|
| 判定 | `VideoStatusEventAgeGuard#isOutsideNormalReplayWindow(occurredAt, now)`：严格 `occurredAt.isBefore(now.minusDays(30))` 才拒，**恰好 30 天允许**，纳秒不截断 |
| 常量 | `MAX_NORMAL_REPLAY_AGE_DAYS = 30` 固定；**不新增配置项**、不放宽开关，不改 `VideoStatusProperties`（`redisEventTtlDays` 与 7 天自动重放窗口的启动校验均不动），不改 `application.yml` |
| 调用点 | `VideoStatusConsumptionServiceImpl#register`：`event.validate()` + `validatePayloadHash(...)` **之后**、任何 mapper/序列化**之前**（含唯一键冲突后的账本查询） |
| 拒绝方式 | `NonRetryableMessageException`（固定安全原因常量，不含 payload / 完整 eventId）→ 消费者既有 `NonRetryable` → **死信转发 + ACK** 边界；**不是静默 ACK**，也不进 retry 队列 |
| 时钟 | 组件用 `Clock.systemDefaultZone()`（与业务侧 `LocalDateTime.now()` 的无时区语义一致），另有可注入 `Clock` 的测试构造；`LocalDateTime` 语义不变 |
| 已存在账本行 | 因为守卫在任何 mapper 交互之前，**status1/2 的重复事件同样被拒**，不再走 `handleDuplicate` 静默返回 `REDIS_ALREADY_APPLIED` / `ALREADY_FLUSHED`；`handleReceived` **未新增调用** |
| 既有 7 天守卫 | 不改。默认 7 天（`consumer-recovery-auto-replay-max-age-seconds`，可配置，上限受 processed TTL 约束，**不能假定永远 7 天**）比 30 天更严；30 天以内行为完全不变，超过 30 天时新守卫先生效，既不读也不改账本行 |
| 未来时间 | **不新增拒绝**，保持既有行为。payload 时间戳无签名，因此**这不是防篡改机制**；缺 `occurredAt` 仍由既有 `validate()` 拒绝；payload / 指纹语义不变 |

**写边界（不夸大）**：`register` 内部不产生 MySQL/Redis 写（`REQUIRES_NEW` 事务空回滚），但**全流程并非零写**：
消费者会把消息转发到死信队列（broker 写），遥测会记一次失败。因此不得把本切片描述成“拒绝时零写入”。

**部署与归档前提**：没有本守卫的旧消费者仍会应用旧事件，所以归档安全要求**全体消费者同版本部署**；
混版本后删归档终态行或回滚到旧消费者都会重新放行旧事件。本守卫本身**不构成**删除授权，dry-run 仍 `NOT_AUTHORIZED_FOR_DELETION`。

**未覆盖的入口（S1 剩余，已由 T14-S2 接入）**：T07 Outbox 单事件重放、T10-B1 consumed status3 重放、
T09 status0 补偿重发在 S1 当时**未**调用该判定（见下 §9.3.2 的 S2 切片）；管理侧现有更严格的 7 天双窗口守卫
仍保留并在默认配置下先生效。因此即便在 S2 之后，“所有入口都拒绝旧消息”也**不能**声称——未升级的旧消费者仍会放行。

**S1 审核备注（2026-10-24 复核，已在本轮修正）**：

1. **缺 `occurredAt` 的入口拒绝路径现在有用例钉住**：`register` 先调 `event.validate()`，`occurredAt == null` 抛
   `IllegalArgumentException`（非 `NonRetryableMessageException`），且**在任何 mapper 交互之前**；
   `VideoStatusConsumptionRegistrationTests#missingOccurredAtIsRejectedByEventValidationBeforeAnyMapperInteraction`
   断言异常类型与 `verifyNoInteractions(consumedEventMapper)`。此前这条只在源码里成立、没有测试。
2. **已清理无用 stub**：`staleEventWithExistingLedgerRowIsNeitherReadNorChanged` 原本为“账本行存在”打了两个
   `selectByEventId*` stub，但断言是零交互，stub 永不被调用；删除后断言强度不变（任何查询都会让
   `verifyNoInteractions` 失败）。
3. **修正一处时钟耦合**：`existingSevenDayReplayGuardIsUnchangedInsideThirtyDays` 原本用固定时钟派生的
   `consumed_at` 去喂 `handleReceived` 里活的 `LocalDateTime.now()`，机器日期靠前时会走错分支；
   现改为同一活时钟派生（意图“8 天 > 7 天”不变）。
4. **新记录的后果（原文档没写）**：首次消费前就积压超过 30 天的**合法**事件（例如 relay 关闭/租约长时间
   停滞导致事件晚投递）会在首次消费时被直接转死信：它不产生 `video_status_consumed_event` 账本行、
   不自动补算（落库只看 status 1，恢复只重加 status 1 的 dirty vid），唯一痕迹是 durable 死信队列
   （无消费者、无首次入队时间）与一次遥测失败。注意 **relay 的确认状态与消费是独立的**：relay 可能已把行
   标成 `SENT`、也可能仍停在 PENDING/SENDING/FAILED，不能断言“一定已 SENT”；无论哪种，都不存在
   “按 30 天补算”的机制。
5. **原“把窗口调到 30 天以上会让管理入口 accept 然后消费端 DLQ”的缺口已由 S2 关闭**（§9.3.2）：
   三个管理入口现在都会在写之前用同一个固定上界拒绝。

**可测证据**：`VideoStatusEventAgeGuardTests` 9 例（固定时钟：窗口内 / 恰好 30 天 / ±1 纳秒 / 超 1 秒 / 未来时间 / 默认时区墙钟一致性）；
`VideoStatusConsumptionRegistrationTests` 7 例（超龄零 mapper 交互、已有 status1 行既不查也不改、FLUSHED 行不被静默吸收、
恰好 30 天与窗口内登记不变、既有 7 天 `markRepairRequired` 路径在 30 天内不变、缺 `occurredAt` 由 `validate()` 拒绝且零 mapper 交互）；
`VideoStatusEventConsumerAckBoundaryTests` 新增 1 例证明新异常走 DLQ + ACK 一次（非静默 ACK、无 retry 转发、不写消费失败记录）。
定向 `50 tests, 0 failures/errors`（`/tmp/t14-s1-target-unit2.log`）；普通回归 **684 tests, 0 failures/errors, 201 skipped**
（`/tmp/t14-s1-unit-full.log`）。**未开任何隔离栈、未跑 IT/scale、未动容器**；无 schema/迁移/COS/删除/新 HTTP。候选、未父验收，T14 不勾选。

### 9.3.2 T14-S2 已落地切片：三个管理入口接入同一固定 30 天上界（候选，2026-10-24）

**范围：一个已有判定、四个调用点、一个新原因码；无新增配置/路由/schema。** 不是 S1 的“全入口覆盖”
（relay 与自动重放/恢复路径仍然不拒绝），也**不是**归档授权。

| 入口 | 接入位置 | 拒绝形态 | 读侧同源 |
|---|---|---|---|
| 消费登记（T14-S1） | `VideoStatusConsumptionServiceImpl#register` | `NonRetryableMessageException` → 死信 + ACK | — |
| T07 Outbox 单事件重放 | `VideoStatusOutboxReplayService#replay` 第 5b 步（createdAt / occurredAt 双窗口之后、消费账本查询与 `resetFailedToPending` 之前） | `BusinessException(409)` | `GET .../outbox/{eventId}` 的 `note` 列明该上界（GET 本身仍只做静态必要条件判断） |
| T10-B1 status3 重放 | `VideoStatusStatus3ReplayService#evaluate`（在 occurredAt 窗口之后、预算比较/`processed` 证据与任何写之前；有界 `countReplayAttempts` 查询作为调用实参在 `evaluate` 之前已执行） | `BusinessException(409)` | 同一个 `evaluate` → `precheck.replay.refusalCode` |
| T09 status0 补偿 | `VideoStatusStatus0CompensationServiceImpl#evaluate`（preview 与 `compensateOne` 共用） | `release(..., refusalCode)` 写冷却诊断，不发布、不改计数 | `preview.items[].refusalCode` + `withinSafeWindow` |

**原因码**：`VideoStatusEventAgeGuard.REFUSAL_CODE = EVENT_OVER_NORMAL_REPLAY_WINDOW`（读写两侧同一字符串）。
**刻意不复用** `CONSUMED_OVER_SAFE_WINDOW` / `STATUS0_OVER_SAFE_WINDOW`：那两个码描述的是**可配置**窗口
（启动校验只要求小于 `redisEventTtlDays`，所以允许被调到 30 天以上），而这里是**固定政策上界**；
混用会让运维无法区分两者（处置完全不同），而且原码的说明文本会变成不成立的说法。

**优先级不变**：新检查排在每个入口原有的“可配置窗口”判定**之后**，默认 7 天更严，所以默认配置下的超龄事件
仍然返回原来的原因（`CONSUMED_OVER_SAFE_WINDOW` / `STATUS0_OVER_SAFE_WINDOW` / T07 的 occurredAt 窗口说明）。
新码只在运维把 `consumer-recovery-auto-replay-max-age-seconds` 调到 30 天以上时兜底。

**恰好 30 天**：共享 predicate 不拒（严格 `isBefore`），但原更严格的 7 天窗口 / `processed` TTL / 年龄 /
未来 300s 容忍 / 指纹条件仍可拒绝。**精确边界不在这里重复验证**：入口级用例只用“明显在内（20/29 天）”
与“明显在外（31 天）”，纳秒级边界由 `VideoStatusEventAgeGuardTests` 的固定时钟用例负责——入口用例用真实
时钟，断言“恰好 30 天”会因毫秒级流逝变成 flaky。

**时钟口径**：每个服务保持自己原有的取时方式（T07/T10/T09 写侧都是 `LocalDateTime.now()`），
T09 preview 整轮取一次 JVM `now` 并与 `runOnce` 共用同一 `evaluate`；租约/冷却/静默期仍用 SQL 返回的 DB
`NOW(3)`，两者不混用。一个 `evaluate` 内不重复取时间。

**T09 写边界（不夸大）**：固定上界判定本身早于 Redis 证据检查与发布，但 T09 拒绝路径**不是零写**——
它会按原有协议释放租约并写冷却/诊断 `last_error`（`releaseStatus0Compensation`）。它**不**发布 MQ、
**不**改业务计数、**不**自动把合法事件隔离成 status3。

**T09 读侧收紧（同一次 evaluate 的必然结果）**：`preview.items[]` 新增 `refusalCode`；`withinSafeWindow`
从“只看 DB 年龄”改为“DB 年龄 + `occurredAt` 窗口”（与写侧一致），`eligible` 因此不会再在写侧会拒绝的行上
为 true；`selectStatus0CompensationPreview` 额外读取 `payload` 以解出 `occurredAt`（只读、仍有界）。
payload 解析失败、`validate()` 失败或与账本行不一致时，`evaluate` 返回 `withinSafeWindow=false`（`occurredAt`
无从证明）并给三个固定内容码之一（`STATUS0_PAYLOAD_INVALID` / `STATUS0_EVENT_INVALID` /
`STATUS0_EVENT_MISMATCH`），不回显原始异常文本，未识别消息回落 `STATUS0_EVENT_INVALID`。指纹不符
（`STATUS0_FINGERPRINT_MISMATCH`）不在 preview 判定内，它由 `runOnce` 在读取 Redis 证据前单独校验。
preview 仍**不**访问 Redis，所以 `eligible=true` 仍不代表写侧一定发送。

**不变的部分**：固定 30 常量与“恰好 30 天允许”边界不变；`VideoStatusProperties` 与原可调窗口、
`processed` TTL 交叉校验均未改；未新增配置项；relay 未改（不新增拒绝）；无 schema/迁移/新 HTTP 路由。

**可测证据**（纯 Mockito，串行；`-DvideoStatusIT=false -DvideoStatusScale=false`，
`MAVEN_OPTS='-Xms64m -Xmx256m'`，`argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'`）：
`VideoStatusOutboxReplayBoundaryTests` 8 → **11** 例（45 天窗口下 31 天拒绝且零写、同配置 29 天仍重放、
默认 7 天窗口下仍返回原 occurredAt 原因）；`VideoStatusStatus3ReplayServiceTests` 26 → **28** 例
（precheck 与 replay 同一 `EVENT_OVER_NORMAL_REPLAY_WINDOW`、零写、无 Redis 读；同配置 29 天仍可重放）；
`VideoStatusStatus0CompensationTests` 31 → **35** 例（31 天释放同一码、preview 与 runOnce 同码、无发布、
无 `processed` 读；同配置 20 天仍重发；默认 7 天仍 `STATUS0_OVER_SAFE_WINDOW`）；
`VideoStatusConsumptionRegistrationTests` 6 → **7** 例（缺 `occurredAt` 用例）。定向运行
**96 tests, 0 failures/errors, 0 skipped**（`/tmp/t14-s2-target-unit2.log`），相关类回归
**48 tests, 0 failures/errors**（`/tmp/t14-s2-related-unit.log`）。
**未开隔离栈、未跑 IT/scale、未动容器/DB/Redis/COS**；这些是测试环境候选证据，不是生产验收。

**审查修复轮（同一候选，2026-10-24）**：T09 `evaluate` 的 parse 失败分支原先返回
`new Status0Verdict(withinDbWindow, null, e.getMessage())`，会让“内容无法验证”的行带上
`withinSafeWindow=true` 并把原始异常文本透到 `preview.items[].refusalCode` 与 `release` 的 `last_error`。
现修为 `withinSafeWindow=false` + 白名单原因码（只允许 `parseAndMatch` 真实使用的三个固定内容码，其余回落
`STATUS0_EVENT_INVALID`，不回显。外层 `compensateOne` 的 `startsWith("STATUS0_")` 消毒器语义不同（还包含
证据/发布/释放类码），本次**未**改动、也未合并两个分支）。新增/收紧单测：malformed payload、`validate()`
失败、账本/payload 不一致三例均断言读侧 `withinSafeWindow=false` / `eligible=false` / 精确码与写侧同码
`release`、不发布、不读 Redis；指纹不符一例只覆盖写侧（`STATUS0_FINGERPRINT_MISMATCH`、不发布、不读
Redis）；`previewUsesDatabaseClockForGraceAndLeaseDecisions` 用真实合法 fixture（DB 年龄 100s、occurredAt
100s 前、attempts 1<5、无租约/冷却）补正向断言 `withinSafeWindow=true` / `eligible=true` / `refusalCode=null`。
`VideoStatusStatus0CompensationTests` 35 → **39** 例；定向运行 **94 tests, 0 failures/errors, 0 skipped**
（`/tmp/t14-s2-review-fix.log`）。仍然只改了这一窄切片（无 schema/迁移/路由/COS/删除），未开隔离栈，
不是生产或 IT 验收。

**仍然不做 / 不得声称**：没有归档与删除能力（dry-run 仍 `NOT_AUTHORIZED_FOR_DELETION`）；未升级的旧消费者
仍会放行旧事件（全兼容部署才能依赖未来归档）；relay 与自动恢复/重放路径不新增拒绝；未来时间戳仍不拒绝，
因此不是防篡改机制；超过 30 天才被投递的合法旧事件仍会直接进死信且无账本、不自动补算（§9.3.1 第 4 条）。

### 9.4 生产清理仍缺的实现前置

政策已批准、起算点与归档存储已确认（§9.1、§9.1.1），但本工具与仓库都**没有**任何清理/归档能力。开始清理前必须实现并验收：

1. **旧消息硬拒绝或去重墓碑**（例如保留 eventId 摘要/时间水位，对超过窗口的重投直接拒绝而不是
   重新计数）；否则删掉终态历史行就等于删掉去重证据。**当前有消费登记侧（T14-S1，§9.3.1）与三个管理入口
   （T14-S2，§9.3.2）的候选守卫**，但 relay/自动重放路径仍不拒绝，且旧消费者会继续放行，因此本条仍未满足；
2. **仅终态**：status0/1/3 与待处置 Outbox FAILED 永不进入归档/删除；
3. **安全归档确认**：归档副本可读、完整、可定位之后才允许删除在线行；
4. **限速、分页、断点续跑**的归档/清理执行器，以及“中断后重跑不重复/不遗漏”的演练；
5. **归档后重放旧消息不重复计数**的验收，以及 **30 天重放守卫**（超窗口拒绝、窗口内继续走现有
   age/`processed`/证据校验）的测试——守卫已有消费登记侧（§9.3.1）与三个管理入口（§9.3.2）的候选切片
   与纯单测，但隔离演练仍未做；
6. 索引与在线查询锁影响的验证。

在这些落地前，本工具的结论永远是“候选可见，但**不授权删除**”。

## 10. 本轮实测（测试环境，非生产验收；本轮文档改动未重跑）

> 下列数字是 T14-A 此前的既有证据，保留原文，**本轮（T14-S1）未重跑它们**；T14-S1 自己的证据见 §9.3.1。

- 普通回归（无基础设施）：`574 tests, 0 failures/errors, 180 skipped`
  （含本切片 11 + 2 + 10 个纯单测；9 个隔离用例因 `videoStatusIT` 未开启计入 skipped）。
- 隔离验收：`VideoStatusCapacityAuditIsolatedTests` **9 tests, 0 failures/errors/skipped**，
  真实 MySQL 13307 + Redis 16379（新增用例用 `cmdstat_scan` 增量证明 SCAN 命令次数 == 记录值）。
- 命令次数证据：`-Dtest=VideoStatusCapacityAuditScanTests` **10 tests**，全部通过；关键断言是
  “读取器被调用次数 == `scanCommands` == 命令上限”，即命令数是唯一硬上限。
- 生成的 dry-run 报告（临时路径，示例）：
  `C:\Users\<user>\AppData\Local\Temp\video-status-capacity-audit-<random>.txt`
- 该次隔离库样本（**只是诊断，不是容量结论**）：
  - `video_status_outbox` 估算 5384 行 / 3.65 MB；`video_status_consumed_event` 估算 29523 行 / 27.30 MB；
  - Redis `used_memory=9,950,240 B`、`DBSIZE=34765`、`maxmemory=0`、policy `noeviction`；
  - `processed` 抽样 100 个 key：单键 104～120 B（avg 104 B）；
  - 假设 100,000 事件/天：DB 日增 80.1～240.3 MB/天，Redis 日增 10.4～12.0 MB/天，
    30 天保留占用 312～360 MB；**Redis 耗尽天数 UNKNOWN**（`maxmemory=0`）。

## 11. 未做 / 剩余风险

- 保留期与重放窗口政策**已批准**，起算点与归档存储**已由本轮用户确认**（§9.1.1），但归档与删除
  **都没有任何实现**；30 天重放守卫已有消费登记（T14-S1，§9.3.1）与三个管理入口（T14-S2，§9.3.2）的
  候选切片，relay/自动重放路径仍不拒绝，且旧消费者仍会放行旧事件，因此仍**不构成删除授权**；
- 字段层面：`sent_at` / `flushed_at` 存在但**无索引**，`sent_at` 是 JVM 时钟；终态时间缺失的历史行
  **不可回填**，只能保守排除；**DLQ 首次入队时间与归档校验记录完全不存在**（§9.2）；
- 没有归档成功校验、没有去重墓碑/全入口旧消息硬拒绝（只有消费登记与三个管理入口的候选守卫，
  relay 与自动重放路径不拒绝），删除终态历史仍不安全；
- 归档执行器、断点续跑、限速、索引/锁影响验证、归档后重放不重复计数的演练都未实现；
- 容量数字来自单机隔离库的小样本，`information_schema` 是估算，`processed` 抽样有偏（只覆盖
  SCAN 命令预算内扫描到的 key，默认参数下通常一次命令就填满 100 个样本并标记“覆盖截断”），
  不能当作生产容量结论；
- 有界 SCAN 的边界现在由命令次数保证，但**没有**做生产级 keyspace 规模下的耗时/命中率评估，
  也没有“多次抽样取更稳定均值”的机制（截断时只用已扫描部分的样本）；
- 生产告警（外部告警投递）仍属 T05/T16 范围，本工具只在报告里给建议。
