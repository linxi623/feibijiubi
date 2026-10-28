# T13：读路径一致性与热榜取舍（模式 B，候选待父复验）

> 状态：**2026-10-23 候选，2026-10-28 一轮收口修复后仍是候选，等待父代理复验；T13 未验收，T12 仍不能勾选。**
> 本文只固定“面向用户的统计读走哪条路、读到的是什么口径、读不到时返回什么”，不改统计协议
> （Outbox / Redis Lua / 消费 / 刷库 / 重建一条都没动），也不新增任何 HTTP 接口。
>
> 结论一句话：**模式 B —— 用户可见的业务统计继续以 MySQL `video_status` 为唯一来源，Redis
> `current` 不参与任何用户可见响应。** 因此“Redis 实时读没接线”不是未完成 bug；反过来，任何
> 想把它接线的人必须先改这份决定。
>
> 2026-10-28 更正的一处表述：Redis `current` 的读取方**不止一个**，原先“`getByVid` 是唯一 Redis
> 统计读”的说法是错的。管理端单视频只读对账 `VideoStatusReconciliationServiceImpl#readRedisSample`
> 也会采样 `current`（只读诊断，不产生用户可见统计响应）。正确的说法是：用户可见业务统计仍全部
> 来自 MySQL；`getByVid` 仍是**没有调用方**的只读门面，不能把它叫“用户读入口”。

## 1. 计划允许的两种模式，为什么选 B

主计划 T13 给了两条路：

- **A. 详情读实时 Redis**，Redis 故障时回落 DB 并允许暂时落后；
- **B. 保持 DB 最终统计**，并给出延迟目标。

选 B 的理由（针对当前代码事实，不是偏好）：

1. 现在所有用户可见的统计读本来就是 DB 单源（见 §2）。这里核查的 Java 统计查询包括两个 Redis `current` 读取路径（不含 Lua 写入流程中的校验读取），都不是
   用户可见统计入口：一个是**没有任何调用方**的只读门面 `VideoStatusServiceImpl#getByVid`，
   另一个是管理端单视频只读对账 `VideoStatusReconciliationServiceImpl#readRedisSample`（只读
   诊断采样）。改 A 等于新写一条用户读链路，而不是修一个坏掉的链路。
2. A 会把读语义绑到重建协议上：`current` 缺失/重建中/坏字段/Redis 超时四种状态都要在
   读路径里定义清楚，而“读 miss 不能同步触发昂贵重建”“重建期间读到的是旧值还是 DB 基线”
   这些问题的答案会直接影响 T09/T12 的保证边界。
3. B 的坏处只是**最终一致**（刷新延迟），而这个延迟在做完 §4 的诚实描述后是可接受的产品语义；
   A 的好处（省一次主键查询）在 `video_status` 这种单行主键读上收益很小。
4. 不存在“先 A 再补 B”的中间态：一旦某个接口读 Redis，就必须为它单独定义 miss、损坏、
   门闩、Redis 故障四种语义，并同步进 `docs/api.md`。当前没有这个产品需求。

**明确不做的事**：不引入 Redis 实时读；不在 read miss 时同步触发重建；不为读路径放宽任何
DB 错误守卫；不为了让测试“看起来覆盖 Redis”而给读路径新增 Redis 调用。

## 2. 读表面清单（代码核查结果）

“统计读”指返回视频互动计数的用户可见/管理员可见入口。核查范围是 `service/impl` 下的读方法
与对应 Mapper SQL。

| 入口 | Service / Mapper | 统计来源 | 返回字段 |
|---|---|---|---|
| `GET /api/videos/{vid}` 详情 | `VideoServiceImpl#getVideoDetail` → `VideoStatusMapper#selectByVid` | `video_status` 单行 | `play/like/coin/collect/comment/danmu/share`（7 项，**无** `unlikeTimes`） |
| `GET /api/videos/feed` 列表 | `VideoServiceImpl#getVideoFeed` → `VideoMapper#selectFeed` | `video ⋈ video_status` 同一条 SQL 投影 | `playTimes`、`commentTimes`（2 项） |
| `GET /api/users/me`、`GET /api/users/{uid}` | `UserServiceImpl#buildUserVO` → `VideoStatusMapper#countLikeByUid` | `SUM(video_status.like_times)` | `userCount.loveCount` |
| 管理员视频详情 | `VideoReviewImpl#getVideo` → `VideoStatusMapper#selectByVid` | `video_status` 单行 | 8 项（含 `unlikeTimes`） |
| 未接线：`VideoStatusServiceImpl#getByVid` | `RedisHashOperations#entries` → miss 时回落 `selectByVid` | Redis `current`（miss 时 DB） | 8 项（`VideoStatus` 实体） |
| 管理端只读对账（诊断） | `VideoStatusReconciliationServiceImpl#readRedisSample` → `RedisHashOperations#multiGet` | Redis `current` 只读采样（**不是**统计读入口） | 8 项，仅用于对账诊断输出 |

核查结论：

- 四个用户/管理员可见入口**没有任何 Redis 依赖**，`src/test/.../VideoStatusReadConsistencyTests`
  用反射断言了这三个服务类既不声明也不注入 `*Redis*` / `*Redisson*` 字段或构造参数。也就是说
  `current` 的 miss、坏字段、重建门闩、Redis 故障在这条链路上**不可达**，既不会改写 DB 结果，
  也没有“伪造 0”的路径。
- `getByVid` 目前**没有调用方**（`grep` 只命中它自己和测试），本身不是用户读入口。它 miss 时回落
  DB、坏字段抛 `VideoStatusCounterRangeException`、Redis 故障抛 `RedisOperationException`，
  都不返回 0。它属于“将来可能用到的只读门面”，不属于用户可见路径，因此**不能**把它叫“用户读
  入口”。
- Redis `current` 还有一个读取方：`GET /api/admin/video-status/reconciliation?vid=...` 背后的
  `VideoStatusReconciliationServiceImpl#readRedisSample`。它是**只读诊断采样**（一次 `multiGet`
  读八个字段并做 canonical 校验，不写 Redis/MySQL、不领取租约、不改事件状态），输出只用于对账
  诊断，从不进入用户可见统计响应；接口结论固定为 `NO_SAFE_CONCLUSION_AUTOMATIC_REPAIR_DISABLED`。
  因此“`current` 不参与用户可见响应”仍成立，但“只有一个 Redis 读取方”不成立。
- 没有任何“解析失败 → 0”的分支：缺失/损坏字段一律显式异常；DB 统计行缺失在详情是 500、
  在管理员详情是 404、在 Feed 是被 JOIN 丢弃。

## 3. 口径差异（必须按接口分别描述，不能笼统说“全站统计”）

同一行 `video_status` 数据，不同接口的**可见范围**与**暴露字段**不同：

### 3.1 视频详情

```sql
SELECT vid, play_times, like_times, unlike_times, comment_times,
       coin_times, share_times, collect_times, danmu_times
FROM video_status WHERE vid = #{vid}
```

- 视频本身必须已过审（`status=1`）且未删除；`visibility=1`（私密）只允许作者本人查看，其他
  用户 403。**统计行本身不做可见性过滤**：作者看自己的私密视频，看到的是这一行的真实计数。
- 统计行缺失 → `code=500 视频统计数据异常`（不会返回 0，也不会继续查作者/粉丝）。
- `unlike_times`（“踩”）只累计，详情 VO 不暴露；要暴露它属于产品决定，不是读路径修复。

### 3.2 Feed（列表）

```sql
FROM video v
INNER JOIN video_status vs ON vs.vid = v.vid
INNER JOIN users u ON u.id = v.uid
WHERE v.status = 1 AND v.visibility = 0 AND v.deleted_at IS NULL
```

- 只返回**过审 + 公开 + 未删除**的视频，因此私密视频的播放/评论数不会出现在列表里。
- 只投影 `vs.play_times`、`vs.comment_times` 两列；其余计数不在列表里出现，列表也不会为每条
  视频单独查一次 `video_status`（无 N+1，也无 Redis 逐条读）。
- `INNER JOIN` 的语义：统计行缺失的视频**整条不出现**（被数据库丢弃），不会显示成 0。

### 3.3 用户获赞数（`loveCount`）

```sql
SELECT COALESCE(SUM(vs.like_times), 0)
FROM video_status vs
INNER JOIN video v ON v.vid = vs.vid
WHERE v.uid = #{uid} AND v.status = 1 AND v.visibility = 0 AND v.deleted_at IS NULL
```

- 是**跨视频聚合**，不是单视频计数；只计过审 + 公开 + 未删除的视频，私密/未过审/已删除视频的
  点赞**不计入**。
- 只加 `like_times`，**不减去** `unlike_times`；因此 `loveCount` ≠（点赞 − 踩）。
- 空集时 SQL `COALESCE` 给出 `0`，这是“没有符合条件的视频”的正确聚合值，不是解析失败兜底。
- 聚合失败（DB 异常）直接向上抛，不会返回 0；`UserConverter` 对 `null` 的默认 0 只覆盖
  “聚合结果本来就是空集”的情况，SQL 侧已用 `COALESCE` 保证不会因空集返回 null。

### 3.4 管理员视频详情

- 与用户详情同源（`selectByVid`），但**不做可见性判断**（私密视频同样返回统计行），返回全部
  8 项（含 `unlike_times`）。
- 8 项计数从同一次 `selectByVid` **原样透传**（不截断、不经 float）：`2^53+1`、`Long.MAX_VALUE`
  这类值也保持精确，客户端需无损失地解析 JSON（与 T02 的计数范围契约一致）。
- 统计行缺失 → `code=404 视频状态为空`，且**不再查作者/粉丝**，不会拼出“作者粉丝齐全、统计为 0”
  的半成品响应。与用户详情的 500 文案不同；这是既有实现差异，本文如实记录，统一两者文案会改
  线上返回值，属于独立变更。

## 4. 延迟语义：最终一致，没有硬性 SLA

模式 B 的代价是“刚发生的互动要等异步链路落库后才会出现在响应里”。当前链路与延迟贡献项：

| 环节 | 配置/机制 | 对延迟的影响 |
|---|---|---|
| 业务事务 | `async-enabled=true` 时只写 Outbox 行 | 事务提交即可见事件，不阻塞响应 |
| Outbox 投递 | `outbox-fixed-delay-ms=1000`，`outbox-batch-size=100` | 常态化 ≤1 s 量级的轮询延迟 |
| RabbitMQ + 消费 | 消费者应用增量到 Redis，写 consumed 事件（`process_status=1`） | 通常毫秒级；**此时 DB 还没变** |
| 批量刷库 | `flush-fixed-delay-ms=500`，`flush-event-batch-size=1000` | 再叠加 0.5 s 量级的调度延迟 |
| 失败重试 | 10 s / 30 s / 120 s 三档 TTL 队列 + `consumer-max-retries` | 失败一次就是 10 s 起的可见延迟 |
| 单轮预算不足 | `round-budget-ms=5000`、`flush-dirty-batch-size=100` | 工作堆积时按轮延后，不是立即落库 |

因此：

- **正常路径**：从互动到详情/列表/获赞数反映出来，量级是“约 1～2 秒”（轮询 1 s + 刷库 0.5 s
  加处理时间），这是**观察量级，不是承诺**。
- **异常路径**：重试退避、工作堆积、调度停摆都会让延迟变成分钟级。
- **本文不承诺任何健康时延 SLA**（没有 p95/p99 目标，也没有“超时多久必须告警”的合同）。
  T05 的 15 s 采样间隔、`PENDING_ALERT_AGE_MILLIS=120 s`（最老待处理年龄）、
  `SCHEDULER_ALERT_AGE_MILLIS=30 s`（调度心跳）都是**诊断阈值**：它们回答“现在看起来是否不对劲”，
  不是“延迟保证”，也不是产品承诺。生产阈值本身还是 T16 的事。
- 想让某个接口实时，只能先按 §1 改模式，并按 §7 补齐 miss/损坏/门闩/Redis 故障语义。

## 5. Redis `current` 的真实地位（读侧）

`VideoStatusServiceImpl#getByVid` 是一条**没有调用方**的只读门面（**不是**唯一的 Redis 读取方，
也不是用户读入口：管理端对账另有只读采样，见 §2）。它的读语义
（`VideoStatusCounterReadTests` 已覆盖，T13 补充 miss/门闩/故障用例）：

| 状态 | 行为 | 是否伪造 0 |
|---|---|---|
| `current` 命中且字段合法 | 逐字段 `VideoStatusCounterRange.parse`，`3e9` / `Long.MAX` 精确 | 否 |
| `current` miss | 回落 `video_status.selectByVid`；**不回填缓存、不取 vid 锁、不触发重建** | 否 |
| `current` 字段损坏/缺失/负数/超界 | 抛 `VideoStatusCounterRangeException`（含字段名与实际值） | 否 |
| `current.vid` 与请求不符 | 抛 `IllegalStateException`，不返回别人的计数 | 否 |
| Redis 运行时故障 | 抛 `RedisOperationException`，**不回落 DB**（不把 Redis 故障藏成“看起来正常的旧值”） | 否 |
| 重建门闩存在（正在重建） | 读路径**不读**门闩、不取锁；最多表现为 miss（回落 DB）或读到旧值 | 否 |

“读路径不看门闩”这一点是刻意的：门闩只保护写侧（增量/初始化/替换），读侧若去等锁，就会把
重建的持锁时间放大到读延迟上，而读到的值本来也只是缓存的快照。

## 6. 热榜 ZSet：本阶段明确延期保留（不改协议）

事实核查：

- 键：`feed:hot:videos:v1`（`RedisKeyUtils#feedHotVideos`）。
- 写入点三处：`video-status-increment.lua` 每次成功应用事件后 `ZINCRBY`；
  `video-status-init.lua` 重建时 `ZADD`；`video-status-replace.lua`（未开放原语）替换时 `ZADD`。
- 读取点：**零**。没有任何 Service/Controller/SQL 读取该 ZSet，也没有 API 暴露“热门列表”。
- `score` 是加权累计值（play 1.0 / like 1.5 / unlike −1.0 / comment 3.5 / coin 4.0 /
  share 2.5 / collect 4.0 / danmu 2.0），**没有时间衰减、没有窗口**。

决定：**延期保留，既不接入也不停止写入**，理由是“先不改统计协议”。

- 保留的代价（可量化部分）：每成功应用一次事件多一次 `ZINCRBY`（在既有 Lua 里，不增加往返）；
  每个被消费过的 vid 占一个 member，内存是 **O(视频数)** 而不是 O(事件数)——事件再多也只是改
  score；视频删除时**没有** `ZREM`，所以 member 会随历史视频单调累积。具体字节数未测（T14）。
- 保留的收益：**当前用户可见收益为零**（没有读取方）。保留它只是因为它现在是写路径的一部分，
  删掉要动 Lua 协议与 T02/T12-C1 的已验收脚本，属于独立变更。
- 若要接入或删除，必须先定义（写成独立实施项）：排序口径（累计分 vs 时间窗/衰减）、
  删除/下架视频的 `ZREM`、历史热度重建来源、分页方式、以及“允许读到累计分”的产品说明。
  在此之前不存在“热门榜功能”，也不要把该 ZSet 当成已验证的排序能力。
- 与 T12-C1 的关系：replace 原语的 `ZADD hot` 只保证“替换 current 时同步 hot 的当前分”，
  不是热榜读取能力，也不是历史热度重建。

## 7. 测试证据

新增（2026-10-23 首版 + 2026-10-28 补充管理员详情，纯 Mockito，不连 Redis/MySQL/RabbitMQ）：
`src/test/java/com/feibijiubi/backend/service/video/VideoStatusReadConsistencyTests.java`，16 例：

| 分组 | 用例 |
|---|---|
| 详情（DB 正常/缺行/异常） | `detailReturnsTheDatabaseRowCountersExactly`、`detailWithoutStatisticsRowFailsInsteadOfReportingZero`、`detailPropagatesDatabaseFailureInsteadOfZeroCounters` |
| 管理员详情（2026-10-28 新增） | `adminDetailPassesAllEightDatabaseCountersExactlyIncludingAbove2Pow53`、`adminDetailWithoutStatisticsRowReturns404AndStopsBeforeAuthorAndFans` |
| Feed（DB 正常/异常） | `feedReturnsTheSingleJoinedDatabaseProjectionWithoutPerVideoStatusReads`、`feedPropagatesDatabaseFailureInsteadOfEmptyPage` |
| 用户获赞（DB 正常/异常） | `userLoveCountIsTheDatabaseAggregateAndKeepsLargeValues`、`userLoveCountPropagatesDatabaseFailureInsteadOfZero` |
| Redis 状态不可达/不改结果 | `statsReadServicesDeclareNoRedisCollaborator`、`cacheMissReadsTheDatabaseAndNeitherWritesBackNorStartsARebuild`、`cacheMissNeverConsultsTheRebuildGateSoAHeldGateCannotBlockTheRead`、`corruptedCacheFieldFailsLoudlyInsteadOfZeroOrDatabaseFailOpen`、`redisFailureOnTheReadSeamIsNotTranslatedIntoZeroCounters` |
| 口径/字段面 | `detailExposesSevenCountersAndFeedOnlyPlayAndComment`、`sqlProjectsOneDatabaseSourceWithTheDocumentedFilters` |

管理员详情两例的断言口径（跑真实 `VideoReviewImpl#getVideo` + 真实 `VideoConverter`）：

- 正常路径把 8 项计数逐项精确透传，其中 `playTimes` 用 `2^53 + 1 = 9007199254740993`、
  `likeTimes` 用 `Long.MAX_VALUE`，任何 float/截断转换都会立即失败；私密视频（`visibility=1`）
  也照样返回统计行，证明服务层不做可见性过滤（SQL 无可见性条件由源码核查确认，不是此 mock 测试的证明范围）；并断言 `selectByVid` 只被调一次，不存在
  “按字段补齐”的第二次统计查询。
- 统计行缺失时抛 `code=404 视频状态为空`，且 `userMapper` / `userFollowMapper` 零交互、
  `countVideoByUid` 从未调用——不会拼出一个“作者/粉丝齐全、统计为 0”的半成品 VO。

要点：这些断言跑的是真实生产代码路径（真实 `VideoConverter`、真实 `getByVid` 解析），Mockito
只替换 Mapper/Redis 门面；SQL 口径用 Mapper XML 的**单个 `<select>` 块**做静态断言，避免
“断言串匹到隔壁语句”。Redis 侧的命中/坏字段/vid 不匹配/miss 已由既有
`VideoStatusCounterReadTests`（8 例）覆盖，本类只补它没覆盖的 miss 副作用、门闩与 Redis 故障。

本轮实际运行（2026-10-28，受限堆串行、`videoStatusIT/Scale/Baseline=false`）：
`./mvnw -B -DvideoStatusIT=false -DvideoStatusScale=false -DvideoStatusBaseline=false
-DargLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'
-Dtest=VideoStatusReadConsistencyTests,VideoStatusCounterReadTests test` =
**24 tests，0 failures/errors，0 skipped**（`VideoStatusReadConsistencyTests` 16 +
`VideoStatusCounterReadTests` 8；末次加 `-o` 离线复用本地依赖），日志 `/tmp/t13-fix-target-unit2-A3BgFA.log`（同轮首跑 `/tmp/t13-fix-target-unit-E1ZD6a.log` 结果相同）。本轮没重跑整包普通回归，
所以不复述旧的 542 tests 数字。

既有 T02 用例（不重复、不修改）：`VideoStatusCounterReadTests` 8 例。

## 8. 明确未做 / 仍开放的问题

- **未接线**：`getByVid` 仍然没有调用方；没有 Redis 实时读，也没有“读 miss 触发重建”。
  这是本模式的结论，不是欠账。
- **未测量**：真实 HTTP 端到端 DB 可见延迟（从互动到详情/Feed/获赞数变化）没有压测；
  §4 的“约 1～2 秒”是配置推算，不是实测分布。
- **未统一**：用户详情 500 与管理员详情 404 的缺行文案差异保留原样（改文案会改线上返回值）。
- **未实现**：热榜读取/删除/历史重建；`unlike_times` 的用户可见读；Feed 暴露更多计数。
- **测试环境限制**：2026-10-28 只用受限堆（`MAVEN_OPTS=-Xms64m -Xmx256m`、
  `argLine=-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true`）串行跑了两个单测类，未跑
  `BackendApplicationTests`，未启动隔离 compose 环境，也未跑整包普通回归。
- **未改生产 Java**：本轮只改测试与文档；`VideoStatusReconciliationServiceImpl` 的 Redis 采样
  行为不变，只是文档不再宣称 `getByVid` 是唯一的 Redis 读取方。
- **T13 仍是候选**：等父代理独立复验后才谈验收；T12（强制重建/生产容量）与 T10（DLQ/批量）
  的未完成项不因本文改变。
