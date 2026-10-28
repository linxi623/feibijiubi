# 互动统计链路改进执行计划与 TODO

> 状态：执行中；T01—T09 已验收并合入；T10 进行中（第一阶段失败诊断/确认已阅与只读对账已合入主分支
> `6fc5e7f`；T10-B1 consumed status3 单事件 proven-only 受控重放的窄范围用例已由父代理复跑通过，
> 但 B1 仍为候选、T10 整项待验收；DLQ/批量处置仍欠，整项未完成）；T11（dirty 恢复公平扫描与失败退避）
> 已验收（按计划允许在 T06 后提前）。T12（A/B/C1）、T13、T14-A、T14-S1、T14-S2 均为 2026-10-23/24 候选、未父验收，
> T12/T13/T14 整项未完成。T14 的保留期与重放窗口政策已于 2026-10-23 批准，**起算点与归档存储已由本轮用户确认**
> （见 T14 段），但**仍仅为决策、尚无实现**；T14-S1 落地了消费登记侧的 30 天绝对年龄拒绝候选，T14-S2 把同一
> 固定上界接到 T07/T10-B1/T09 三个管理入口（均无归档/删除）。
> T07/T08/T09 为测试环境验收，不等于生产验收。仅表中已完成且有证据的项代表已实现能力；不代表批准生产上线。
>
> 范围：互动入口 → Outbox → RabbitMQ → Redis → 消费事件 → 批量落库 → 重建/补偿。
> 原则：先验证正确性，再补恢复闭环，再治理稳定性，最后优化吞吐。
> 初版仅制定计划；2026-10-21 起按本文实施。仅使用独立测试环境，逐项验收，不自动部署。

## 1. 审计结论的使用边界

前期结论来自静态代码审计，不等于所有故障均已复现。实施前必须遵守以下约束：

1. **负增量顺序窗口需要确定性测试。** 已有“正增量优先”排序及负增量测试，但尚需专门覆盖“Redis +1 已完成、消费状态提交晚于 -1”的落库窗口，不能声称现有测试完全没有负增量覆盖。
2. **UNSIGNED 运算需在实际 MySQL 版本和 SQL mode 下验证。** 不直接假定所有环境都报同一种错误。
3. **关闭调度不等于数据必然丢失。** `async=true/scheduling=false` 可以用于维护或分角色部署；缺陷是缺少明确契约、状态检查和无人落库告警，不能一律禁止此组合。
4. **重投不等于重复计数。** 已有 DB 唯一键、语义指纹、Redis processed、消费状态防护，需要保留。不能宣称这些机制覆盖任意 Redis 数据丢失。
5. **停止在 status0/DLQ 不等于消息已被物理删除。** 当前主要缺口是未完成事件无法自动收敛，且缺少安全处置工具。
6. **Redis 实时读取尚未接入主要业务路径，不等于整个异步链路无收益。** 批量写热点行仍有价值，但数据库总写入次数并未因此必然下降。
7. **Outbox 租约恢复增加重试数，不代表仅崩溃固定次数就一定进入 FAILED。** 当前最终失败判定在发布异常分支；需分别测试恢复、再次成功和再次失败的行为。

保留现有关键设计：业务与 Outbox 同事务、状态登记独立提交、落库与 FLUSHED 同事务、重建门闩令牌校验、vid 锁协调、dirty 只补加不并发误删。不得通过去掉幂等、非负校验或强制清零来“修复”测试。

## 2. TODO 总表（唯一进度入口）

状态：`待开始` / `进行中` / `待验收` / `已完成` / `阻塞` / `延期`。
完成要求：实现、测试、文档、验收证据全部具备后才勾选；不能以“代码写完”代替验收。

| 完成 | 顺序/ID | 优先级 | 任务 | 前置依赖 | 当前状态 | 验收证据 |
|---|---|---|---|---|---|---|
| [x] | 01 / T01 | P0 | 冻结不变量、隔离测试与故障复现基线 | 无 | 已完成 | [基线报告 §8](video-status-improvement-baseline.md)：单测31通过；隔离25用例/1可选跳过；真实阻塞100轮 |
| [x] | 02 / T02 | P0 | 验证并修正非负 SQL、统一计数类型 | T01 | 已完成 | a9a3ad9；最终87单测通过/62隔离用例0失败1可选跳过；真实计数/事务回滚见下方验收记录 |
| [x] | 03 / T03 | P0 | 修复负增量落库顺序窗口与错误分类 | T02 | 已完成 | c4d6e99；真实消费者阻塞100轮全部延后后收敛、0误隔离；真实上限LIMIT1拆批收敛 |
| [x] | 04 / T04 | P0 | 修复失效开关、明确运行角色及开关契约 | T01；按序在 T03 后交付 | 已完成 | fe11d03；配置绑定/启动拒绝6用例，角色13用例，真实维护切换最终DB=Redis=6、4异步2同步 |
| [x] | 05 / T05 | P1 | 建立最小链路指标与积压告警 | T03、T04 | 已完成 | 46af28b；父代理 2026-10-22 复验：69 单测 + 8 隔离用例均 0 失败/错误/跳过；四类故障可定位、采样只读、迁移幂等 |
| [x] | 06 / T06 | P1 | 隔离调度资源、限制单轮任务预算 | T05 | 已完成 | 3e33415；同批 69 + 8；受控阻塞 30.082s 时 flush 62ms、恢复继续心跳；并发落库 baseline=100/flushed=100 |
| [x] | 07 / T07 | P1 | 完善 Outbox 发布、回写、租约处理 | T06 | 已完成 | 95af60d；父代理 2026-10-22 全链路回归 `VideoStatusOutbox*Tests,VideoStatusIsolatedIntegrationTests,VideoStatusMetricsIsolatedTests,VideoStatusSchedulingRealFlowTests,VideoStatusDirtyRecoveryRealConvergenceTests`：87 tests，0 失败/错误，1 skipped（可选 benchmark），日志 `/tmp/improvement-main-regression3.log`；T07 单组 61 tests 0 失败/错误/跳过；测试环境验收 |
| [x] | 08 / T08 | P1 | 消费重试退避、故障限速、ACK 边界 | T05、T07 | 已完成 | 0dd98ad/b846032/92eb8c1（候选 b796fc8）；候选父复验 22 tests，0 失败/错误，1 skipped，日志 `/tmp/t08-parent-final.log`；真实 10/30/120s TTL、容器 stop/start、Redis CLIENT PAUSE 120s（非进程/网络断连） |
| [x] | 09 / T09 | P1 | 接通安全窗口内 status0 自动补偿 | T03、T07、T08 | 已完成（测试环境验收） | 主分支 `97c7357`；父代理 2026-10-22 实跑单测 356 tests，0 失败/错误，108 skipped（`/tmp/t09-main-unit.log`）；opt-in IT 62 tests，0 失败/错误，1 skipped（`/tmp/t09-main-it.log`）；候选 `aab1c64` 早前 43 tests 0 失败/错误/跳过（`/tmp/t09-parent-final.log`）；无证据模式保持受控/默认关，非生产验收 |
| [ ] | 10 / T10 | P1 | DLQ/Outbox FAILED/status3 处置及对账闭环 | T05、T09 | 进行中（第一阶段已合入主分支 `6fc5e7f`；T10-B1 窄范围用例父代理已复跑通过，B1 仍为候选、整项待验收） | 第一阶段失败分页/详情/预检查/受审计确认与单视频只读对账已合入；父代理 2026-10-22 实跑 opt-in IT 121 tests，0 失败/错误/跳过（`/tmp/improvement-t10-main-it.log`，含 T07 replay/T09 compensation），普通单测 416 tests，0 失败/错误，130 skipped（`/tmp/improvement-t10-main-final-unit.log`）；T10-B1 候选：父代理 2026-10-23 复跑窄范围单测 54 tests（含 `VideoStatusStatus3ReplayServiceTests` 26 例）与隔离 IT `VideoStatusStatus3ReplayIsolatedTests` 11 tests，均 0 失败/错误/跳过（`/tmp/t10b1-parent-unit.log`、`/tmp/t10b1-parent-it.log`），仍待最终裁定；DLQ 摄入/处置、刷库型 status3 修复、批量操作和自动修复/收敛仍未实现，整项不勾选 |
| [x] | 11 / T11 | P1 | dirty 恢复公平扫描及失败退避 | T03、T05、T06 | 已完成 | be7d836；父代理 2026-10-22 实跑 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusDirtyRecoveryRealConvergenceTests,VideoStatusDirtyRecoveryFairnessIsolatedTests' test`：3 tests，0 失败/错误/跳过，日志 `/tmp/t11-real-convergence.log`；真实 MySQL/Redis，专用头部 SADD 故障注入，四轮尾部获机会，最终 250 收敛且无 status3 |
| [ ] | 12 / T12 | P2 | 有界重建、批量查询及受控强制修复 | T09、T10、T11 | 进行中（T12-A/T12-B 为 2026-10-23 候选且父代理已复跑验证；整项未验收） | T12-A 候选：重建分类只对 status0 候选按固定 256 条一批 pipeline `EXISTS` 查询 processed 证据，status1 不再查询；逐 key 顺序、重复 key 按出现次数计数，失败 fail closed 且无半初始化，锁/无 TTL 门闩/token release/初始化参数不变。T12-B 候选：重建候选改为 `load()` 同一 REPEATABLE_READ 只读事务内的 keyset 分页（`id > afterId`，每页 ≤1000，不用 OFFSET，`FORCE INDEX (vid,id)`），候选上界 `rebuild-max-candidates`（默认 100000）与快照读取预算 `rebuild-snapshot-budget-ms` 显式配置，超上限/超预算 `RetryableMessageException` 且不返回截断快照、无半初始化。父代理复跑：普通回归 497 tests（0 失败/错误，144 skipped，`/tmp/improvement-parent-unit-final.log`），隔离 31 tests（20 全链路 + 11 B1，0 失败/错误，1 skipped，`/tmp/improvement-parent-t12b-it.log`）；本轮补行级 id 校验（页内 id 必须存在且严格递增）、4 例分页用例与规模测量类后复验 504 tests（0 失败/错误，147 skipped，`/tmp/t12b-worker-unit.log`）与 `VideoStatusIsolatedIntegrationTests` 20 tests（0 失败/错误，1 skipped，`/tmp/t12b-worker-it.log`）。新增 opt-in `VideoStatusRebuildScaleIsolatedTests`（默认 skipped，`-DvideoStatusScale=true`）实测 0/1,000/100,000 候选各 3 次冷重建：100,000 → 5.3–9.6 s（三轮 9 个样本）、391 次 pipeline/100,000 条 EXISTS 逻辑命令、heap 池峰值增量 131–241 MiB（上界近似），预设 oracle 不漏不重；恢复轮以受限堆 `-DargLine=-Xms64m -Xmx512m` 串行复跑分页单测 16 tests 与规模类 3 tests，均 0 失败/错误（`/tmp/t12-scale-recovery.log`）；单机单 JVM、3 样本、P95 仅诊断，**不是性能验收**（`/tmp/t12-scale-full.log`）。部署硬依赖：上线前必须先执行 `t10-video-status-reconciliation-indexes.sql`，缺索引时重建失败并重试/DLQ；超候选上限同样重试/DLQ，无自动修复，需运维处置。历史并发用例间歇错误（2 errors）未定位未修复，本轮两次隔离运行均通过但不得写成已解决。T12-C1 候选（2026-10-23，**内部原语，未开放**）：新增 `video-status-replace.lua` + `RedisScriptConfig#videoStatusReplaceScript`，keys/args 同 init 但只允许“current 已存在且完整”时覆盖，拒绝码覆盖门闩/冷 miss/类型/TTL/未知字段/损坏取值/vid 不一致，校验全在写之前；三条写（HSET/SADD/ZADD）各自 pcall 返回阶段码，**不承诺回滚**；不 DEL current、不改 processed、不释放门闩、`pending=false` 不 SREM。无 HTTP/Java 入口、无自动调用；强制重建权限/审计/并发协调、崩溃恢复、完整性校验与生产容量验收仍未实现 |
| [ ] | 13 / T13 | P2 | 明确查询一致性、接实时读或保留 DB 读 | T02、T05、T12 | 进行中（模式 B 候选，2026-10-23，未父验收；T13 不勾选） | 选 B：面向用户统计读继续以 MySQL 为唯一来源，不接 Redis 实时读、不在 read miss 同步重建；核查详情/Feed/获赞数/管理员详情读源，逐接口记录“缺行”语义差异；延迟为最终一致、无健康 SLA（正常量级约 1～2 s 由配置推算，10/30/120 s 重试与堆积可到分钟级；T05 15 s 采样、120 s 最老待处理、30 s 心跳仅为诊断阈值）；热榜 ZSet 三处写入、零读取，延期保留并记录成本；新增 `VideoStatusReadConsistencyTests` 14 例，普通回归 542 tests 0 失败/错误、171 skipped（`/tmp/t13-unit-worker.log`，受限堆串行、排除 `BackendApplicationTests`）；无 HTTP/迁移/协议变更。**2026-10-28 收口修复（仍为候选、不勾选）**：更正“`getByVid` 是唯一 Redis 统计读”的错误表述——管理端单视频只读对账 `VideoStatusReconciliationServiceImpl#readRedisSample` 也采样 `current`（只读诊断，不构成用户读入口，`getByVid` 仍无调用方）；补管理员详情 2 例（8 项精确透传含 `2^53+1` 与 `Long.MAX_VALUE`、缺行 `404 视频状态为空` 且不查作者/粉丝），定向串行 **24 tests，0 失败/错误/跳过**（末次 `-o` 离线，`/tmp/t13-fix-target-unit2-A3BgFA.log`（同轮首跑 `/tmp/t13-fix-target-unit-E1ZD6a.log` 结果相同），未重跑整包回归，未改生产 Java） |
| [ ] | 14 / T14 | P2 | 事件归档、幂等保留与 Redis 容量治理 | T09、T10、T12 | 进行中（T14-A 只读容量核查 + 归档 dry-run 候选，2026-10-23；T14-S1 消费登记侧 30 天绝对年龄拒绝候选，2026-10-24；T14-S2 同一固定上界接入 T07/T10-B1/T09 三个管理入口候选，2026-10-24（同日审查修复轮：T09 parse 失败分支改为 withinSafeWindow=false + 白名单原因码，定向 94 tests，`/tmp/t14-s2-review-fix.log`）；均未父验收；T14 整项未完成） | T14-A 候选（**测试作用域、只读、无清理**）：`src/test/.../service/video/capacity/` 三个工具类 + `VideoStatusCapacityAuditIsolatedTests` 8 例 opt-in + 纯单测 11 + 2 例。可测数据=`information_schema` 体积估算（不 `COUNT(*)`）+ Redis `INFO memory`/`CONFIG GET`/`DBSIZE` + 有界 `SCAN`/`MEMORY USAGE` processed 抽样；终态 dry-run 只列 id/时间/状态，`FORCE INDEX` + 时间窗 + `LIMIT`，缺索引/访问路径不符报 `NOT_EVALUABLE` 不降级全表扫描；status0/1/3 与 FAILED 永不列，**终态也一律 `NOT_AUTHORIZED_FOR_DELETION`**（保留期政策已批准但归档/删除/30 天守卫均无实现、无归档成功校验与旧消息硬拒绝/去重墓碑，processed TTL 不是删除证据）；数据不足一律 UNKNOWN 不回落 0。普通回归 **563 tests，0 失败/错误，179 skipped**；隔离 8 tests，0 失败/错误/跳过；无 HTTP/schema/迁移变更。**未完成**：保留期与重放窗口政策已于 2026-10-23 批准（成功终态在线 90 天 + 归档副本 275 天；DLQ 30 天处置 SLA，非 TTL、未解决不自动删除；审计 365 天；普通重放窗口 30 天，超期转人工核查；30 天内不放宽 T07/T09/T10 守卫；T15 两表不纳入），**起算点与归档存储已由本轮用户确认**（在线 90 天从 `sent_at`/`flushed_at` 终态完成起算、非创建/接收时间；归档 275 天从归档副本校验成功起算、非创建时间，归档延后则实际总保留可超过 365 天；审计 365 天从审计行创建起算；DLQ 30 天 SLA 从首次入死信队列起算、重投不重置、未解决不自动删除；独立私有 COS 归档桶，完整性+记录数校验通过才允许删在线数据，开发阶段仅隔离测试存储），但归档、删除与 30 天守卫**均无实现**，去重墓碑、限速分页断点续跑归档、归档中断重跑与重放不重复计数演练、索引/锁影响验证仍欠（字段缺口：`sent_at` 为 JVM 时钟且 `sent_at`/`flushed_at` 无索引，DLQ 首次入队时间与归档校验记录不存在，见 `docs/tutorial/video-status-capacity-audit.md` §9.2）；T14 不勾选 |
| [ ] | 15 / T15 | P2 | 播放/分享防刷与业务幂等策略 | T04、T05 | 进行中（第一阶段 DB 核心 + 第二阶段 HTTP 接线/Redis 准入为 2026-10-23 候选；30 天有界清理为本轮候选（默认关闭、未运行隔离用例）；均已接线/落地但未父验收、未上线，不勾选） | 新增 `video_interaction_request`（全局唯一、`request_key` 大小写敏感 `utf8mb4_bin`、state/event_id/时间）与 `video_interaction_window`（actor_type+actor_hash+vid+action 唯一、`last_counted_at` 可 NULL = 从未计数）两表 + 幂等迁移 `database/migrations/t15-video-interaction-idempotency.sql`（只新增表，不改/不删既有行，镜像 `database/feibijiubi.sql`）；新增 `VideoInteractionService#record` 事务服务：事务外幂等读重放 → 同事务占位 INSERT → 窗口 ODKU 保证行 → `FOR UPDATE` → DB `NOW(3)` 守卫更新（`last_counted_at IS NULL OR <= NOW-30min`）→ 获准才计数（异步复用既有 `createEvent` 拿 eventId / 同步复用 `increasePlayTimes`/`increaseShareTimes` 守卫更新）→ 终态 CAS；抑制零写不推进窗口；并发同 `requestKey` 靠唯一键冲突让尝试事务整体回滚后在事务外读赢家，`record()` 断言不得在已有事务中调用。第一阶段证据（历史）：`VideoInteractionServiceTests` 25 例、隔离 7+7 例、普通回归 613 tests/194 skipped（`/tmp/t15-unit-full2.log`）。**第二阶段（已接线）**：`play-count`/`share` 必填 `Idempotency-Key`（1..64 安全字符、大小写敏感），匿名必填 `X-Visitor-Id`（canonical UUID），返回 `{counted,duplicate,reason}`；准入顺序 参数校验 → 身份解析 → 视频校验（`PublishedVideoGuard`）→ 只读重放（不碰 Redis）→ Redis 两维固定窗口（60 s；单动作 60、合计 120，`app.video-interaction.*`，超限 429 统一文案，Redis 异常 503 fail-closed）→ `record`；旧签名已删除且无重载。已批准决策：两个头强制、缺失/非法 400、不兼容旧客户端且无兼容窗口；视频下架/删除后同一 `requestKey` 重试返回 404（视频校验先于重放）；匿名身份为客户端随机 UUID（可随时更换，故只约束合作客户端）、不新增身份机制；30 分钟窗口不可配置；额度在事务前消耗、DB 失败不退。证据：普通回归 666 tests，0 失败/错误，200 skipped（`/tmp/t15b-unit-full.log`）；定向单测 72 tests（`/tmp/t15b-target-unit3.log`：HttpMapping 7 + ActorResolver 7 + Admission 17 + RateLimit 9 + Service 32）；准入隔离 6 tests（`/tmp/t15b-admission-it4.log`）、第一阶段隔离回归 20 tests（`/tmp/t15b-regression-it1.log`）、维护演练 1 例（`/tmp/t15b-drill-maintenance.log`）。**仍未绿/未做**：隔离套件最近一次完整运行 `VideoStatusIsolatedIntegrationTests` 20 tests 有 1 failure（`concurrentFlushAndConsumptionPreserveAllEvents`，历史并发用例、不经过 T15 入口、根因未定位未修复，不得写成已解决或整组全绿，`/tmp/t15b-drill-it2.log`）；**T15 30 天有界清理（本轮候选，默认关闭，未运行隔离用例）**：保留期固定为常量（request 自 `completed_at` 起 30 天、window 连续 30 天无计数），**没有** `retention-days` 配置；`cleanup-enabled=false`（默认关闭，服务内二次判断）、`cleanup-batch-size=500`（启动校验 1..5000）、`cleanup-fixed-delay-ms=60000`（校验正数）；独立单线程 `ThreadPoolTaskScheduler`（线程池 bean `videoInteractionCleanupTaskScheduler`，与 `@Component` 组件 bean
`videoInteractionCleanupScheduler` 不同名）与 T06 投递/落库/恢复/补偿链路隔离；每轮 state=1/state=2/window 各一批，先非锁定选 id 再按主键**重复时间+状态守卫**删除（避免范围删除的 next-key 锁），空 id 集合 `WHERE 1 = 0` fail closed，候选超批/非法 id 在删除前失败，服务拒绝在已有事务中运行（各 mapper 独立提交），时间阈值一律由 SQL 计算（`NOW(3)`，不新增 JVM 时钟阈值；DB 时钟/会话时区风险仍在）；迁移 `database/migrations/t15-video-interaction-cleanup-indexes.sql`（`(state,completed_at,id)` / `(last_counted_at,id)`，镜像 `database/feibijiubi.sql`）是开启硬前置：两条候选查询带 `FORCE INDEX`，缺索引明确报 1176 而不是静默全表扫描。禁止 PENDING/`completed_at IS NULL`/`last_counted_at IS NULL`，不碰 outbox/consumed/Redis 证据，无新 HTTP 接口。语义改为“记录还在即重放、记录被删后同一 requestKey 按新请求处理（仍受 30 分钟窗口）”，清理不是 30 天到点 TTL。证据：定向纯单测 **33 tests，0 失败/错误/跳过**（`VideoInteractionCleanupServiceTests` 19 + `VideoInteractionCleanupContractTests` 9 + `VideoInteractionCleanupRetentionTests` 2 + `VideoInteractionCleanupSchedulingWiringTests` 3，`/tmp/t15-cleanup-target2-unit.log`）；普通回归 **744 tests，0 失败/错误，214 skipped**（`/tmp/t15-cleanup-wiring-full.log`，其中 `VideoInteractionCleanupIsolatedTests` 13 例整体 skip）。**未运行**任何隔离 IT/scale/容器/DB/Redis/迁移，opt-in `VideoInteractionCleanupIsolatedTests` 只编译；生产死锁重试、容量/压测、服务端下发/签名访客标识、旧客户端兼容窗口、生产防刷验收仍欠；两表清理不使用 T14 的 90/275 天政策。T15 仍未验收、不勾选。教程 `docs/tutorial/video-interaction-idempotency.md`（§12 为本切片） |
| [ ] | 16 / T16 | P2 | 故障演练、端到端压测与分阶段上线 | 前述实施项验收；延期项须记录风险 | T16-B0 同步准入测试环境基线已测；整项未完成 | 用户本轮限定为测试环境基线，不做生产验收。父代理 2026-10-25 实跑 `VideoInteractionAdmissionBaselineIsolatedTests`：3 tests，0 failures/errors/skipped，日志 `/tmp/t16-b0-parent-baseline.log`。单视频/十视频各200次同步计数，100次重放，预热排除，非HTTP/无MQ/非异步端到端；未删除或迁移数据。详见 `video-interaction-test-baseline.md`，不得重复成功测量冒充新证据；故障、并发容量及上线未验收 |

执行默认按表中顺序推进，每项单独提交。依赖不是“必须全做完才允许修一个小问题”：T04 可在 T01 后作为独立小补丁提前交付，T11 可在 T06 后提前交付，但必须记录调整原因。不得为了吞吐提前跳过 T02/T03。

## 3. 通用验收口径

### 3.1 正确性不变量

对一个受控测试视频，定义初始统计为 B，合法业务事件集合为 E：

- 最终 DB 统计 = B + E 中应生效事件的净增量。
- 重投相同 eventId 不改变最终结果；同 eventId 不同内容必须拒绝并可追踪。
- 所有可自动恢复事件最终到达 FLUSHED；不能靠转人工修复让测试看似“队列清空”。
- 正常收敛后 Redis current 与 DB 一致；若按设计允许降级/重建中暂时落后，必须验证恢复后的最终一致。
- 单批落库失败必须同时回滚统计更新和 FLUSHED 标记。
- 合法取消操作不能被永久丢弃、清零或误判为人工修复。
- 所有不能安全自动恢复的事件均进入可查询、有原因、有审计的处置路径。

测试期 oracle 使用独立维护的业务输入/期望净增量，不能只拿两份可能同时错误的统计互相比对。生产对账另行区分：点赞/收藏可核对关系表；播放/分享若无独立行为事实或归档，只能验证事件账本一致性，不能声称验证真实行为数量。

### 3.2 测试环境和证据

未来执行时，只使用独立 `compose.test.yaml` 项目：MySQL 13307、Redis 16379、RabbitMQ 15673/15674。不得合并正式 compose、复用部署卷或连接正式端口，不对正式 Redis 执行 FLUSHALL。

计划中的验证命令（本次不执行）：

```bash
# 无需基础设施的测试
./mvnw '-Dtest=*Tests,!BackendApplicationTests,!VideoStatusIsolatedIntegrationTests' test

# 独立基础设施及现有隔离集成测试
# 新增测试类后同步扩展 -Dtest 列表，避免只运行旧测试。
docker compose -f compose.test.yaml up -d --wait
./mvnw -DvideoStatusIT=true -Dtest=VideoStatusIsolatedIntegrationTests test

# 仅停止独立测试环境，保留数据
docker compose -f compose.test.yaml stop
```

全量 `./mvnw test` 只在确认 contextLoads 不会访问非测试资源后运行。每项证据记录：commit、环境版本/SQL mode、命令、测试数、失败数、重现时序、关键 DB 状态与计数、Redis 结果、MQ ready/unacked/重试/DLQ 数量。不得复制凭证。

### 3.3 默认测试门槛

以下为拟定的隔离测试预算，不是生产 SLA；T01 固定测试硬件和数据规模后确认，调整必须留原因，不能因失败临时放宽：

- 确定性并发测试使用 latch/barrier/故障钩子，不依赖碰运气的 sleep；关键时序重复 100 次，0 次计数错误、0 次合法事件误隔离。
- 无故障、单视频 1,000 条事件且无生产限速时，停止输入后 60 秒内收敛。
- 依赖恢复后，1,000 条安全可恢复积压在 180 秒内收敛；若退避配置使该目标不可能，先明确新预算再执行，不隐瞒等待窗口。
- 不以“主队列为空”作为成功标准；同时核对 DB、Redis、事件状态和各队列。
- 所有故障注入只操作独立测试资源，测试失败应保留诊断证据，不自动清除失败事件掩盖问题。

## 4. P0：先保证结果正确

### T01：建立可验证的基线

**步骤**
1. 阅读现有协议和测试，列出已有覆盖，避免重复实现。
2. 固定计数、幂等、事务、状态迁移、门闩、重放年龄等不变量。
3. 在测试环境加入可控阻塞点，复现以下顺序：+1 Redis 成功但状态提交暂停，-1 完成状态提交，随后触发 flush。
4. 复现 SQL 下溢、markSent 回写失败、丢 dirty、残留门闩；区分确定缺陷与不成立假设。
5. 记录默认配置、表结构、版本和测试结果，不变更业务行为。

**产物**：故障用例/测试清单、基线报告、不成立结论的更正说明。

**验收**：现有可运行测试基线清楚；每个 P0 问题有可重复失败证据或明确“不成立”说明。新缺陷测试不得被长期禁用后宣称已修复。

### T02：SQL 非负守卫与计数类型

**涉及位置**：`VideoStatusMapper.xml`、`VideoStatus`、相关 VO/converter/Mapper、Redis 解析、schema/migration。

**步骤**
1. 实测 0 + (-1)、1 + (-1)、正向累加、净零批次及边界数值；记录 MySQL 8.4 与部署版本/SQL mode 差异。
2. 选择无下溢的条件判断或明确的数值类型方案。不能未经范围分析就全面 `CAST AS SIGNED`，UNSIGNED 可表示的上界大于 Java Long。
3. 明确应用支持范围（建议 0 到 Long.MAX_VALUE，并验证累计加法溢出）；已有超过此范围的数据必须先盘点。
4. Java 计数链路统一 Long，检查聚合返回值、个人获赞总数、JSON 和前端大整数约定；vid 等标识无需跟着修改。
5. 区分“行不存在”“暂不满足非负条件”“数值溢出”“连接故障”，移除解析失败静默返回 0 的行为。

**验收**
- 0 + (-1) 不修改数据，得到明确可分类结果；不能依靠异常被无限重试。
- 1 + (-1) 正确变为 0，净零批次仍能正确完成事件状态迁移。
- 至少用 3,000,000,000 验证 DB→Java→响应与 Redis→Java→响应不溢出、不变 0。
- 上限溢出被明确拒绝并可诊断，无部分落库或部分状态提交。
- 若改响应类型或数据库结构，提供兼容性说明、增量迁移与回滚策略，不只修改初始化 SQL。

### T03：解决负增量落库顺序窗口

**步骤**
1. 使用 T01 用例确认状态提交乱序，而不是仅测试 MQ 消息先后到达。
2. 先制定错误分类：依赖事件未确认、真正数据不一致、未知类型、统计行缺失、数据库暂时不可用。
3. 选择并记录最小正确方案：对可能暂态的负净增量延后刷库并触发状态核查/补偿；或引入能证明正确性的序列/水位方案。不默认恢复所有消费的全程 vid 锁。
4. 若有暂态重试，加入 next-attempt/退避或等价调度控制，避免每 500ms 热循环；超时升级不能只凭一次非负失败就永久隔离。
5. 覆盖多个字段混合批次、批次拆分、重复投递、status0 长时间未推进、重建与 flush 并发。

**验收**
- 指定 +1/-1 窗口重复 100 次，最终 DB=Redis=0，两个合法事件均 FLUSHED，无误入 status3。
- +1 最终确认后，先前延后的 -1 自动收敛，无需手工补数。
- 当前负增量始终无法证明合法时，不允许篡改为 0；超过预算进入告警/受控处置。
- 保留正增量优先策略的适用性说明，不把它当作完整顺序保证。
- 原有无锁快路径、重建门闩、重复消费、并发 flush 测试无回归。

### T04：配置契约与运行角色

**步骤**
1. 接通 `outbox-relay-enabled`，或明确删除该配置并同步使用说明；优先保留独立停投能力。
2. 明确 async、relay、listener、flush/recovery 的职责矩阵及单实例/多角色部署方案。
3. 校验批量大小、时长、重试次数、安全重放年龄与 processed TTL 等取值。
4. 定义运行中切换 async 的维护流程：禁止在有未处理事件时直接切到同步更新基线而不协调重建。
5. 检查配置默认值与实际 application.yml、项目说明之间的偏差。

**验收**
- relay=false 时不领取、不发布，但允许按既定职责继续消费与落库。
- 四类角色组合有配置测试；单机未配置落库执行者有明确诊断，多角色合法组合不被误拒。
- 不合法参数在启动期给出可理解错误；停投/停刷/恢复流程有操作步骤。
- 同步/异步切换演练后无重复计数、遗漏或旧缓存覆盖新基线。

**P0 阶段出口**：T01—T04 全部验收通过；未确认 SQL 语义和负增量窗口前，不开展吞吐重构。

## 5. P1：让系统在故障后能恢复、能定位

### T05：最小可观测能力（先于新增补偿）

**步骤**
- 接入项目适配的指标方案，至少包含 Outbox 各状态数量/最老年龄、消费 status0/1/3 数量/最老年龄、发布和落库成功/失败、重试/DLQ、调度延迟、vid 锁等待、重建耗时和失败。
- 记录事件从创建到 FLUSHED 的端到端延迟；eventId/vid 放日志或查询接口，不作为无限基数的指标 label。
- 门闩停留时间需新观测手段或元数据设计，现有无 TTL 字符串本身不能提供创建时间；不得为观测而给门闩加自动过期。
- 明确指标采样成本，避免每次抓取都对无限增长大表全表计数。

**验收**：人为制造一条 Outbox 失败、一条 status0 超时、一条 status3、一组 pending 积压，均能在约定采样/告警窗口内定位；指标采集不改变事件状态，无敏感数据输出。暂以最老待处理 >120 秒、关键调度无进展 >30 秒作为测试告警阈值，生产阈值由 T16 确认。

### T06：调度隔离与执行预算

**步骤**
- 为投递、落库、恢复建立明确的独立执行资源或经过容量约束的调度方案。
- 限定线程数、排队长度、单轮批量及最长处理预算；明确多实例下由现有租约/vid 锁协调。
- 配置线程名、拒绝策略、优雅停机和任务异常隔离，避免只增加线程数而产生重叠任务失控。

**验收**：将发布 confirm 人为阻塞 30 秒，已经消费成功的事件仍能在 10 秒测试预算内落库，恢复调度仍有心跳；阻塞一个视频不应无限阻塞其他视频；两个实例同时工作仍保持单次有效落库。

### T07：Outbox 发布和租约

**步骤**
1. 分离 JSON/事件非法、发布失败、confirm 不确定、markSent 回写失败。
2. 检查 markSent/markPending/markFailed 更新行数；保留租约 token 的 CAS 防旧 owner 回写。
3. 单条异常隔离，不因 catch 内数据库再失败而丢掉整批执行机会；剩余任务可明确重试或等待租约恢复。
4. 调整预领取批次/续租方案，租约预算覆盖“批内等待 + confirm + 回写”，而非只覆盖单条发布耗时。
5. 分清租约恢复次数和发布失败次数；上限耗尽形成可处置终态，而非无告警停止。

**验收**
- MQ 已确认但 DB 回写失败：可重投但最终只生效一次，Outbox 最终 SENT。
- 原 owner 超时、另一实例接管后，旧 token 无法修改新 owner 状态。
- 一条 markSent/markPending 抛异常不使其他已领取事件永久卡住。
- 模拟整批慢发布和反复重启：租约回收有界、无丢事件；最终失败原因和计数含义明确。
- FAILED 的管理处置入口在 T10 完成前，已有可执行、受审计的临时运维说明。

### T08：消费重试与 ACK 边界

**步骤**
- 定义暂态异常分级退避（如 10s/30s/120s，具体由配置与恢复预算确定）；不在同一个 TTL 队列混用任意过期时间造成队头阻塞。
- 转发失败采用受控暂停/退避/限速，不能用 consumer 线程无限 sleep，也不能无限立即 requeue。
- 明确处理成功 ACK 失败、retry/DLQ 发布成功但 ACK 失败、发布结果不确定的行为。
- 保留至少一次投递语义，不承诺跨 RabbitMQ 与 DB 的 exactly-once；通过幂等吸收复制，通过限流控制放大。

**验收**：Redis 不可用 120 秒后恢复，事件仍能按策略安全收敛或进入可重放 DLQ，不静默消失；转发失败时在测试配置的最大尝试率内运行，无持续无界热循环；ACK 故障不导致双计数，正常成功消息不被误转 DLQ；非法 payload 不无限重试。

### T09：status0 安全补偿

**步骤**
1. 接通超时 RECEIVED 扫描，按年龄/游标和批次领取，多实例具备租约或等价互斥。
2. 原 eventId、原语义内容重放；不绕开 register、指纹或年龄校验。
3. 设计事务边界：领取事务不能持有数据库行锁等待 MQ confirm；明确发送后记录失败的重复恢复方式。
4. 新鲜/正在处理事件不频繁抢救；设置补偿冷却、并发上限和重放记录。
5. 超过安全年龄、processed 证据异常或数据丢失边界不盲目补发，升级为受控修复。

**验收**
- Redis 已应用但未 mark status1：补偿后只计一次，最终 FLUSHED。
- 未应用 Redis 就中断：补偿后补上一次，最终 FLUSHED。
- 两个补偿实例与原消费者并发，无重复计数；补偿发送后崩溃可恢复。
- 安全年龄以内/边界/以外均有测试；超龄事件可查询且不会直接重新累计。
- current 与 processed 同时丢失、processed 单独丢失分别测试并明确保证边界，不能把不确定情况自动视为“从未应用”。

### T10：失败事件处置与对账

**步骤**
- 先形成受审计服务能力，再决定使用管理员 GET 查询/POST 操作接口或受控命令工具。
- 支持 Outbox FAILED、DLQ、status3 的分页查询、详情、原因、预检查、受控重放、人工确认忽略与审计日志。
- 明确不同状态修复动作：不允许简单修改 status 后重新发一个新 eventId。
- 对账区分业务关系真值、事件账本、DB 基线、Redis 派生值；若 Redis 修复需要强制重建，T12 前先限制相关操作或进入维护流程。
- 修复基线必须遵守 vid 锁/门闩协议，并处理已在 Redis 生效但未落库的增量。

**验收**：未授权用户不能操作；同一修复请求重复提交不会重复累计；每次操作记录操作者、原因、前后状态、关联 eventId；修复后 DB/Redis/事件账本可解释且收敛；不能安全修复的请求被拒绝并给出原因，不要求运维直接改 SQL。

### T11：dirty 公平恢复与失败调度

**步骤**
- 用稳定游标轮转或分片替代永远从最小 vid 开始的固定 LIMIT。
- 游标跨轮推进并能回绕，多实例/重启丢游标只影响效率，不影响正确性。
- 引入失败视频退避；仍保留新事件 SADD 和 DB 反哺，不引入并发 SREM 误删。

**验收**：准备 250 个 pending 视频、扫描批量 100，其中前 100 个持续失败、后 150 个丢失 dirty；不超过 4 个完整恢复轮次均被扫描到并获得处理机会，健康视频可收敛；游标重启/回绕不漏视频；消费者并发新增 dirty 不被删除。

**P1 阶段出口**：依赖短时故障、消费者中断、Outbox 回写失败、dirty 丢失均可在预算内自动恢复；超出自动恢复边界的事件能够告警、查询和安全处置。

## 6. P2：控制长期成本与提供产品收益

### T12：有界重建与受控强制修复

**步骤**
1. 先测候选数量、单条 JSON 成本和 Redis 往返次数，再决定批量 EXISTS/流水线等方案，避免直接对非字符串类型使用不合适的批量读。
2. 在一致快照/水位协议下分页聚合或分批读取，保持锁和门闩协议；禁止加 LIMIT 后忽略剩余候选。
3. 定义超预算行为：中止但不发布半份快照，保留可恢复状态并告警。若要释放锁后继续，必须先设计跨批一致性协议。
4. 区分“缺失初始化”与“已存在但错误的强制重建”；后者需管理员权限、审计、并发协调。
5. 保留 token-owned release、无 TTL 门闩、新 owner 接管能力。

**验收**：分别在 0/1,000/100,000 候选规模测试，结果不漏不重；报告峰值内存、Redis 往返、P95 重建时间与门闩持续时间；Redis 查询不再每条独立 RTT；旧 owner 不能覆盖新结果；中途失败无半初始化；超过预算可诊断且后续能接管。

**T12-A 紧耦合小切片（2026-10-23，候选，未父验收）**

- 已实现：`VideoStatusRebuildServiceImpl#classifyConfirmedPending` 按快照顺序以固定 256 条一批
  （`PROCESSED_EVIDENCE_BATCH_SIZE`）切分；每批只为 `process_status=0` 候选构造本批 key 并调用
  `RedisUtils.hasKeys`，一次 pipeline `EXISTS` 取回逐 key 结果，按位置回填。`process_status=1`
  已确认应用，不再发起任何 Redis 查询。批内没有 status0 候选时不发请求；不构造全量候选 key 集合。
- fail closed：`hasKeys` 对 null 返回、条数不符、非布尔/非 0-1 元素抛 `RedisOperationException`；
  分类路径额外拒绝 null 元素（未知不等于“未应用”）。异常发生在 `initializeRedis` 之前，
  因此不发布半份初始化，`finally` 仍用同一 token 释放门闩；vid 锁、无 TTL 门闩、token-owned
  release 与初始化脚本 keys/args 形状全部不变。
- 明确未做：`selectRebuildCandidates` 仍一次全量加载该 vid 的 0/1 候选（无分页/流式/水位协议；
  这一点已由下面的 T12-B 候选替换为 keyset 分页）；
  0/1,000/100,000 规模的内存、Redis 往返次数、P95 重建与门闩持续时间未测量；超预算中止与告警、
  “已存在但错误”的强制重建（管理员权限/审计/并发协调）与任何重建 HTTP 接口均未实现。
  任意 Redis 数据丢失、进程崩溃与生产容量仍不在本切片保证范围。
- 证据：`VideoStatusRebuildProcessedEvidenceTests` 9 例（0 候选、仅 status1、混合状态、600 候选跨
  256 边界、跨批重复 key、Redis 异常、返回长度异常、null 结果、门闩/初始化协议顺序与形状）；
  `RedisUtilsTests` 新增 8 例 pipeline 用例；隔离用例
  `VideoStatusIsolatedIntegrationTests#rebuildCountsProcessedEvidenceAcrossBatchBoundary` 用真实
  MySQL/Redis 验证 300 条 status0（跨批次）+ 40 条 status1（重建前删除 processed key）得到
  `100 + 40 + 180 = 320` 且事件状态不变。普通单测 `474 tests, 0 failures/errors, 142 skipped`。
  这些只是候选证据，T12 整项仍不能勾选。

  时间线澄清（保留历史汇总，不互相覆盖）：T12-A 当时确实只新增了 **8 例** `RedisUtilsTests`
  pipeline 用例；后续加固（T12-B 同一窗口）又补了 **4 例**严格回复类型用例（只接受整型 0/1，
  拒绝小数/NaN/超范围整数/未知类型），所以该类当前累计 12 例新增 pipeline 用例。
  “8 例”是 T12-A 切片的历史数字，不应改成 12；“12 例”是当前类总数，两个数字并存。

**T12-B 紧耦合小切片（2026-10-23，父已复跑验证，T12 整项未验收）**

- 已实现：`selectRebuildCandidates(vid)`（无 LIMIT 全量加载）替换为
  `selectRebuildCandidatesAfter(vid, afterId, limit)`；`VideoStatusRebuildSnapshotServiceImpl#load`
  在**同一个** `@Transactional(readOnly=true, isolation=REPEATABLE_READ)` 内先读基线、再按
  `id > afterId` 逐页读到空页/短页，页大小 `rebuild-candidate-page-size`（默认 1000，
  上限 `MAX_REBUILD_CANDIDATE_PAGE_SIZE=1000`），不使用 OFFSET。基线与所有页共享同一份
  InnoDB 一致性快照，分页不跨事务。
- SQL 显式 `FORCE INDEX (idx_video_status_consumed_vid_id)`。不加提示时优化器选
  `idx_video_status_consumed_pending` 的两个 `process_status` 区间 + `Using filesort`，
  每页都要重读该 vid 的全部候选（实测同一 vid 300 条候选、`LIMIT 100`：无提示读 300 条索引项
  并排序，加提示只读 140 条）。该 `(vid,id)` 索引由 T10 对账迁移提供并在 `database/feibijiubi.sql`
  镜像，**本切片无新迁移**；索引缺失时查询报错而不是静默全表扫。
- 候选仍返回当前 DTO list，因此上界是 `rebuild-max-candidates + 1` 条（默认 100000），
  **不是常数内存**。超过上界抛 `RetryableMessageException`，绝不返回截断快照；页大于请求大小、
  返回 null、游标不前进同样失败。快照读取预算 `rebuild-snapshot-budget-ms`（默认 10000）用
  `System.nanoTime()` 在页间与逐条转换前检查，超预算失败但**不中断已发出的 SQL**。
- 不变：vid 锁、无 TTL 门闩、`finally` 同 token 释放、初始化脚本 keys/args、T12-A processed
  证据批次、计数聚合与 processed 状态语义。两个新失败点都在 `initializeRedis` 之前，
  因此无半初始化，下一次重建用新 owner token 接管。
- 未做：“已存在但错误”的强制重建（权限/审计/并发协调）、重建 HTTP 接口、deadlock 专项、
  跨批水位协议、生产容量/性能验收。0/1,000/100,000 规模本轮已用 opt-in 测量类跑出诊断数字
  （见下），但每规模 3 样本、单机单 JVM，不构成验收，也不外推到生产。
- 证据：`VideoStatusRebuildSnapshotPagingTests` 16 例（0 条、恰好跨页、整倍数页、上限=成功/上限+1
  失败、游标严格前进、游标不前进、超页大小、null 页、末行缺 id、页内 id 未严格递增、
  第二页超页大小、页间/转换期间超预算、RR 注解、不可变列表）；
  `VideoStatusRebuildProcessedEvidenceTests` 新增“快照失败无初始化+下次接管”；
  `VideoStatusRuntimePropertiesContextTests` 新增三键绑定/默认值/非法值；
  `VideoStatusIsolatedIntegrationTests#rebuildPagedCandidatesMatchBaselineInsideOneRepeatableReadSnapshot`
  （1000 条候选、另一线程在第一页后提交一条新候选，证明两页同连接同 RR 快照且并发行不可见）与
  `#rebuildOverCandidateLimitPublishesNothingAndNextAttemptTakesOver`（超上限无半初始化、门闩释放、
  下次接管成功）为真实 MySQL/Redis 用例。普通单测 `497 tests, 0 failures/errors, 144 skipped`
  （`/tmp/t12b-unit-final.log`）；`VideoStatusIsolatedIntegrationTests` 全类 `20 tests, 0 failures/errors,
  1 skipped`（`/tmp/t12b-it-class-final.log`）。教程：`docs/tutorial/video-status-rebuild-paging.md`。
  这些只是候选证据，T12 整项仍不能勾选，也不是生产验收。
- 父代理复跑（2026-10-23）：普通回归 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test`
  **497 tests，0 失败/错误，144 skipped**（`/tmp/improvement-parent-unit-final.log`）；
  opt-in 隔离 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusIsolatedIntegrationTests,VideoStatusStatus3ReplayIsolatedTests' test`
  **31 tests，0 失败/错误，1 skipped**（20 例全链路 + 11 例 B1，`/tmp/improvement-parent-t12b-it.log`）。
  本轮再补行级 id 校验（页内每行 id 必须存在且严格递增）、4 例分页用例与规模测量类后复验：
  普通回归 **504 tests，0 失败/错误，147 skipped**（`/tmp/t12b-worker-unit.log`）；
  `VideoStatusIsolatedIntegrationTests` **20 tests，0 失败/错误，1 skipped**（`/tmp/t12b-worker-it.log`）。
- 历史并发问题保留：T10-B1 记录过的两个并发用例
  （`concurrentColdStartAndDuplicateDeliveryConverge`、`concurrentFlushAndConsumptionPreserveAllEvents`）
  曾报 2 errors，而父代理本轮与本轮复验的隔离运行均通过。这是**间歇性**问题，根因未定位、未修复，
  不因本轮通过而写成“已解决”，历史记录也不删除。
- 部署硬依赖：T12-B 上线前必须先执行 `database/migrations/t10-video-status-reconciliation-indexes.sql`。
  `selectRebuildCandidatesAfter` 的 `FORCE INDEX (idx_video_status_consumed_vid_id)` 让该索引从
  “T10 对账更快”变成“冷缓存重建的硬依赖”：缺索引时 MySQL 直接报 1176，重建在 `initializeRedis`
  之前失败（无半份 current，门闩由 `finally` 释放），消费链路把它当 `DataAccessException` 重试→DLQ。
  候选超 `rebuild-max-candidates` 同样重试→DLQ，**没有自动修复**：不自动截断、不自动提高上限，
  也没有强制重建入口，必须运维按错误信息停写/调配置并重启。
- 规模测量（新增 opt-in `VideoStatusRebuildScaleIsolatedTests`，默认 skipped，`-DvideoStatusScale=true`）：
  0/1,000/100,000 候选各 3 次冷重建，预设 oracle（`current = 100 + 候选数`，并交叉核对 DB 行数、
  去重 eventId、`SUM(delta)`、其余七计数为 0、事件状态未变）每次重复都断言。实测
  （`/tmp/t12-scale-full.log`，52.98 s）：0 → 18/18/22 ms；1,000 → 164/190/379 ms，4 次 pipeline /
  1,000 条 EXISTS；100,000 → 7,789/8,143/9,649 ms，391 次 pipeline / 100,000 条 EXISTS。门闩持有与
  重建耗时几乎相同。恢复轮在受限堆（`-DargLine=-Xms64m -Xmx512m`）下串行复跑，0 → 17/19/24 ms，
  1,000 → 179/184/405 ms，100,000 → 5,349/5,609/6,152 ms，仍是 391 次 pipeline / 100,000 条 EXISTS
  （`/tmp/t12-scale-recovery.log`，43.76 s，0 失败/错误），三轮 100,000 的 9 个样本落在 5.3–9.6 s。
  pipeline 次数严格等于 `ceil(候选数/256)`。**限制**：单机单 JVM、无并发/故障注入、
  每规模 3 样本（P95 仅诊断，输出 `p95=n/a`）；测量把读取预算放宽到 1 小时，heap 池峰值之和只是
  上界近似（不是候选 retained size）。**风险**：默认 `rebuild-snapshot-budget-ms=10000` 与默认 100000
  上限同一量级，接近上限规模的冷重建可能触碰预算；未在默认预算下跑 100,000，故不断言必然失败，
  生产前必须按实际规模/硬件重估。

**T12-C1 受控替换内部原语（2026-10-23，候选，未开放、未父验收）**

- 新增 `src/main/resources/lua/video-status-replace.lua` 与 `RedisScriptConfig#videoStatusReplaceScript`
  （`DefaultRedisScript<String>`），keys/args 形状与 `video-status-init.lua` 完全一致，但语义相反：
  init 要求 `current` **不存在**，replace 只允许 `current` **已存在且完整**（冷 miss 返回
  `CURRENT_MISSING`，绝不顺手初始化）。
- 校验顺序固定为：门闩 token → current 类型 → dirty/hot 类型 → current 无 TTL → vid → hot 有限
  → 八计数 canonical 且 ≤ `Long.MAX` → `hasPending` → 已有字段集合 → 已有取值 → vid 一致。
  拒绝码：`STALE_REBUILD`、`CURRENT_MISSING`、`INVALID_REDIS_TYPE`、`CURRENT_HAS_TTL`、
  `INVALID_ARGUMENT`、`COUNTER_OUT_OF_RANGE`、`UNEXPECTED_FIELDS`、`CURRENT_INVALID_VALUE`、
  `VID_MISMATCH`。**所有校验都在第一条写命令之前，任何拒绝都是零写入。**
- 未知字段不用 `HKEYS`（无界）：先 `HLEN` 与固定上限 9 比较，再对九个已知字段逐个 `HGET`，
  命令数固定为 10。已有取值也必须是合法 current（vid 非零、八计数 canonical ≤ `Long.MAX`），
  否则 `CURRENT_INVALID_VALUE`——覆盖已损坏的 Hash 只会把损坏洗白。
- 写入：一次 `HSET` 写九个字段 → 仅 `hasPending=true` 时 `SADD dirty`（`false` **不** `SREM`，
  保守保留脏标记）→ `ZADD hot`。三条写各自 `pcall` 包裹，失败返回阶段码
  `REPLACE_HSET_FAILED` / `REPLACE_DIRTY_FAILED` / `REPLACE_HOT_FAILED`，成功返回 `REPLACED`。
  **不承诺回滚**：Lua 串行执行不等于事务，运行时错误（noeviction OOM、ACL 拒绝、实例故障）下
  前序阶段可能已写入，阶段码表达“执行到哪一步、哪一步未知”；也不用“读回相等”证明执行成功。
- 不做：不 `DEL` current、不改 `processed` 幂等 key 及其 TTL、不释放门闩（仍由调用方同 token 释放，
  旧 token 永远 `STALE_REBUILD`）；无 unproven 模式、无放宽开关。
- **未开放**：没有 HTTP 入口、没有 Java 业务调用者、没有注册自动调用（`grep videoStatusReplaceScript`
  只命中配置类）。强制重建仍需管理员权限、审计表/幂等 operationId、与 vid 锁/门闩/批量刷库的
  并发协调，以及崩溃后“已替换一半”的处置流程；完整性校验（与 MySQL 基线/事件账本对账）也不在本原语内。
- 证据：opt-in `VideoStatusReplaceLuaIsolatedTests` **24 tests，0 失败/错误/跳过**
  （`/tmp/t12c1-replace-it2.log`，真实 Redis 16379，脚本经 `RedisScriptConfig` bean 执行）覆盖正常替换
  九字段+hot、`pending=false` 不 `SREM` 且不动其它 vid、processed 与 TTL 不变、冷 miss/类型/TTL/
  未知字段/缺失字段/损坏取值/旧 owner/缺门闩/vid 不一致/非法参数、`Long.MAX` 与 `2^53+1` 精确、
  `Long.MAX+1` 越界，以及 ACL 只对专用临时用户禁用 `HSET`/`SADD`/`ZADD` 的**真实运行时部分失败**
  （分别验证阶段码与“Hash 已写、dirty 未写、hot 未写”的既成事实）。普通回归
  `528 tests，0 失败/错误，171 skipped`（`/tmp/t12c1-unit3.log`，较上轮 504/147 正好多出本类 24 例 skip）。
  教程：`docs/tutorial/video-status-replace-primitive.md`。
- 测试环境注意：本机 JDK 21 下 Mockito inline mock maker 需要 `-Djdk.attach.allowAttachSelf=true`，
  否则 ByteBuddy 的外部进程 attach 在内存/提交受限时失败，整轮回归会误报大量
  `Could not initialize plugin: interface org.mockito.plugins.MockMaker`（与被测代码无关）。
- **T12 仍不能勾选**：强制重建入口（权限/审计/并发协调）、崩溃恢复、完整性校验与生产容量验收仍缺。

### T13：读路径与热榜取舍

**步骤**
- 明确选择并记录：A. 详情读实时 Redis，故障读 DB 并允许暂时落后；B. 保持 DB 最终统计，并给出延迟目标。
- 若选 A，统一服务入口、缺失/脏字段/Redis 超时/重建期间读语义，避免每次 miss 同步触发昂贵重建；若选 B，不把 Redis 查询未接入作为未完成 bug。
- 同时检查详情、列表、用户获赞数的口径，不能只接一个接口却宣称全站实时。
- 热榜 ZSet 确定接入、延期保留或停止写入；若保留，定义排序/删除/历史热度重建规则。

**验收**：所选模式有 API/产品说明；正常、miss、Redis 故障、损坏字段、重建中各有测试；不会解析失败返回虚假 0；降级时延有界；热榜保留有明确消费者或明确成本理由。

**T13 模式 B 决策（2026-10-23，候选，未父验收）**

- **决策**：在计划允许的 A/B 中选 **B**。面向用户的统计读继续以 MySQL `video_status` 为唯一来源；
  不引入 Redis 实时读，不在 read miss 同步触发重建，不为读路径放宽 DB 错误守卫，也不为“测试覆盖
  Redis”给读路径新增 Redis 调用。因此“Redis 查询未接线”是本决策结论，不是未完成 bug；要改成 A
  必须先有新的书面决定，并为 miss / 坏字段 / 重建门闩 / Redis 超时四种状态定义读语义。
- **读源证据（代码核查）**：`GET /api/videos/{vid}` → `VideoStatusMapper#selectByVid`（单行，
  7 项计数，无 `unlikeTimes`）；`GET /api/videos/feed` → `VideoMapper#selectFeed`（`video ⋈ video_status` 同一条 SQL 投影，
  只有 `playTimes`/`commentTimes`，无逐条补查）；`loveCount` →
  `VideoStatusMapper#countLikeByUid`（`COALESCE(SUM(like_times),0)`）；管理员详情 →
  `selectByVid`（8 项，不做可见性过滤）。四个入口都不含任何 Redis/Redisson 依赖，测试用反射把这
  一点固定住，因此 `current` 的 miss/损坏/门闩/故障在用户可见链路上不可达。
  Redis `current` 的读取方**不止一个**，因此不再有“唯一的 Redis 统计读”这种说法：
  `VideoStatusServiceImpl#getByVid` 是**无调用方**的只读门面（miss 回落 DB 且不回填、不取锁、
  不重建；坏字段抛 `VideoStatusCounterRangeException`；Redis 故障抛 `RedisOperationException`
  不回落 DB、不返回 0；不读门闩），不能叫“用户读入口”；另一个读取方是管理端单视频只读对账
  `VideoStatusReconciliationServiceImpl#readRedisSample`（一次 `multiGet` 采样八个字段做诊断，
  不写 Redis/MySQL、不领租约、不改事件状态，输出不进入任何用户可见统计响应）。
- **口径差异（已逐接口写入 `docs/api.md`）**：详情=已过审+未删除（私密仅作者），缺行 `500
  视频统计数据异常`；Feed=已过审+公开+未删除，缺行时整条被 JOIN 丢弃（不显示 0）；`loveCount`
  只聚合已过审+公开+未删除视频的 `like_times`，不减 `unlike_times`，空集为 0；管理员详情不筛
  可见性，缺行 `404 视频状态为空`。`unlike_times` 只累计、无用户可见读接口。
- **延迟**：最终一致，**无健康时延 SLA**。正常量级“约 1～2 秒”由配置推算
  （`outbox-fixed-delay-ms=1000` + `flush-fixed-delay-ms=500` + 处理），不是实测分布；失败重试
  10/30/120 s、`round-budget-ms` 预算内堆积会把延迟推到分钟级。T05 的 15 s 采样、
  `PENDING_ALERT_AGE_MILLIS=120 s`、`SCHEDULER_ALERT_AGE_MILLIS=30 s` 只是诊断阈值，不是保证。
- **热榜 ZSet 延期保留**：`feed:hot:videos:v1` 有三处写入（`video-status-increment.lua` 每次应用
  事件 `ZINCRBY`、`video-status-init.lua` 重建 `ZADD`、未开放的 `video-status-replace.lua` 替换
  `ZADD`）、**零处读取**；无 TTL、删除视频不 `ZREM`、累计加权分无时间衰减；内存是 O(视频数) 而
  非 O(事件数)。当前用户可见收益为 0，保留只为“不动统计协议”；接入或删除需独立变更，先定义
  排序/衰减、删除、历史热度重建与分页。
- **测试证据**：新增 `VideoStatusReadConsistencyTests` **16 例**（纯 Mockito）：详情/Feed/获赞
  正常值（含 3e9、9e9 精确）、统计行缺失（详情 `500 视频统计数据异常`、Feed 由 JOIN 丢弃）、
  `DataAccessResourceFailureException` 透传（不返回 0/空页）；管理员详情 2 例（2026-10-28 补）：
  8 项计数逐项精确透传（`playTimes=2^53+1=9007199254740993`、`likeTimes=Long.MAX_VALUE`，私密视频
  也照样返回，证明服务层不做可见性过滤；SQL 无可见性条件由源码核查确认，不属于此 mock 测试的证明范围），以及统计行缺失时 `404 视频状态为空` 且 `userMapper`/
  `userFollowMapper` 零交互、`countVideoByUid` 从未调用；结构守卫（三个读服务不声明
  Redis/Redisson 依赖）；`getByVid` miss（回落 DB、不回填、不取锁、不重建）、门闩不被读取、坏字段
  显式异常且不 fail-open 到 DB、Redis 故障透传；VO 字段面（详情 7 项、Feed 2 项、无 `unlikeTimes`）
  与 Mapper XML 单个 `<select>` 块的 SQL 口径断言。既有 `VideoStatusCounterReadTests` 8 例继续覆盖
  命中/坏字段/vid 不匹配/miss，本项不重复改写成空测试。2026-10-28 定向收口运行（受限堆串行，
  `-DvideoStatusIT=false -DvideoStatusScale=false -DvideoStatusBaseline=false`）：
  `VideoStatusReadConsistencyTests,VideoStatusCounterReadTests` = **24 tests，0 失败/错误，0 skipped**
  （末次 `-o` 离线，`/tmp/t13-fix-target-unit2-A3BgFA.log`，同轮首跑 `/tmp/t13-fix-target-unit-E1ZD6a.log` 结果相同；`MAVEN_OPTS='-Xms64m -Xmx256m'`、
  `argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'`，未启动隔离 compose 环境，未跑
  `BackendApplicationTests`）。首版记录的普通回归 542 tests 为 2026-10-23 历史数字，本轮未重跑，
  不作为当前证据。
- **未做**：真实 HTTP 端到端 DB 可见延迟实测、生产容量、热榜读取/删除、`unlike_times` 用户可见读、
  `getByVid` 接线；用户详情 500 与管理员详情 404 的文案差异按原样保留（改文案会改线上返回值）。
  **T13 仍是候选、不能勾选**；T12（强制重建/生产容量）与 T10（DLQ/批量）的未完成项不因本决策改变。
- 教程：`docs/tutorial/video-status-read-consistency.md`。
- 关联记录：父代理复跑 T12-C1 窄范围用例
  `./mvnw -DvideoStatusIT=true -Dtest=VideoStatusReplaceLuaIsolatedTests test`（实际
  `argLine=-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true` 受限堆）= **24 tests，0 失败/错误/
  跳过**（`/tmp/t12c1-parent-it.log`）。这只是原语本身的窄范围验证，**不是 T12 验收**。

### T14：归档与容量治理

**已批准政策（2026-10-23，用户批准；仅为决策，尚未实现）**：成功终态 Outbox（`SENT=2`）与消费账本
（`FLUSHED=2`）在线保留 **90 天**，归档副本再保留 **275 天**（两段**起算点不同**，见下：只在归档不延迟时才近似“合计 365 天”，
归档延后时实际总保留更长，**不得**写成从事件创建固定 365 天）；DLQ **30 天内必须处置**
（处理 SLA，**不是 TTL**，未解决不自动删除、不配置自动过期）；操作审计保留 **365 天**；最大**普通**事件
重放窗口为事件发生后 **30 天**，超过转人工核查并拒绝普通重放；30 天以内仍须满足全部现有 age、
`processed` TTL 与证据守卫，T07/T09/T10 的拒绝条件一条都不放宽。T15 的 `video_interaction_request` /
`video_interaction_window` **不纳入**本政策（它们的保留期由 T15 自己的 30 天清理切片单独决定，见 T15 行：
request 自 `completed_at` 起 30 天、window 连续 30 天无计数，默认关闭，不是 TTL）；旧的"同一 `requestKey`
永不重复计数"表述已被 T15 决策**取代**为"记录还在即重放，记录被清理后同一 `requestKey` 按新请求处理、
仍受 30 分钟窗口约束"。**政策已批准、起算点已确认但无实现**：没有归档执行器、没有删除代码，30 天新守卫只有消费登记侧候选切片
（T14-S1），T14-A dry-run 依旧 `NOT_AUTHORIZED_FOR_DELETION`。**本轮用户确认的起算点与存储**（未新增日历日期）：在线 90 天从终态完成起算——
Outbox 用 `sent_at`（发布 confirm 成功后回写），消费账本用 `flushed_at`（批量落库事务内写入），**不是** `created_at` /
`consumed_at`；归档副本 275 天从**归档副本校验成功**起算（不是事件创建时间，也不从“在线 90 天结束”自动顺延，
归档延后时实际总保留可超过 365 天）；审计 365 天从**审计行创建**（`created_at`）起算；DLQ 30 天 SLA 从**首次进入
死信队列**起算，**重投不重置、未解决不自动删除**；归档落在**独立私有 COS 归档桶**，只有**文件完整性 + 记录数量
校验通过**才允许删除在线数据，**开发阶段只允许隔离测试存储**。

**字段核查结论（只读源码/schema，详见 `docs/tutorial/video-status-capacity-audit.md` §9.2）**：`sent_at` / `flushed_at`
都存在且语义正确，但 `sent_at` 写的是 **JVM 时钟**（`markSent` 传入 `LocalDateTime.now()`），两列**都没有索引**
（现有是 `(status, created_at, id)` / `(process_status, consumed_at, id)`）；`flushed_at` 在 T10-B1 重放（`resetRepairToReceived`）
会被清空，只能表示“最近一次落库完成”；**DLQ 首次入队时间完全不存在**（只有 durable 队列，无消费者、无 DB 表）；
**归档校验记录完全不存在**；`FileStorageService` 只能同桶操作、只能判存在、写不了 manifest，**不适合**归档。
**终态时间缺失的历史行不能猜成 `created_at`/`consumed_at`、不能回填伪时间，只能保守排除自动删除。**

**下一步最小可测试切片（建议、待父审，本轮不实现）**：S1 = 30 天旧消息拒绝的入口全覆盖（单一窗口判定被消费应用、
T07 Outbox 重放、T10-B1 status3 重放、T09 status0 补偿重发四处共用；注意消费侧现有唯一年龄守卫只在**重复/重投路径**
`handleReceived`、基准是 `consumed_at` 且**依赖账本行存在**，首次登记/首次应用无守卫，所以新守卫必须独立于账本行存在性；
**relay 不新增拒绝**）。**S1 现状（2026-10-24）：消费登记（T14-S1）与三个管理入口（T14-S2）均已落地候选切片，
relay 与自动重放/恢复路径仍不拒绝。** 原建议“30 天必须按严格小于 processed TTL 落地”**已被父决策推翻**：
该守卫是纯事件年龄上限、从不读 `processed` 证据，与 `redisEventTtlDays` 无先后约束。S2 = 归档时间字段 + 校验记录（下一轮才建
`(status, sent_at, id)` / `(process_status, flushed_at, id)` 索引与归档记录表，只做“可证明”，**不做执行器**）。
**命名提示**：本文件的“S2（归档时间字段）”与父代理本轮交付的 **T14-S2（30 天守卫接入三个管理入口）**
**不是**同一件事；归档侧 S2 仍未实现。

**生产清理前置（必须先实现并验收）**：旧消息硬拒绝或去重墓碑、仅终态、安全归档确认、限速分页断点重跑、归档后重放
旧消息不重复计数（含 30 天重放守卫本身）的演练与索引/锁影响验证。

**步骤**
- 估算日事件量 × 行/索引大小、processed key 数量 × 保留天数及 Redis 实测字节数。
- 最大重放窗口、DLQ 处理时限与审计期限已按上述政策确定；下一步是把它们落成可验收的实现，而不是继续等决策。
- 仅清理安全终态且满足保留窗口的数据；status0/1/3 与待处置 FAILED 不随普通历史数据清理。
- 若删除消费记录，必须保留足够的去重证据，或明确拒绝过期旧消息；“processed 还有 TTL”不是删除 DB 幂等证据的充分条件。
- 小批清理/归档、断点续跑、限速，验证索引与在线查询锁影响。

**验收**：演练归档中断后重跑无重复/漏归档；归档后重放旧消息不重复计数；待处理事件不被删；容量报告含预计耗尽时间和告警水位；验证 noeviction 下内存耗尽的告警和安全恢复，不以删除门闩腾空间。

### T15：防刷与业务幂等

**步骤**
- 先定义有效播放、重复播放窗口、匿名访客与登录用户、分享次数的产品语义。
- 使用组合维度和有限窗口限流，避免单纯 IP 封禁误伤共享网络；对存储的访客标识采取最小化原则。
- 区分请求幂等与消息幂等：同一请求重试不能生成无限个新的合法 eventId。
- 点赞/收藏/投币维持现有行锁、唯一键、余额守卫；不要替换成只有 Redis 去重。

**验收**：同一访问在定义窗口内重试只生成规定数量的事件，窗口外按规则计数；并发请求不能绕过限制；超限返回统一业务响应；限流 Redis 故障时的 fail-open/fail-closed 行为明确并有测试；合理流量不会被整站阻断。

**已交付：T15 第一阶段 DB 核心（2026-10-23 候选；第二阶段已接线，整项仍未验收、未上线、不勾选）**

> 本小节是**第一阶段历史记录**（保留原文），当前事实与第二阶段证据见下一小节。

- 产品语义（已批准）：同一 actor 对同一 vid 的同一动作，**滚动 30 分钟**内最多计一次；同一
  `requestKey` **永远只计一次**（重复提交重放原结果，含“被抑制”这个结果）；抑制不推进窗口；
  ⚠️ “永远只计一次”已被本节末尾的 **T15 30 天有界清理**决策取代：记录还在即重放，记录被清理后
  同一 `requestKey` 按新请求处理（仍受 30 分钟窗口约束）。本条保留为第一阶段历史原文。
  匿名 actor 用**随机 UUID 而非指纹**，DB 只存 `actor_type` + 规范化 64 hex `actor_hash`。
- 表：`video_interaction_request`（`request_key` 列级 `utf8mb4_bin` 保证大小写敏感的唯一键、
  state `0 PENDING/1 COUNTED/2 SUPPRESSED`、`event_id`、时间）与 `video_interaction_window`
  （`actor_type+actor_hash+vid+action` 唯一、`last_counted_at` **可 NULL = 从未计数**）。
  迁移 `database/migrations/t15-video-interaction-idempotency.sql` 幂等、只新增表，
  不 `ALTER`/`UPDATE`/`DELETE` 既有行；已镜像到 `database/feibijiubi.sql`。
- 流程：事务外幂等读（命中重放 / 形状冲突 409）→ 同一事务内 INSERT 占位 → 窗口 ODKU
  保证行（自赋值，绝不初始化 `last_counted_at`）→ `SELECT ... FOR UPDATE` → DB `NOW(3)`
  守卫更新（`last_counted_at IS NULL OR <= NOW(3)-30min`，1 行=获准、0 行=抑制且零写）→
  获准才计数（异步复用既有 `createEvent` 并保存其 eventId；同步复用既有 `increasePlayTimes`/
  `increaseShareTimes` 守卫更新）→ 终态 CAS。四步同一事务提交。
- 并发同 `requestKey`：唯一键冲突让本次尝试事务**整体回滚**，回滚后在事务外读赢家并重放；
  禁止在回调里捕获后继续使用 rollback-only 事务。`record()` 显式断言**不得在已有事务中调用**。
- 证据：单测 `VideoInteractionServiceTests`（第一阶段 25 例，第二阶段补齐后 32 例，恢复轮修复后 33 例）；opt-in 隔离 `VideoInteractionIsolatedTests`
  7 例（异步，含 Outbox 触发器故障整事务回滚）与 `VideoInteractionSyncIsolatedTests` 7 例
  （同步，含 `Long.MAX` 计数失败整事务回滚、`requestKey` 大小写敏感）；第一阶段普通回归 613 tests，
  0 失败/错误，194 skipped（`/tmp/t15-unit-full2.log`；当前工作区为 666/200，见下一小节）。
- 第一阶段遗留的“明确未做”清单（无 HTTP 接口/无调用方、无身份解析、无 Redis 接线、无超限统一响应）
  **已被第二阶段实现**，其余未做项见下一小节。

**已交付：T15 第二阶段 HTTP 接线 + Redis 准入（2026-10-23 候选，未父验收、未上线，T15 不勾选）**

- 入口：`POST /api/videos/{vid}/play-count` 与 `/share`（`UserVideoController`）保持 `@OptionalLogin`，
  新增必填 `Idempotency-Key`（`[A-Za-z0-9._:-]{1,64}`，大小写敏感）与匿名必填 `X-Visitor-Id`（canonical UUID），
  返回 `ApiResponse<VideoInteractionVO>{counted,duplicate,reason}`；两个头在 Controller 上故意 `required=false`，
  由服务层给明确 400（否则 `MissingRequestHeaderException` 会退化成 500）。
  旧签名 `UserVideoService#increasePlayCount(Integer)` / `#increaseShare(Integer)` **已删除且无重载**，
  新签名带 `currentUserId, vid, requestKey, visitorId`，只委派准入、不加 `@Transactional`。
- 准入编排 `VideoInteractionAdmissionServiceImpl`（固定顺序）：参数校验（`VideoInteractionValidation`）→
  身份解析（`VideoInteractionActorResolver`：登录优先、域分离 SHA-256、不采集 IP/UA/指纹）→
  视频校验（`PublishedVideoGuard#requirePublishedPlayable`，400/404/500，与点赞/投币/收藏/进度同一份规则）→
  **只读重放查询（不碰 Redis）** → Redis 准入 → `record`。
- Redis 准入 `VideoInteractionRateLimitServiceImpl` 复用 `lua/fixed-window-rate-limit.lua`，同一 actor 两维
  （单动作默认 60 次/60 s、合计默认 120 次/60 s；`app.video-interaction.*` 启动期校验，`application.yml` 无条目）；
  超限 429 单一文案 `操作过于频繁，请稍后再试`；脚本 `null`/负数/异常 → 503（fail-closed）。
- 已批准并落地的决策：两个头**强制**、缺失/非法 400、**不兼容旧客户端且无兼容窗口**；
  视频校验先于重放（视频下架/删除后同一 `requestKey` 重试返回 **404**）；匿名身份用客户端随机 UUID、
  不新增身份机制；30 分钟窗口不可配置。
- 证据（均来自已有日志，本轮未复跑）：普通回归 **666 tests，0 失败/错误，200 skipped，BUILD SUCCESS**
  （`/tmp/t15b-unit-full.log`，`Tests run` 第 14129 行）；定向单测 **72 tests，0 失败/错误**
  （`/tmp/t15b-target-unit3.log`，HttpMapping 7 + ActorResolver 7 + Admission 17 + RateLimit 9 + Service 32）；
  opt-in 准入隔离 `VideoInteractionAdmissionIsolatedTests` **6 tests，0 失败/错误/跳过**
  （`/tmp/t15b-admission-it4.log`）；第一阶段隔离回归 20 tests，0 失败/错误
  （`/tmp/t15b-regression-it1.log`）；维护演练 1 例通过（`/tmp/t15b-drill-maintenance.log`，
  演练入口 `countOnePlay()` 每次随机访客 + 随机 `requestKey`）。
- **仍未绿/未做**：隔离套件最近一次完整运行 `VideoStatusIsolatedIntegrationTests` **20 tests，1 failure，
  1 skipped，BUILD FAILURE**（`/tmp/t15b-drill-it2.log`，`concurrentFlushAndConsumptionPreserveAllEvents`
  `condition did not converge in 30 seconds`）——历史并发用例、不经过 T15 入口、根因未定位未修复，
  不得写成已解决或“整组全绿”。仍欠：
  生产死锁重试、容量/压测、服务端下发/签名访客标识、旧客户端兼容窗口、任何生产防刷验收
  （两表保留期/清理已由下一小节交付为**默认关闭的候选**，未运行隔离用例，仍不是 T14 的 90/275 天政策）。
  恢复轮修正：唯一键冲突后读不到赢家返回 500，避免误报请求冲突 409，事务仍回滚；
  `VideoInteractionServiceTests` 定向实跑 33 tests，0 failures/errors/skipped（`/tmp/t15-resume-service-unit.log`），
  包含 Outbox 冲突回滚用例；未重跑全量或隔离演练，不改变历史并发失败与未验收结论。
  教程：`docs/tutorial/video-interaction-idempotency.md`（§11 为第二阶段当前事实）。

**已交付：T15 30 天有界清理（本轮候选，默认关闭，未运行隔离用例，T15 仍不勾选）**

- 保留期已由用户确认并写死为常量（**不可配置**）：`video_interaction_request` 终态行自 `completed_at`
  起 30 天；`video_interaction_window` 连续 30 天无计数。做成配置项就能配 0 天，窗口行每轮被删、30 分钟
  限计被静默绕过，因此**没有** `retention-days` 键。
- 语义变更：记录还在 → 同一 `requestKey` 重放历史结果；记录已被清理 → 同一 `requestKey` 按新请求处理、
  仍受 30 分钟窗口约束。清理**默认关闭**且按批推进，**不是** 30 天到点 TTL（31 天前的行在下一轮读到它之前
  仍会重放）。
- 旋钮（`app.video-interaction.*`，`application.yml` 无条目即默认值）：`cleanup-enabled=false`、
  `cleanup-batch-size=500`（启动校验 1..5000）、`cleanup-fixed-delay-ms=60000`（校验正数）。独立单线程
  `ThreadPoolTaskScheduler`（**bean `videoInteractionCleanupTaskScheduler`**，与 `@Component` 调度组件
  `videoInteractionCleanupScheduler` **不同名**——同名会让 Spring Boot 关闭 bean 覆盖时启动失败；线程前缀
  `video-interaction-cleanup-`，5 s 有界停机）与 T06 投递/落库/恢复/补偿链路隔离，并由
  `VideoInteractionCleanupSchedulingWiringTests` 在真实 Spring 上下文（`allow-bean-definition-overriding=false`）
  里钉住两个 bean 的类型与 `@Scheduled` 引用。
- `VideoInteractionCleanupServiceImpl#cleanupOnce` 每轮三批（state=1 / state=2 / 空闲窗口），每批最多
  `cleanup-batch-size`：非锁定选 id → **按主键等值删并重复时间+状态守卫**（候选读与删除之间没有事务，
  守卫是“不删刚推进的窗口行”的唯一防线）→ 不开显式事务（各 mapper 独立提交），服务拒绝在已有事务中运行。
  空 id 集合 `WHERE 1 = 0` fail closed；候选超批/`null`/非正 id 在删除前失败；时间阈值一律由 SQL 计算
  （`NOW(3)`，不新增 JVM 时钟阈值；DB 时钟/会话时区风险仍在）；
  `timeout=3` 只是语句超时意图，**不承诺**硬 3 秒截止；**也不保证**优化器访问路径与锁足迹
  （间隙锁/next-key、锁等待时长），那必须用真实 MySQL 的 `EXPLAIN`/锁观测实测，本轮未运行 IT、无实测数据。
- 永不清理：已提交 PENDING、`completed_at IS NULL` 的终态行、`last_counted_at IS NULL` 的窗口行。不碰
  `video_status` / Outbox / 消费账本 / Redis（含 `processed`），无新 HTTP 接口、无分布式锁、无游标水位。
- 迁移 `database/migrations/t15-video-interaction-cleanup-indexes.sql`（幂等，只加索引，镜像
  `database/feibijiubi.sql`）是开启清理的**硬前置**：`(state, completed_at, id)` 与
  `(last_counted_at, id)`；两条候选查询带 `FORCE INDEX`，缺索引时 MySQL 报 1176、清理可见失败，不降级
  全表扫描；`state` 必须单值查询（`IN (1,2)` 会引入全局 filesort，`LIMIT` 提前终止失效）。
- 证据（本轮实跑，受限堆串行，`-DvideoStatusIT=false -DvideoStatusScale=false`）：定向纯单测 **33 tests，
  0 失败/错误/跳过**（`VideoInteractionCleanupServiceTests` 19 + `VideoInteractionCleanupContractTests` 9 +
  `VideoInteractionCleanupRetentionTests` 2 + `VideoInteractionCleanupSchedulingWiringTests` 3，
  `/tmp/t15-cleanup-target2-unit.log`；调度装配单独 `/tmp/t15-cleanup-wiring-unit.log`）；普通回归 **744 tests，
  0 失败/错误，214 skipped**（`/tmp/t15-cleanup-wiring-full.log`，其中 opt-in `VideoInteractionCleanupIsolatedTests`
  13 例整体 skip）。**未运行**任何隔离 IT/scale/容器/DB/Redis/迁移/删除；opt-in 隔离类（过期/新鲜/NULL/PENDING
  矩阵、删窗口后首次计数+第二次抑制、29 天重放 vs 删除后同键变新请求、选 id→推进时间→删 0 行、真实行锁交错、
  批有界收敛、重跑幂等、`EXPLAIN` 命中索引、不碰统计账本）**只编译**。因为生产清理全局扫描、无法限定 vid，
  该类每轮清理前先检查“是否存在不是本类造出的过期候选行”，存在即失败退出、不删未知测试数据。
- **调度 bean 同名阻塞修复轮（父审查发现，本轮修复）**：`@Component VideoInteractionCleanupScheduler` 的默认 bean 名
  `videoInteractionCleanupScheduler` 与线程池 `@Bean(name=...)` 曾同名；Spring Boot 默认
  `allow-bean-definition-overriding=false`，应用会在启动时抛 `BeanDefinitionOverrideException`（reviewer 与单测都
  看不见）。修复：线程池 bean 改为 `videoInteractionCleanupTaskScheduler`（常量 `CLEANUP_TASK_SCHEDULER_BEAN` 与
  `@Bean` 方法名同步），`@Component` 组件保留原名；新增无 DB、无完整应用的**真实 Spring 上下文回归**
  `VideoInteractionCleanupSchedulingWiringTests`（3 例，显式关闭 bean 覆盖）：验证两个 bean 的类型/名称/唯一性、
  `@Scheduled(scheduler=...)` 引用可解析、缺线程池 bean 时 refresh 必须失败并点名。把常量临时改回同名后该测试实测
  以 `BeanDefinitionOverrideException` 失败（已还原），证明它能抓住这个阻塞。同时把实现类 javadoc 的
  “不为额外间隙负责”改为“**不保证**优化器访问路径/锁足迹，须真实 MySQL 实测”，并去掉未运行 IT 却写“已钉住”的表述。
  修复后定向纯单测 **33 tests**（`/tmp/t15-cleanup-target2-unit.log`）、普通回归 **744 tests，0 失败/错误，214 skipped**
  （`/tmp/t15-cleanup-wiring-full.log`）。父代理此前复跑的 63 tests（`/tmp/t15-cleanup-parent-unit.log`）不验证启动，
  不作为修复后证据；隔离 IT 仍只编译未运行。
- **审查修复轮（同轮，另跑纯单测）**：① 隔离类 `@AfterEach` 不再调用全局 `cleanupOnce()`（那会在 foreign guard
  拒绝后照样执行、把刚拒绝的别人的过期行删掉），改为只用本次跟踪的 id 集合走 `deleteExpiredRequestsByIds` /
  `deleteIdleWindowsByIds`（同样带时间/状态守卫，一次语句删完，无 `LIMIT`、无轮次）；跟踪完整性由 foreign guard
  兜底（任何未跟踪的过期候选都算外来数据），并加**源码文本契约** `isolatedTeardownDeletesOnlyOwnTrackedIdsAndNeverRunsGlobalCleanup`
  防止有人把收尾改回全局清理；② 两个“先选候选再推进时间”的确定性用例补上 `assertOnlyOwnExpiredCandidates()`，
  避免外来过期行把 3 行候选页挤掉而报成误异；③ `VideoInteractionService` 接口把“永远只计一次”改为“记录保留期内
  只计一次”，并给 `lookupReplay` 补“重放以请求行仍存在为界”的说明；④ 运行手册速率拆成请求行 2×500/分钟=1000 行/分钟
  （≈1.44 M/天）与窗口行最多 500/分钟（≈0.72 M/天），合计 1500 行/分钟（≈2.16 M/天），并注明这是**理想上限非实测**；
  ⑤ `database/feibijiubi.sql` 的表注释改为保留期口径，历史迁移 `t15-video-interaction-idempotency.sql` **只加注释说明、
  不改任何可执行 DDL**（去掉注释后与 HEAD 逐字一致）；⑥ `VideoInteractionProperties` 类说明改为“准入+清理参数”，
  scheduler 注明占位符默认值 `60000` 与字段默认 `60_000L` 是两份字面量、必须一起改；⑦ 时钟表述收窄为“**不新增 JVM
  时钟阈值**”，并写明 DB `NOW(3)`/会话时区/DST 的风险并未消失且无时区切换测试。修复后定向纯单测 **30 tests，
  0 失败/错误/跳过**（`/tmp/t15-cleanup-reviewfix-unit.log`），普通回归 **741 tests，0 失败/错误，214 skipped**
  （`/tmp/t15-cleanup-reviewfix-full.log`）。隔离 IT 仍**只编译未运行**。
- 残余风险（记录不修）：Outbox 积压超过 30 天（本身是事故）时同 `requestKey` 重试会产生第二个 `eventId`，
  旧事件是否再次计数取决于消费侧 30 天年龄守卫（T14-S1）与既有重放/补偿窗口，**不写成“必然重复”**；
  已提交的 `NULL last_counted_at` 行按语义永不清理（当前代码不可达，属理论泄漏）。

### T16：系统验收、容量评估和上线

**步骤**
1. 在隔离环境进行真正的进程 kill/restart、依赖断连恢复；不能只把方法抛异常等同于完整崩溃演练。
2. 压测覆盖单热点、多视频、正负混合、重复消息、积压恢复、冷启动重建；分别测生产入口、Outbox、消费、落库瓶颈。
3. 固定机器/数据集/JVM 预热，至少三轮记录吞吐、P95/P99、DB 写次数、MQ 积压、Redis 内存及恢复速度，不承诺未经验证的提升比例。
4. 先确定目标负载 L 和延迟目标，再执行持续负载与突发测试；目标不能在测试失败后无记录地下调。
5. 同步代码与配置/HTTP/MQ 变化到 `docs/api.md`、对应教程；`AGENTS.md` 与 `CLAUDE.md` 保持一致。
6. 制定备份、增量迁移、统一停旧/启新、观察与回滚步骤；协议不兼容版本不得混跑。

**验收**
- 目标 L 持续 30 分钟无持续积压增长，2L 突发 5 分钟后能在约定恢复预算内收敛；两项先固定环境和验收预算。
- 全部不变量成立；无法自动恢复的故障均有告警与可执行处置手册。
- 回滚演练不清空 outbox、消费记录、processed 或门闩，不因回滚重复累计。
- 无未说明的 P0/P1 阻塞项；P2 延期项明确负责人/风险/触发条件。
- 完成评审后才批准上线，而不是测试通过后自动部署。

## 7. 分阶段上线与回滚规则

- **批次 A：正确性补丁（T01—T04）**。先完成 SQL/类型兼容分析和迁移预演，再统一部署相关消费者/落库组件。
- **批次 B：恢复稳定性（T05—T11）**。补偿默认关闭，先只读扫描/预览，再限速启用；观察重复率、状态迁移、积压年龄和 DB 压力后扩大。
- **批次 C：成本和产品收益（T12—T15）**。强制重建、实时读取、归档分别独立开关；归档先 dry-run，再小批执行。
- **最终验收（T16）**。每个批次都先完成适用的集成验证，T16 负责跨批次整体演练，不把所有测试拖到最后。

回滚触发：计数不一致、合法事件批量误入修复、重复计数、状态推进异常、持续积压或关键资源耗尽。优先暂停新增补偿/归档/变更功能并保留证据，停止不兼容写入者后统一回滚。不能以删队列、删幂等记录、给门闩补 TTL 或清空 Redis 作为通用回滚办法。涉及不可逆迁移时先采取向前修复；必须在实施前说明不能直接回滚的部分。

## 8. 后续每项任务的维护模板

实施时在本文末尾追加记录，并同步更新 TODO 表：

```text
任务 ID：
状态：
负责人：
开始/完成时间：
确认的问题与复现方式：
设计选择及拒绝其他方案的理由：
变更文件 / commit：
配置、数据库、HTTP/MQ 协议变化：
测试环境、命令、数据规模：
验收结果与证据路径：
未通过项 / 剩余风险：
上线开关 / 回滚步骤：
下一任务与依赖：
```

### T01 执行记录

- 状态：已完成（2026-10-21）；执行：global DeepSeek v4.1 Flash 子代理；验收：父代理独立复跑。
- 变更：新增 SQL 基线、顺序分类及 Outbox 单测；真实消费者 latch 阻塞窗口 100 轮；未修改业务行为。
- 证据与环境：见 [基线报告 §8](video-status-improvement-baseline.md)。默认 MySQL 8.4.11 窗口产生1690并保留status1，依赖确认后收敛；不能宣称所有环境必然隔离合法事件。
- 配置/协议/DDL：无生产变更；测试仅独立 `feibijiubi-test`。回滚仅撤销新增测试/报告，不删除事件证据。
- 下一项：T02（cn DeepSeek v4.1 Flash 实现），T03 必须在 T02 后验收；T02/T03 不单独上线。

### T02 / T04 阶段记录

- T02 执行模型：`my-workbuddy/cn:deepseek-v4.1-flash`；global 模型交叉审查。父代理验收未通过，WIP 6b95cf6 仅保存在独立工作树。
- T04 由 global 模型提前准备（计划允许的 T01 后独立项），候选746c50e。19 个单测父复验通过，但依赖/演练未满足，不勾选完成、不合入。
- 失败证据、待修点、候选路径与下一步见 [阶段交接](video-status-improvement-handoff.md)。后续必须修复再验收，不能绕过 T02/T03 开展吞吐改造。

### T02—T04 最终验收记录（2026-10-21）

- 执行：cn/global DeepSeek v4.1 Flash 子代理，父代理多轮退回、修正并独立复跑；合入提交 T02 `a9a3ad9`，T03 `c4d6e99`，T04 `fe11d03`。初轮失败与候选保存在阶段交接，不隐去失败历史。
- 环境：Windows 本机、Java21.0.9/编译目标17；独立 `compose.test.yaml`，MySQL8.4.11，Redis7.4、RabbitMQ4；默认 SQL mode 见基线报告。未使用正式资源、未输出应用凭证。
- 命令1：`./mvnw clean '-Dtest=*Tests,!BackendApplicationTests,!VideoStatusIsolatedIntegrationTests,!VideoStatusSqlBaselineTests,!VideoStatusCounterIsolatedTests,!VideoStatusLuaCounterTests,!VideoStatusCounterTransactionTests' test`，87 tests，0 failures/errors/skipped。
- 命令2：`./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusSqlBaselineTests,VideoStatusCounterIsolatedTests,VideoStatusLuaCounterTests,VideoStatusCounterTransactionTests,VideoStatusIsolatedIntegrationTests' test`，62 tests，0 failures/errors，1 skipped（可选benchmark）。父代理日志 `/tmp/p0-all-unit.log`、`/tmp/p0-all-it.log`，可用相同命令复跑；临时日志不作为唯一长期证据，固定断言在测试源码中。
- 核心证据：30亿 DB/Redis→Java→VO与个人获赞；Long.MAX上界与>2^53精确Lua；校验失败current/processed/dirty/hot不变；真实事务中计数已更新后markFlushed抛错/0行均回滚；同步点赞关系在上限/下限拒绝后回滚。
- 顺序：真实消费者latch阻塞100轮，`deferred=100/quarantined=0`，释放后DB=Redis=0且两个事件FLUSHED。父代理额外发现并修正上限反向窗口、求和中间溢出、LIMIT1正优先饥饿；mock各100轮与真实MySQL负优先备选均收敛。重复、门闩、无锁快路径、并发flush回归通过。
- 配置：relay=false不领取/恢复/发布；真实绑定及@PostConstruct坏参数启动失败，不连接基础设施。维护演练停止输入并排空，再sync写DB、持锁重建派生缓存、恢复async；最终4异步+2同步，DB=Redis=6，无额外Outbox或重复事件。没有新增生产热切换/强制重建入口。
- 设计：不改DDL；SQL用DECIMAL守卫、计数Long、Lua字符串精确算术；flush BigInteger求净量，边界不足延后2s、4096条本地LRU、超过预算只告警、不误隔离。上限失败最多一次负优先锁定备选；dirty不SREM、vid锁/无TTL token门闩保留。
- 风险/回滚：先盘点>Long与>Integer（含聚合）再部署；T02/T03配套、旧新不兼容消费者不得混跑。不要删除幂等或门闩；优先暂停并向前修复，不把旧版误隔离当作安全回滚。任意Redis运行时故障/数据丢失、进程崩溃与大规模性能仍不在本阶段保证范围，交T09/T12/T16；持续边界不足需T05指标与T10处置。
- 文档：`AGENTS.md`/`CLAUDE.md`保持一致，`docs/api.md`及三篇对应教程同步。下一任务T05，P0实现出口已通过，但不批准自动上线。

### T05—T06 最终验收记录（2026-10-22）

- 状态：已完成；实现提交 T05 `46af28b`、T06 `3e33415`，验收基线 `b59b579`。验收由父代理本次独立复跑；本次仅做文档收尾，未修改代码、未提交 git、未自动部署。
- 环境与数据边界：Windows 本机 + 独立 `compose.test.yaml`（MySQL 13307 / Redis 16379 / RabbitMQ 15673）；未使用正式资源，未读取或输出 `application.yml` 凭证。测试只使用本用例新建数据，不清理历史事件；仍属测试环境验收。
- 命令1（无基础设施）：`./mvnw '-Dtest=VideoStatusMetricsSamplerTests,VideoStatusMetricsTelemetryTests,VideoStatusMetricsComponentWiringTests,VideoStatusMetricsAuthorizationTests,VideoStatusTelemetryInstrumentationTests,VideoStatusSchedulingConfigTests,VideoStatusSchedulingIsolationTests' test`，结果 69 tests，0 failures/errors/skipped，日志 `/tmp/improvement-t05-t06-unit.log`。
- 命令2（隔离环境）：`docker compose -f compose.test.yaml up -d --wait` 后执行 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusMetricsIsolatedTests,VideoStatusSchedulingRealFlowTests' test`，结果 8 tests，0 failures/errors/skipped，日志 `/tmp/improvement-t05-t06-it.log`。两项命令均同时覆盖 T05/T06，不把总数拆成两个任务的虚假细分数。
- T05证据：真实隔离库制造 OUTBOX FAILED、OUTBOX PENDING(1)>120s、CONSUMED RECEIVED(0)>180s、CONSUMED REPAIR_REQUIRED(3) 四类样本，采样告警和 `/api/admin/video-status/metrics` 缓存路径均可定位；采样前后事件/统计行逐字段相等；迁移连续两遍幂等且 `EXPLAIN` 命中新索引；flush 仅在事务提交后记一次端到端样本，外层回滚为零次；门闩新格式可读停留时间、老格式明确未知且只读。索引迁移必须先于采样上线，缺索引只返回 `DEGRADED`。
- T06证据：真实业务组件下将 publish 受控阻塞，输出 `blocked publish millis=30082 flushMillis=62 flushRuns=267 recoveryRuns=144`，即阻塞超 30 秒时落库仍在 10 秒测试预算内完成、恢复调度持续心跳；两个独立调度器共享同一 Redis 分布式锁并发处理同一 vid，输出 `baseline=100 flushed=100`，只落库一次。
- 边界：T06 的“两个实例”是两个独立调度器对象在**同一 JVM**，不是两个 JVM；30 秒故障是**受控阻塞**，不是真实网络断连 30 秒。T05 告警只写日志和 HTTP 快照，**没有外部告警投递**；本实例指标重启后清零，不能与共享 DB backlog 相加。两任务均不等同于生产验收、真实故障演练或容量承诺。
- 文档：`AGENTS.md`/`CLAUDE.md` 保持一致；`docs/api.md` 补充真实 `/api/admin/video-status/metrics` 与 `/gate` 契约；可观测/调度教程移除“等待验收”和过时“无指标/共享调度线程”描述；`video-status-runtime-roles.md` 与阶段交接同步状态。
- 剩余风险/下一项：生产阈值、外部告警、真实两 JVM/网络故障和系统容量仍交 T16；调度预算不能强杀阻塞调用，业务 SQL 超时未统一；T07 处理 Outbox 发布/回写/租约细化，T10 处理失败事件处置；T11 的恢复公平性已另行验收（见下方 T11 记录）。

### T07 最终验收记录（2026-10-22）

- 状态：**已完成（测试环境验收）**；实现已合入主分支 `95af60d`（候选 `eec1e01`）。本次只补验收文档，未修改代码、未提交 git、未自动部署。
- 验收命令（父代理本次实跑）：`./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests,VideoStatusIsolatedIntegrationTests,VideoStatusMetricsIsolatedTests,VideoStatusSchedulingRealFlowTests,VideoStatusDirtyRecoveryRealConvergenceTests' test`；结果 **87 tests，0 failures / 0 errors / 1 skipped**（可选 benchmark），日志 `/tmp/improvement-main-regression3.log`。早前 T07 单组命令 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests' test` 为 **61 tests，0 失败/错误/跳过**（`/tmp/improvement-t07-verify.log`、`/tmp/improvement-t07-main.log`）。
- 失败历史（保留）：先前整组回归曾出现 3 failures（旧 `VideoStatusIsolatedIntegrationTests` 维护排空/真实全链路/负依赖重试，均 30 秒超时），日志 `/tmp/improvement-main-regression.log`；`8e81d55` 修正测试观测错误后由本次最终回归通过，最终失败原因与修复过程见回归续作文档。
- 已验证事实：发布发送失败、confirm 不确定、confirm 后回写失败、非法事件和租约恢复按独立预算分类；`retry_count`、`lease_recovery_count`、`confirm_writeback_count` 互不挤占，confirm 回写第 10 次形成 `confirm writeback budget exhausted` 终态；租约恢复第 10 次形成 `lease recovery budget exhausted` 终态；CAS 0 行只诊断，单条异常不中断整轮。
- 管理边界：当前仅提供单个 Outbox FAILED 事件的安全查询与受审计重放（`GET /api/admin/video-status/outbox/{eventId}`、`POST .../replay`），属于 T10 极窄子集；批量处置、DLQ、consumed status3 修复、对账仍归 T10。
- 文档与环境：`AGENTS.md`/`CLAUDE.md` 保持一致；`docs/api.md` 与 Outbox 可靠性/租约迁移/重放 Runbook/运行角色教程同步。仅使用独立测试环境，未读取或输出 `application.yml` 凭证；**未宣称生产上线、真实进程崩溃演练或真实两 JVM/网络分区验证**，这些仍交 T16。

### T08 最终验收记录（2026-10-22）

- 状态：**已完成（测试环境验收）**；实现已 cherry-pick 到主分支 `0dd98ad`、`b846032`、`92eb8c1`（候选 `b796fc8`）。本次只补验收文档，未修改代码、未提交 git、未自动部署。
- 验收命令（父代理在独立候选父复验）：`./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusIsolatedIntegrationTests,VideoStatusConsumerRetryLifecycleIsolatedTests' test`；结果 **22 tests，0 failures / 0 errors / 1 skipped**，日志 `/tmp/t08-parent-final.log`。
- 已验证事实：三级固定 TTL 队列 10/30/120 秒真实到期并各自回到主队列，原 10 秒队列名称与参数不变；真实 listener 容器 `stop()` 后按恢复延迟 `start()` 继续消费；转发失败不 ACK/NACK，由单线程恢复任务受控暂停，窗口内 start 次数有界；业务成功但 ACK 失败不转发 retry/DLQ、不 NACK，重复投递由 eventId+指纹幂等吸收；非法 payload 只进 DLQ 一次。
- 故障边界：Redis 验收使用服务端 `CLIENT PAUSE 120000`，进程与网络连接始终存活；**不是** Redis 进程重启、网络断连或任意 Redis 数据丢失保证。容器 stop/start 是真实容器生命周期操作，但不是进程崩溃/两 JVM 演练。这些仍交 T16。
- 失败历史（保留）：候选阶段父代理首次运行曾 2 failures/1 error/1 skipped（`/tmp/t08-real-lifecycle.log`），后续子代理复跑超时、旧 IT 100 轮顺序用例曾超时；修正测试清理/等待与观测后，由本次 22 tests 通过，细节见回归续作文档。
- 管理/协议边界：T08 不新增或修改 HTTP 接口；DLQ 内容对账、FAILED/status3 处置仍归 T10，status0 自动补偿仍归 T09。

### T09 实施与最终验收记录（2026-10-22）

- 状态：**已完成（测试环境验收）**；实现已合入主分支 `97c7357`（候选 `aab1c64`）。本文只更新文档，未修改代码、未提交 git、未自动部署。
- 候选父代理命令：`./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusStatus0CompensationTests,VideoStatusStatus0CompensationAuthorizationTests,VideoStatusStatus0CompensationIsolatedIntegrationTests' test`；结果 **43 tests，0 failures / 0 errors / 0 skipped**，其中 **8 个真实隔离 IT**，日志 `/tmp/t09-parent-final.log`。该候选结果早于主分支最终验收，最终验收见下条。
- 实现边界：只处理 `process_status=0 (RECEIVED)`；原 eventId/payload/vid/type/delta，重算语义指纹，校验 DB 年龄与 `occurredAt`；短事务领取 + 租约 CAS，MQ confirm 不在领取事务内；回写不增加 `consumer_retry_count`，补偿预算独立。
- 三个闸门默认关闭：`status0-compensation-enabled`、`status0-compensation-integrity-confirmed`、`status0-compensation-allow-unproven-replay`。`enabled=true` 但 integrity 未确认时启动失败；受控未证明模式必须三者同时为真。
- processed 缺失固定为 `EVIDENCE_UNKNOWN`，不能判定“未应用”：可能从未应用，也可能 current 已应用而 processed 单独丢失。默认不发送、不转 status3、不清 current、不伪造证据，只释放租约/冷却/诊断。受控模式是操作员声明，不是自动检测，也不保证任意 Redis 数据丢失或 current+processed 同时丢失的安全。
- 接口：仅新增管理员只读 `GET /api/admin/video-status/status0-compensation/preview?limit=1..100&afterId>=0`；不领取、不发送、不写 Redis/DB，默认关闭时仍可用。没有运行期 pause/启停写接口。
- 配置/停机：yaml **不是热更新**；事故时对所有承担补偿角色的实例关闭总开关并逐个重启，等待 inflight 发布/回写结束或租约自然到期，**不强杀**正在进行的 publish confirm。
- 迁移：`database/migrations/t09-video-status-status0-compensation.sql` 幂等新增补偿列与索引，镜像到 `database/feibijiubi.sql`；不回填历史、不改 consumed 行状态。
- 主分支最终验收（父代理本次实跑）：普通回归 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` 为 **356 tests，0 failures / 0 errors / 108 skipped**，日志 `/tmp/t09-main-unit.log`；opt-in 隔离回归 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusStatus0CompensationTests,VideoStatusStatus0CompensationAuthorizationTests,VideoStatusStatus0CompensationIsolatedIntegrationTests,VideoStatusIsolatedIntegrationTests,VideoStatusOutboxReliabilityIsolatedTests' test` 为 **62 tests，0 failures / 0 errors / 1 skipped**，日志 `/tmp/t09-main-it.log`。候选早前 43 tests（含 8 真实隔离 IT）见 `/tmp/t09-parent-final.log`。
- 失败历史（保留）：早前验证曾用错开关导致目标测试被跳过；mapper 参数绑定/时区处理也曾失败，修正后才得到上述结果。未读取或输出任何凭证。
- 边界：以上为测试环境验收，**不是生产验收**，也不是真实进程崩溃/两 JVM 演练。无证据（unproven）重放模式仍受三闸门控制且默认关闭，不能宣称任意 Redis 数据丢失安全；生产开关、容量和跨进程恢复仍交 T16。
- 文档：`AGENTS.md`/`CLAUDE.md` 保持一致；`docs/api.md` 补充 preview GET 参数与响应；T09 教程同步实现、默认闸门、停机边界、最终验收证据和失败历史。

### T10 第一阶段进展记录（2026-10-22，T10 未完成）

- 状态：**T10 整项仍为进行中，不能勾选**。第一阶段实现已合入主分支 `6fc5e7f`（前序 `d21ebd7`、`66f9bb0`、`6a46fd0`、`2641897`、`e2c6ea6`），不再使用“独立工作树 15 个单测候选、未合入”的旧描述。DLQ 摄入/分页/处置/重放、consumed status3 受控重放与安全证明、批量操作、自动修复和收敛仍**未实现**。
- 父代理本次在独立测试环境实跑 `./mvnw -DvideoStatusIT=true` 的一组 T10/T07/T09 用例（日志 `/tmp/improvement-t10-main-it.log`）：**121 tests，0 failures / 0 errors / 0 skipped**，覆盖 `VideoStatusFailureAdminAuthorizationTests`、`VideoStatusFailureAdminIsolatedTests`、`VideoStatusFailureAdminServiceTests`、`VideoStatusReconciliationAuthorizationTests`、`VideoStatusReconciliationIsolatedTests`、`VideoStatusReconciliationTests`、T07 Outbox replay 边界/隔离用例和 T09 compensation 用例。
- 普通单测父代理实跑日志 `/tmp/improvement-t10-main-final-unit.log`：**416 tests，0 failures / 0 errors / 130 skipped**（skipped 为 opt-in 隔离开关关闭的用例）。以上均为测试环境证据，不是生产验收。
- 失败处置语义：`GET /api/admin/video-status/failures` 的 OUTBOX 只列 status3，CONSUMED 只列 status0（仅诊断）与 status3；`afterId` keyset、`limit=1..100`。缺少精确 `(status,id)` / `(process_status,id)` 索引时返回 HTTP 503 且不扫描。`acknowledge` 不是“忽略/修复”，只允许 OUTBOX FAILED 和 consumed status3，只写 `ACKNOWLEDGE_FAILURE` 审计且 before==after；status0 只读并返回 409。同目标并发同 operationId 在目标行锁后可返回 duplicate，不同目标/来源/动作/操作人/原因复用 operationId 为 409。历史 `retry_count`/预算计数只作诊断参考，不代表当前失败原因。
- 对账语义：`GET /api/admin/video-status/reconciliation?vid=<正整数>&limit=1..1000`（默认 100）只读。DB 八项计数、账本状态数量、事件窗口、`user_video` 关系真值和 Redis 采样分别读取，明确非原子。账本每个状态独立以 `cap=limit+1` 封顶并分别标记 `capped`，汇总 `truncated`；事件窗口最多读 `limit+1` 行仅用于判断截断，关系采样达到 `cap` 即为下界。缺 `(vid,id)` 索引时只跳过昂贵事件投影并置 `indexAvailable=false`/`incomplete=true`，其它有界 SQL 仍执行。DB 未处理异常走全局 500；Redis 读取异常保留 `READ_FAILED` 且计数为 null，不当 0。接口不写库/Redis、不自动修复、不证明收敛，永远不返回 SAFE。
- 迁移前置：`database/migrations/t10-video-status-failure-admin-indexes.sql` 与 `t10-video-status-reconciliation-indexes.sql` 均需先执行；两个索引已镜像到 `database/feibijiubi.sql`。迁移只加索引，不改列/数据。
- 文档：`AGENTS.md`/`CLAUDE.md` 保持一致；`docs/api.md` 补齐参数、响应、错误与边界；新增中文对账教程 `video-status-reconciliation.md`，并更新失败管理教程与当前进度文档。

### T10-B1 候选进展记录（2026-10-23，候选待父代理验收）

- 状态：**候选实现，未父验收，T10 整项仍不勾选**。本块只做一件事：consumed
  `process_status=3` 的**单事件、proven-only、受审计**重放；不带 DLQ、不带批量、不带自动修复、
  不修刷库型 status3。
- 唯一写入口：`POST /api/admin/video-status/failures/{eventId}/replay?source=CONSUMED`（`@AdminOnly`，
  操作人只取 `currentUserId`，请求体只有 `operationId`/`reason`）。同一 MySQL 事务内：
  consumed `3→0` CAS（`process_status=3 AND redis_applied_at IS NULL AND payload_hash=?`）、
  Outbox `SENT→PENDING` CAS、写 `REPLAY_CONSUMED_TO_RECEIVED` 的 COMPLETED 审计；任一步影响行数非 1
  或抛异常整体回滚。
- 投递策略：**不要在事务内或提交后由请求线程 publish**。事务只改 DB，提交后由既有 Outbox relay
  领取/confirm/退避/租约恢复投递同 eventId、同 payload；提交后进程崩溃仍可从 PENDING 恢复。
  因此要求同 eventId 的 Outbox 行存在且为 `SENT`，否则 409 拒绝，不降级为仅内存发送。
- 安全边界：只要 `redis_applied_at` 非空（刷库型 status3）即拒绝；processed 缺失固定
  `CONSUMED_PROCESSED_EVIDENCE_MISSING`，**不当作未应用**；无 TTL/ TTL 不足/Redis 读取异常一律拒绝，
  绝不 fail-open；没有 unproven 模式，不能绕过或替代 T09 的 integrity 前提；不覆盖任意 Redis 数据丢失。
  双窗口（DB `consumed_at` 年龄 + payload `occurredAt`，未来容忍 300s）；不改 payload、不新建 eventId、
  不清零 `consumer_retry_count`、不刷新 `consumed_at`、不删除 `last_error`、不清零 Outbox 三份预算。
- 读写一致：`GET .../precheck?source=CONSUMED` 对 status3 返回 `replay` 子结构（`decision`/`eligible`/
  `refusalCode`/`processedTtlSeconds`/`outboxStatus`/`replayAttempts`），与 POST 共用
  `VideoStatusStatus3ReplayService#evaluate`；预检查仍只读且永不返回 SAFE；`acknowledge` 与列表行为不变。
- 预算：`status3-replay-max-attempts`（默认 3，启动期校验 1..100），按同 eventId 的审计行动作有界计数
  （`LIMIT maxAttempts+1`，走 `idx_video_status_repair_event(event_id,id)`）。
- 证据（候选）：普通单测 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` 实跑 **456 tests，
  0 failures / 0 errors / 141 skipped**（日志 `/tmp/t10b1-unit.log`）；新增
  `VideoStatusStatus3ReplayServiceTests` 26 例。隔离 IT（仅 `compose.test.yaml` 13307/16379/15673）
  `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusStatus3ReplayIsolatedTests' test` 实跑 **11 tests，
  0 failures / 0 errors / 0 skipped**（`/tmp/t10b1-it.log`），其中 proven 路径经真实 relay→真实消费者→
  真实刷库收敛到 FLUSHED 且计数只累计一次，审计写入失败时真实 MySQL 事务整体回滚。T10 组回归
  `VideoStatusFailureAdmin*Tests,VideoStatusReconciliation*Tests,VideoStatusStatus3Replay*Tests`
  为 **100 tests，0 failures/errors/skipped**（`/tmp/t10b1-t10-group.log`）。
- 既有缺陷保留（非本块引入）：`VideoStatusIsolatedIntegrationTests` 的
  `concurrentColdStartAndDuplicateDeliveryConverge` 与 `concurrentFlushAndConsumptionPreserveAllEvents`
  在 `-DvideoStatusIT=true` 下报 **2 errors**（该类 17 tests，0 failures）；未修改 HEAD 的基线整组为
  **1 failure / 2 errors / 1 skipped**（`/tmp/t10b1-baseline.log`），其中唯一 failure 就是上文 lease
  fixture 问题。并发用例与本块改动无关，未在本块修复（不顺手改无关缺陷）。
- 字段修正（父代理，2026-10-23）：`VideoStatusStatus3ReplayService#evaluate` 的 eligible 分支
  原先把 `redisAppliedEvidencePresent` 写死为 `true`，与“eligibility 已排除 `redis_applied_at`
  非空”的事实相反；现改为 `redisEvidence`（eligible 路径必然为 `false`）。单元
  `VideoStatusStatus3ReplayServiceTests` 与隔离 `VideoStatusStatus3ReplayIsolatedTests` 各补一条
  `assertFalse(eligible.redisAppliedEvidencePresent())` 断言。只影响预检查 `replay` 子结构的展示字段，
  不改拒绝判定、不写状态、不改协议。
- 父代理复跑证据（2026-10-23，窄范围）：`./mvnw '-Dtest=VideoStatusStatus3ReplayServiceTests,VideoStatusFailureAdminAuthorizationTests,VideoStatusFailureAdminServiceTests' test`
  **54 tests，0 failures / 0 errors / 0 skipped**（`/tmp/t10b1-parent-unit.log`）；
  `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusStatus3ReplayIsolatedTests' test`
  **11 tests，0 failures / 0 errors / 0 skipped**（`/tmp/t10b1-parent-it.log`）。只证明 B1 窄范围用例通过，
  不构成 B1 最终验收，也不代表 T10 全组或生产验收。
- 三预算运维说明（本轮文档补强）：B1 保留 Outbox 三份预算，临近耗尽时重放后再失败一次会快速
  FAILED；此时 T07 的单事件 Outbox 重放因“存在消费账本”一律 409，不能作为兜底；必须停下诊断，
  不允许清零预算、改状态或换 eventId。已写入 `video-status-status3-replay.md`（3.3 节、8 节）与
  `docs/api.md` 10.5。
- 测试 fixture 修正（本轮）：`VideoStatusOutboxLeaseTests.roundBudgetSmallerThanConfirmTimeoutIsStillAccepted`
  失败根因是 fixture 只设了 confirm=30 / outbox 租约=35，漏设 `status0CompensationLeaseSeconds`
  （默认 30 < 35，先被补偿租约校验拒绝，测不到 roundBudget 规则）。已在 fixture 补 35，
  生产校验代码未改、未放宽；该用例实跑通过（`/tmp/t10b1-worker-lease-after.log`）。普通单测回归
  `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` 为 **456 tests，0 failures / 0 errors，
  141 skipped**（`/tmp/t10b1-worker-unit.log`，skipped 均为 opt-in 隔离用例），无新增失败类别。
  `VideoStatusIsolatedIntegrationTests` 的两个并发失败仍未处理（不在本块范围）。
- 边界：以上均为测试环境证据，不是生产验收，也不是进程崩溃/两 JVM/Redis 数据丢失演练（T16）；
  DLQ 摄入/处置、批量处置、刷库型 status3（需刷库重试或 T12 门闩内重建）、自动修复仍未实现。

### T11 验收记录（2026-10-22）

- 状态：**已完成**；实现候选 `be7d836`，在主仓库基线 `dc41477` 上以 `git merge --squash be7d836` 暂存。本次只补验收文档，未修改代码、未提交 git、未取消暂存。
- 提前执行理由：T11 是独立的 dirty 恢复公平性与失败退避改进，只依赖 T03/T05/T06 的恢复、观测和调度预算契约，不依赖 T07—T10 的 Outbox 处置与消费重试。计划已明确允许 T11 在 T06 后提前交付；因此在 T07 仍待验收、T08 实施中、T09 设计中的阶段先验收 T11，属于按计划调整而非跳过依赖，后续顺序不变。
- 验收命令（父代理本次实跑）：`./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusDirtyRecoveryRealConvergenceTests,VideoStatusDirtyRecoveryFairnessIsolatedTests' test`；结果 **3 tests，0 failures / 0 errors / 0 skipped**，日志 `/tmp/t11-real-convergence.log`。
- 关键输出：`T11 real convergence vids=250 head=100 tail=150 flushRounds=1 oracleTotal=250`。
- 环境与证据：真实 MySQL、Redis（独立 `compose.test.yaml`），只新增本用例专属 250 个视频和事件；对专用头部 100 个 vid 注入 SADD 故障，四轮恢复内 150 个健康尾部全部获得真实 dirty 机会，释放故障并越过退避后 250 个视频全部落库收敛，全程无 `process_status=3`。测试 oracle 来自独立 seed 的预期值，并同时核对事件表 `SUM(delta)`，不是用被测代码自身结果反推。
- 候选修复：`be7d836` 修复候选实现中“预算在首个 vid 尚未执行时耗尽会回退/丢失游标”的缺陷；现在预算耗尽只把游标推进到**已经处理过**的最后一条，首条未执行时保留上一轮 keyset 进度，避免极端预算下反复卡住头部。
- 单测：`VideoStatusDirtyRecoveryFairnessTests` 的 5 个公平性用例及既有 27 个相关测试由子代理运行通过；父代理本次只复跑上述真实 IT，未复跑该批单测。
- 边界：故障是**进程内专用 SADD 故障注入**，不是进程崩溃、真实 Redis/MySQL 宕机或两 JVM 演练；结果属于测试环境验收，**不能称为生产验收**。生产容量、真实基础设施故障和跨进程恢复仍交 T16。
- 文档：`AGENTS.md`/`CLAUDE.md` 保持一致；`docs/api.md` 增加 T11 无 HTTP 变更说明与运行命令；公平性教程补充验收证据、`be7d836` 游标修复和边界。

### T08 合入后父代理复验（2026-10-22）

- 主分支普通回归：`./mvnw '-Dtest=*Tests,!BackendApplicationTests' test`，313 tests，0 failures/errors，100 skipped（opt-in隔离测试）；日志 `/tmp/improvement-t08-main-unit.log`。
- 主分支真实复验：`./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusIsolatedIntegrationTests,VideoStatusConsumerRetryLifecycleIsolatedTests' test`，22 tests，0 failures/errors，1 skipped（可选benchmark）；日志 `/tmp/improvement-t08-main-it.log`。与候选复验独立运行，不仅依赖子代理报告。
- 测试隔离修复：purge 不会移除已预取的 unacked，先停止本测试监听器使其归队再清理专用队列，类结束关闭上下文；不删除数据库历史事件，不降低100轮及计数断言。

### T14-A 只读容量核查与归档 dry-run（2026-10-23，候选，T14 未完成）

- 状态：**候选、未父验收**；T14 整项**未完成、不能勾选**。本轮只新增测试作用域只读工具与文档，**没有生产代码、
  没有 HTTP 接口、没有 scheduler、没有 schema/迁移变更**，未部署、未提交 git、未 reset/clean/stash。
- 交付物：`src/test/java/com/feibijiubi/backend/service/video/capacity/` 下 `VideoStatusCapacityAudit`（只读 IO）、
  `VideoStatusCapacityEstimator`（纯公式与 UNKNOWN 语义）、`VideoStatusCapacityReport`（模型 + 中文渲染）；
  用例 `VideoStatusCapacityEstimatorTests`（11）、`VideoStatusCapacityReportTests`（2）、
  `VideoStatusCapacityAuditIsolatedTests`（8，opt-in，仅 `compose.test.yaml` 的 MySQL 13307 / Redis 16379）。
- 只读证据（真实隔离环境断言，不是口头承诺）：审计前后 ① 被采样行 `SELECT *` 快照逐字段相等；② MySQL 写语句计数
  （`SHOW GLOBAL STATUS` 的 `Com_insert/update/delete/replace/truncate/alter_table/create_table/drop_table`）不变；
  ③ Redis 写命令计数（`SET/SETEX/HSET/SADD/ZADD/DEL/UNLINK/EXPIRE/PERSIST/CONFIG SET/FLUSHALL/FLUSHDB` 的 calls）不变；
  ④ `DBSIZE` 不变；⑤ 重建门闩仍存在、值不变、**TTL 仍为 -1（没有被补 TTL）**；⑥ `processed` key 值与 TTL 只随
  真实时间衰减、未被重写。工具侧同时禁用 `KEYS`、全表扫描、任何 DB 写/DDL、任何 Redis 写、`CONFIG SET`、
  `FLUSHALL/FLUSHDB`、删除门闩或 processed key。
- 禁删判定：只允许终态进入候选（outbox `status=2 SENT`、consumed `process_status=2 FLUSHED`）；`status0/1/3`
  与 Outbox `FAILED` 永不列出，`Window` 构造器直接拒绝 `terminalStatus != 2`，查询若返回非终态整体判
  `NOT_EVALUABLE`。**即使是终态也一律 `NOT_AUTHORIZED_FOR_DELETION`**：保留期与重放窗口政策虽已于 2026-10-23
  批准，但归档/删除执行器、归档成功校验、旧消息硬拒绝/去重墓碑与 30 天重放守卫都未实现，所以“processed 还有
  TTL”不能作为删除 DB 幂等证据的依据。报告固定输出 `writePerformed=false`、`deletionAuthorized=false`、
  `RETENTION_PERIOD_UNDECIDED`（工具代码未改；含义是“清理执行与安全证明未实现”，不是“政策未决策”）。
- 有界与索引：dry-run 先用 `information_schema.statistics` 校验
  `idx_video_status_outbox_created (status, created_at, id)` /
  `idx_video_status_consumed_created (process_status, consumed_at, id)` 的**列序完全匹配**，再用 `FORCE INDEX` 的
  `EXPLAIN` 要求 `key` 为该索引且 `type != ALL`，两条都过才查询；否则 `NOT_EVALUABLE` + `INDEX_MISSING` /
  `ACCESS_PATH_NOT_INDEXED`，候选为空且不执行查询。查询固定“时间窗（`now-N 天`）+ `ORDER BY 时间,id` + `LIMIT 上限+1`”，
  用例实测 `type=range` 且 `Using index`（覆盖索引，不回表）。两个索引已在 `database/feibijiubi.sql`（T05 迁移）中，
  **本切片不新增迁移**；缺索引的环境应当先执行 T05 迁移，而不是让 dry-run 扫全表。
- 容量口径：可测（表体积估算、Redis 内存指标、processed 抽样单键字节）与**配置假设**
  （`-DvideoStatusCapacityAssumedDailyEvents`、`-DvideoStatusCapacityAssumedRetentionDays`，默认 0=未提供）严格分开，
  报告逐行标注“配置假设，不是测量”。公式：DB 日增 ≈ 假设日事件量 ×(outbox 行+索引字节 + consumed 行+索引字节)；
  Redis 日增 ≈ 假设日事件量 × 单键实测字节（样本 min～max）；processed key 数量 ≈ 假设日事件量 × 假设保留天数；
  保留占用 ≈ Redis 日增 × 假设保留天数；耗尽天数 ≈ (maxmemory − used_memory) ÷ Redis 日增。DB 日增给区间
  （点估计 ×0.5～×1.5，理由=`information_schema` 估算误差与 payload 分布未知）。**数据不足一律 UNKNOWN 并附原因，
  绝不回落 0**：未提供假设日事件量、processed 样本 < 5、未提供假设保留天数、`maxmemory=0`（未设上限）都对应 UNKNOWN
  （已批准政策不会自动代入这些配置参数）。
- 实测（测试环境，**不是生产容量结论**）：普通回归
  `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` = **563 tests，0 失败/错误，179 skipped**
  （`MAVEN_OPTS='-Xms64m -Xmx256m'`、`argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'` 串行；新增 13 个
  纯单测 + 8 个隔离 skip，较上轮 542/171 正好 +21/+8）。隔离验收
  `./mvnw -DvideoStatusIT=true -Dtest=VideoStatusCapacityAuditIsolatedTests test` = **8 tests，0 失败/错误/跳过**，
  并生成临时路径 dry-run 报告（示例 `%TEMP%ideo-status-capacity-audit-<random>.txt`）。该次隔离样本（仅诊断）：
  outbox 估算 5384 行/3.65 MB、consumed 估算 29523 行/27.30 MB、`used_memory≈9.95 MB`、`DBSIZE=34765`、
  `maxmemory=0`/`noeviction`、processed 单键 104～120 B；假设 100,000 事件/天时 DB 日增 80.1～240.3 MB/天、
  Redis 日增 10.4～12.0 MB/天、30 天保留 312～360 MB，**Redis 耗尽天数 UNKNOWN**（`maxmemory=0`）。
- 报告卫生：只含表名/索引名、字节数、候选 id/时间/状态、Redis 指标；**不含**连接串、主机、账号密码、
  完整 eventId、payload、`last_error`。隔离用例用随机 secret 标记做负向断言（报告文本不得出现）。
- 关联记录（父代理 T13 窄范围复跑，2026-10-23）：`VideoStatusReadConsistencyTests` **14 tests，0 失败/错误/跳过**
  （`/tmp/t13-parent-unit.log`）。这只复跑 T13 的窄范围单测类，**不构成 T13 全链验收**，T13 仍为候选、不勾选。
- 未做 / 阻塞 T14（保留期与重放窗口政策已于 2026-10-23 批准，缺的是实现与验收）：① 归档、删除的任何实现；
  30 天重放守卫已有消费登记侧（T14-S1）与三个管理入口（T14-S2，见下）的候选切片，但 relay/自动重放路径
  仍不拒绝；② 旧消息硬拒绝或去重墓碑方案；③ 归档成功校验；④ 小批、限速、断点续跑的归档执行器；
  ⑤ 归档中断后重跑无重复/漏归档的演练；⑥ 归档后重放旧消息不重复计数的演练（含 30 天守卫本身）；
  ⑦ 索引与在线查询锁影响验证；⑧ 生产容量与外部告警（T16）。**起算点与存储已于本轮用户确认**（在线 90 天按
  `sent_at`/`flushed_at` 终态完成起算、非创建/接收时间；归档 275 天从归档副本校验成功起算；审计 365 天从审计行
  创建起算；DLQ 30 天 SLA 从首次入死信队列起算、重投不重置；独立私有 COS 归档桶、完整性+记录数校验通过才允许
  删在线数据、开发阶段仅隔离测试存储），**剩余未定项**：终态时间列的索引迁移、归档校验记录的存储形态、DLQ 首次
  入队时间的落库点（“30 天窗口必须严格小于默认 `redisEventTtlDays=30`”这一旧建议**已由 T14-S1 推翻**，见下）。
  在这些前提落地前，工具结论永远是“候选可见，但不授权删除”。
- 教程：`docs/tutorial/video-status-capacity-audit.md`（含 noeviction 内存告警建议与安全恢复、未来清理前提）；
  `docs/api.md` §1.4 记录“无新增 HTTP 接口”；`AGENTS.md`/`CLAUDE.md` 保持一致。

### T14-S1 消费登记前的 30 天绝对年龄拒绝（2026-10-24，候选，T14 未完成）

- 状态：**候选、未父验收**；T14 整项**未完成、不能勾选**。范围只包含**一个入口**（消费登记），
  不是 S1 的全入口覆盖，也**不是**归档授权。无 schema/迁移/COS/删除/新 HTTP 接口。
- 交付物：`VideoStatusEventAgeGuard`（唯一判定 + 固定常量 `MAX_NORMAL_REPLAY_AGE_DAYS = 30` + 固定安全原因常量）；
  `VideoStatusConsumptionServiceImpl` 改为双构造（生产 `Clock.systemDefaultZone()`、测试可注入 `Clock`），
  在 `event.validate()` + `validatePayloadHash(...)` **之后**、任何 mapper/序列化**之前**调用守卫。
- 边界（显式数学定义）：严格 `occurredAt.isBefore(now.minusDays(30))` 才拒，**恰好 30 天允许**，纳秒不截断；
  未来时间**不新增拒绝**（保留既有行为，且明确记录**不是防篡改机制**）；缺 `occurredAt` 仍由既有 `validate()` 拒绝，
  payload/指纹语义不变。
- 拒绝路径：`NonRetryableMessageException`（固定安全原因，不含 payload/完整 eventId）→ 消费者既有
  NonRetryable → 死信转发 + ACK 边界；**不进 retry 队列、不是静默 ACK**；消费者异常分类与 ACK 边界**未改**。
- 已存在账本：因为守卫早于任何 mapper 交互，**status1/2 的重复事件同样被拒**，不再走 `handleDuplicate`
  静默返回 `REDIS_ALREADY_APPLIED`/`ALREADY_FLUSHED`；**未新增** `handleReceived` 调用；既有 7 天重投守卫不变
  （默认 7 天更严且可配置，上限受 processed TTL 约束，**不能假定永远 7 天**）。
- 配置：固定常量，**不新增任何可放宽配置**；不改 `VideoStatusProperties`（`redisEventTtlDays` 与
  7 天窗口的启动校验不动），不改 `application.yml`。
- 纠正旧建议：**“30 天必须严格小于 processed TTL”不成立**——该守卫只读事件发生时间，从不读 `processed` 证据，
  因此与 `redisEventTtlDays` 无先后约束；仍保留“严格小于 TTL”的只有真正依赖 `processed` 的 7 天自动重放与 T09 窗口。
- 不夸大的写边界：`register` 内无 MySQL/Redis 写（`REQUIRES_NEW` 事务空回滚），但全流程**不是零写**：
  消费者会转发到 DLQ（broker 写）、遥测记一次失败。
- 部署/归档前提：旧消费者仍会放行旧事件，所以归档安全要求**全体消费者同版本部署**；混版本后删归档终态行或
  回滚到旧消费者都会重新放行。本守卫本身**不构成归档授权**，dry-run 仍 `NOT_AUTHORIZED_FOR_DELETION`。
- 未覆盖（当时）：T07 Outbox 重放、T10-B1 status3 重放、T09 status0 补偿三个入口**未**调用该判定；
  管理侧现有更严格的 7 天双窗口守卫保留。**这三个入口已由后续 T14-S2（下）接入同一固定上界**，但
  relay/自动重放/恢复路径仍不拒绝，因此**仍不得**声称“所有入口拒绝旧消息”或“请求全流程零变更”。
- 可测证据：`VideoStatusEventAgeGuardTests` 9 例（固定时钟：窗口内/恰好 30 天/±1 纳秒/超 1 秒/未来/默认时区墙钟一致）；
  `VideoStatusConsumptionRegistrationTests` 6 例（超龄零 mapper 交互、已有 status1 行既不查也不改、FLUSHED 行不被静默吸收、
  恰好 30 天与窗口内登记不变、既有 7 天 `markRepairRequired` 路径在 30 天内不变）；
  `VideoStatusEventConsumerAckBoundaryTests` 新增 1 例证明新异常走 DLQ + ACK 一次。定向
  `50 tests, 0 失败/错误`（`/tmp/t14-s1-target-unit2.log`）；普通回归
  `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` = **684 tests，0 失败/错误，201 skipped**
  （`/tmp/t14-s1-unit-full.log`，`MAVEN_OPTS='-Xms64m -Xmx256m'`、
  `argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'`，串行；IT/scale 关闭）。
  **未开隔离栈、未跑 IT/scale、未动容器或服务**。测试环境候选，不是生产验收。
- 教程/文档：`docs/tutorial/video-status-capacity-audit.md` §9.3.1；`docs/api.md` §1.4（无新增接口）；
  `AGENTS.md`/`CLAUDE.md` 同内容。

### T14-S2 三个管理入口接入同一固定 30 天上界（2026-10-24，候选，T14 未完成）

> **命名提示**：本切片的编号是父代理指定的 **T14-S2**，与本文件上面作为“下一步建议”的 **S2 = 归档时间字段 +
> 校验记录**不是同一件事；归档侧 S2 仍未实现。

- 状态：**候选、未父验收**；T14 整项**未完成、不能勾选**。范围只包含“把已有固定上界接到已有入口”，
  不是全入口覆盖，也**不是**归档授权。无 schema/迁移/COS/删除/新配置项/新 HTTP 路由。
- 接入点（四处共用 `VideoStatusEventAgeGuard#isOutsideNormalReplayWindow`）：
  ① T07 `VideoStatusOutboxReplayService#replay`（createdAt/occurredAt 双窗口之后、消费账本查询与
  `resetFailedToPending` 之前）；② T10-B1 `VideoStatusStatus3ReplayService#evaluate`（occurredAt 窗口之后、
  预算**比较**/`processed` 证据与任何写之前；有界 `countReplayAttempts` 查询作为调用实参在 `evaluate` 之前已
  执行；precheck 与 replay 同源）；③ T09
  `VideoStatusStatus0CompensationServiceImpl#evaluate`（preview 与 `compensateOne` 同源）；
  ④ 消费登记（T14-S1，不变）。
- 原因码：`EVENT_OVER_NORMAL_REPLAY_WINDOW`（读写两侧同一字符串）。**刻意不复用**
  `CONSUMED_OVER_SAFE_WINDOW` / `STATUS0_OVER_SAFE_WINDOW`：那两个码描述**可配置**窗口（启动校验只要求小于
  `redisEventTtlDays`，因此允许被调到 30 天以上），而这里是**固定政策上界**；混用会让运维无法区分两者
  （处置完全不同），且原码的说明文本会变成不成立的说法。
- 优先级不变：新检查排在每个入口原有“可配置窗口”判定**之后**，默认 7 天更严，所以默认配置下的超龄事件
  仍返回原来的原因；新码只在运维把 `consumer-recovery-auto-replay-max-age-seconds` 调到 30 天以上时兜底。
  恰好 30 天共享 predicate 不拒，但原更严格的 7 天/TTL/年龄/未来 300s/指纹条件仍可拒。
- 时钟：各服务保持原取时方式（写侧均为 `LocalDateTime.now()`），T09 preview 整轮取一次 JVM `now` 与
  `runOnce` 共用同一 `evaluate`；租约/冷却/静默期仍用 SQL 的 DB `NOW(3)`，两者不混用；一个 `evaluate` 内
  不重复取时间。入口级用例只用“明显在内（20/29 天）/明显在外（31 天）”，纳秒级边界仍由
  `VideoStatusEventAgeGuardTests` 的固定时钟用例负责（入口用真实时钟，断言“恰好 30 天”会 flaky）。
- T09 写边界（不夸大）：判定早于 Redis 证据与发布，但拒绝路径**不是零写**——它按原协议释放租约并写冷却/诊断
  `last_error`；它**不**发布 MQ、**不**改业务计数、**不**自动把合法事件隔离成 status3。
- T09 读侧收紧（同一次 evaluate 的必然结果）：`preview.items[]` 新增 `refusalCode`；`withinSafeWindow` 从
  “只看 DB 年龄”改为“DB 年龄 + occurredAt 窗口”，`eligible` 不再在写侧会拒绝的行上为 true；
  `selectStatus0CompensationPreview` 额外读 `payload`（只读、仍有界）。preview 仍**不**访问 Redis。
  payload 解析失败、`validate()` 失败或与账本行不一致时 `evaluate` 返回 `withinSafeWindow=false`（`occurredAt`
  无从证明）并给三个固定内容码之一，不回显原始异常文本（未识别消息回落 `STATUS0_EVENT_INVALID`）；
  指纹不符（`STATUS0_FINGERPRINT_MISMATCH`）不在 preview 判定内，由 `runOnce` 在读 Redis 证据前单独校验。
- 不变：固定 30 常量与“恰好 30 天允许”边界；`VideoStatusProperties`、可调窗口与 `processed` TTL 交叉校验；
  relay（不新增拒绝）；无 schema/迁移/新 HTTP 路由。
- 可测证据（纯 Mockito，串行，`-DvideoStatusIT=false -DvideoStatusScale=false`，
  `MAVEN_OPTS='-Xms64m -Xmx256m'`、`argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'`）：
  `VideoStatusOutboxReplayBoundaryTests` 8→**11**、`VideoStatusStatus3ReplayServiceTests` 26→**28**、
  `VideoStatusStatus0CompensationTests` 31→**35**、`VideoStatusConsumptionRegistrationTests` 6→**7**；定向
  **96 tests, 0 失败/错误/跳过**（`/tmp/t14-s2-target-unit2.log`），相关类回归 **48 tests, 0 失败/错误**
  （`/tmp/t14-s2-related-unit.log`）。**未开隔离栈、未跑 IT/scale、未动容器/DB/Redis/COS**。测试环境候选，
  不是生产验收。
- 审查修复轮（同一候选，2026-10-24）：T09 `evaluate` 的 parse 失败分支由
  `new Status0Verdict(withinDbWindow, null, e.getMessage())` 改为 `withinSafeWindow=false` + 白名单原因码
  （只允许三个固定内容码，其余回落 `STATUS0_EVENT_INVALID`，不回显原始异常文本；外层 `compensateOne` 的
  `STATUS0_` 消毒器语义不同，本次未动、未合并）。新增 malformed payload / `validate()` 失败 / 账本-payload
  不一致三例（读侧 `withinSafeWindow=false`、`eligible=false`、精确码；写侧同码 `release`、不发布、不读 Redis），
  指纹不符一例（写侧 `STATUS0_FINGERPRINT_MISMATCH`、不发布、不读 Redis），并在
  `previewUsesDatabaseClockForGraceAndLeaseDecisions` 用真实合法 fixture 补正向断言
  `withinSafeWindow=true` / `eligible=true` / `refusalCode=null`；`VideoStatusStatus0CompensationTests`
  35→**39**。定向运行 **94 tests, 0 失败/错误/跳过**（`/tmp/t14-s2-review-fix.log`）；仍未开隔离栈、无
  IT/scale、无 schema/迁移/路由，候选、未父验收。
- 仍不做 / 不得声称：无归档与删除能力（dry-run 仍 `NOT_AUTHORIZED_FOR_DELETION`）；未升级的旧消费者仍会
  放行旧事件（全兼容部署才能依赖未来归档）；relay 与自动重放/恢复路径不新增拒绝；未来时间戳仍不拒绝，
  所以不是防篡改机制；首次消费前就积压超过 30 天的**合法**旧事件仍会直接进死信、不产生账本行、不自动补算
  （relay 确认状态与消费独立，不能断言它一定已 `SENT`）。
- 教程：`docs/tutorial/video-status-capacity-audit.md` §9.3.2（含 S1 审核备注）；`docs/api.md` §1.4 与 §8.8；
  `AGENTS.md`/`CLAUDE.md` 同内容。

### T11 recovery-contract 测试覆盖补充（2026-10-23，候选，仅测试作用域，本轮未实跑）

**只改 `src/test/java/.../service/video/VideoStatusIsolatedIntegrationTests.java`**：没有生产 Java、SQL、
schema、迁移、配置或 HTTP 变更；不 reset/clean/stash/commit，也不连接生产、不删除全局 dirty/门闩/`processed`。

- 把真实 `VideoStatusDirtyRecoveryScheduler` 注册进测试上下文（`TestApplication` 的 `@Bean`；该上下文没有
  `@EnableScheduling`，`@Scheduled` 不自动触发，用例显式调 `recoverDirtyVids()`），因此“恢复”走真实链路，
  而不是手工 `SADD`。
- 类内 `await(...)` 增加**超时有界诊断**：失败时打印 `vid`、`baseline`、`current`、`process_status` 0/1/2/3
  计数、dirty 归属与 dirty 基数（每段单独兜底）；不打印 payload、eventId、`last_error`、连接信息或凭证。
  所有等待仍是有界 30 秒，没有放大。
- `concurrentFlushAndConsumptionPreserveAllEvents`（4 个消费者线程 + 1 个 flush 线程）收尾从“只跑 flush”
  改为“真实 recovery + 真实 flush”：`stats.apply` 的 Lua `SADD` 与 `markRedisApplied` 的 `0→1` 不是原子的，
  落库线程在这个窗口里 `SPOP` 到该 vid 时既不会落库（`selectPendingForUpdate`/`countPendingByVid` 只看
  status 1），也不会把标记放回（生产代码从不 `SREM`），recovery 是唯一的重新唤醒路径——这与
  “recovery remains required”的既有契约一致，不是测试补丁。断言同时加强为 200 条全部 status 2 且本 vid 无 0/1 残留。
- 新增确定性用例 `dirtyMarkerLostBetweenApplyAndStatusCommitIsRecoveredOnlyByRealRecoveryScheduler`
  （每次新建 vid、不手工 `SADD`/`SREM`）：register → `stats.apply`（Redis +1、Lua `SADD`，仍 status 0）→
  真实 flush 消耗掉 dirty 标记但不落库 → `markRedisApplied`（status 1）→ 证明 DB 有 status 1 待刷而 dirty
  已缺失 → 只跑 flush 若干轮基线不变（机制上不可能收敛）→ 真实 recovery 在计算出的有界轮次
  （`ceil(全库 status-1 distinct vid / flush-dirty-batch-size) + 2`）内补回 dirty → 真实 flush 落库 →
  独立 oracle（`baseline == current == 101`、事件 status 2、`SUM(delta)` == 1）。**否决 scout 的“把
  countPending 扩成 status 0”建议**：那会改变落库协议并对尚未提交状态的事件空转。
- 边界：这是**确定性机制复现**，不是那两个历史并发错误的根因实证（历史记录见 T15 段：
  `/tmp/t15b-drill-it2.log` 的 `concurrentFlushAndConsumptionPreserveAllEvents` 30 秒未收敛）——
  原始日志没有 baseline/current/状态快照，本用例与该历史的关联仍只是假说。
- 本轮环境：隔离栈 `feibijiubi-test-mysql-1`/`redis-1`/`rabbitmq-1` 均为 `Exited (0)`（已停数小时），
  按任务约束（只对已运行隔离服务跑定向测试、不启动/重启服务）**没有实跑这两个 opt-in 方法**，只执行了
  `./mvnw -DskipTests test-compile`（成功）。因此本切片是“已实现 + 已编译”，**测试结论待父代理启动隔离栈后复跑**，
  不得写成已通过或已验收。

### 计划变更记录

- 初版：仅生成执行计划和 TODO；所有实施项均为待开始。没有执行代码修改、配置修改、迁移、测试、基础设施启动或部署。
- 2026-10-22：T11 按计划允许的“T06 后提前交付”完成验收；当时 T07 保持待验收、T08 实施中、T09 设计中，均未提前勾选（保留该历史状态）。
- 2026-10-22：T07（主分支 `95af60d`）全链路回归 87 tests、T08（主分支 `0dd98ad`/`b846032`/`92eb8c1`）候选父复验 22 tests 均通过，按测试环境验收勾选完成；当时 T09 仍仅为设计，T10 未开始，不宣称全计划或生产验收完成。
- 2026-10-22：T09 在主分支 `97c7357` 完成最终验收：单测 356 tests（0 失败/错误，108 skipped）、opt-in IT 62 tests（0 失败/错误，1 skipped），按测试环境验收勾选；无证据模式仍受控/默认关，不宣称生产验收。当时 T10 只有第一阶段 15 个单测候选、未合入；该历史状态后由 2026-10-22 T10 第一阶段合入更新，见下方记录。
- 2026-10-22：T10 第一阶段失败诊断/确认已阅与只读对账合入主分支 `6fc5e7f`，父代理实跑 opt-in IT 121 tests（0 失败/错误/跳过）与普通单测 416 tests（0 失败/错误，130 skipped）。T10 **整项仍进行中且不勾选**；DLQ/consumed status3 受控重放/批量处置/自动修复仍未实现，结果不是生产验收。
- 2026-10-23：新增 T14-A **只读**容量核查 + 归档 dry-run 候选（测试作用域工具 + 8 例隔离用例 + 13 例纯单测；普通回归 563 tests / 179 skipped）。T14 只完成“可测、可复现、零副作用”的核查部分；**保留期与重放窗口政策已于同日批准（仅决策、无实现），去重墓碑、归档执行与演练仍未做**，因此 T14 仍为进行中、不勾选；无 HTTP/schema/迁移变更，未部署。
- 2026-10-23：`VideoStatusIsolatedIntegrationTests` 补齐恢复契约覆盖（真实 recovery bean + 超时有界诊断；并发用例改为 recovery+flush 收尾；新增确定性“dirty 标记在状态提交前被落库消耗”用例）。仅测试类、`AGENTS.md`/`CLAUDE.md` 与本文档变更，无生产代码/SQL/schema 变更；**当时隔离栈未运行，两个 opt-in 方法未实跑，只 `test-compile` 通过**。后续父代理获用户许可后启动已有隔离容器（保留卷、无迁移），仅运行这两个方法：2 tests，0 failures/errors/skipped，日志 `/tmp/t18-recovery-contract-parent-it.log`；确定性用例 baseline/current 均为 101，setup flush/recovery 各一轮。这是恢复机制的定向证据，不宣称历史并发失败根因已被证明，也不是完整套件或生产验收。
- 本轮（用户确认，未新增日历日期）：T14 的保留/归档**起算点与存储**落定并核查字段，仍无任何实现。确认：在线 90 天从终态完成起算（Outbox `sent_at` = 发布 confirm 成功后回写；消费账本 `flushed_at` = 批量落库事务内写入），**不是** `created_at` / `consumed_at`；归档副本 275 天从**归档副本校验成功**起算（不是事件创建时间，归档延后时实际总保留可超过 365 天）；审计 365 天从审计行创建起算；DLQ 30 天处置 SLA 从首次入死信队列起算、重投不重置、未解决不自动删除；归档用独立私有 COS 归档桶，文件完整性 + 记录数量校验通过才允许删除在线数据，开发阶段仅隔离测试存储。字段核查（只读源码/schema）：`sent_at` 为 JVM 时钟且与 `flushed_at` 均**无索引**；`flushed_at` 在 T10-B1 重放时被清空，只表示最近一次落库完成；**DLQ 首次入队时间与归档校验记录完全不存在**；`FileStorageService` 只能同桶、只判存在、写不了 manifest，不适合归档；终态时间缺失的历史行**不得**猜成创建/接收时间或回填，只能保守排除自动删除。下一步最小切片为**建议、待父审**（S1 = 30 天旧消息拒绝入口全覆盖，relay 不新增拒绝；S2 = 归档时间字段 + 校验记录，含新索引，不做执行器）。本轮仅改文档：不改 Java/SQL/配置/schema，不执行测试/迁移/DB/Redis/Docker，未提交；T14 仍不勾选，dry-run 仍 `NOT_AUTHORIZED_FOR_DELETION`，未新建任何资源。
- 2026-10-24：T14-S1 交付**消费登记前 30 天绝对年龄拒绝**候选（`VideoStatusEventAgeGuard` + `VideoStatusConsumptionServiceImpl#register`）。
  只覆盖一个入口（消费登记），T07/T10-B1/T09 入口未接入（**同日后续由 T14-S2 接入，见下条**），因此不得声称“全入口拒绝旧消息”；既有 7 天重投守卫与管理侧
  更严的 7 天双窗口**未改**（但可配置，不能假定永远 7 天）。边界为严格 `occurredAt.isBefore(now - 30 天)`（恰好 30 天允许、
  纳秒不截断），未来时间不新增拒绝且明确**不是防篡改机制**；固定常量、无配置开关、不改 `VideoStatusProperties`/`application.yml`。
  超龄抛 `NonRetryableMessageException` → 既有 DLQ + ACK 边界（非静默 ACK），且早于任何 mapper 交互，所以已有 status1/2 账本行
  不再被静默吸收。**纠正旧建议**：“30 天必须严格小于 processed TTL”不成立（不读 `processed` 证据）。不夸大写边界：
  `register` 内无 DB/Redis 写，但全流程不是零写（DLQ 转发 + 遥测）。证据：定向 50 tests、普通回归 684 tests（0 失败/错误，201 skipped；
  `/tmp/t14-s1-target-unit2.log`、`/tmp/t14-s1-unit-full.log`），**未开隔离栈/未跑 IT/scale**。归档安全仍要求全体消费者同版本部署；
  本守卫不构成归档授权，T14 仍进行中、不勾选。
- 2026-10-24：T14-S2 把同一固定 30 天上界接到 T07 Outbox 重放、T10-B1 status3 重放（precheck/replay 同源）、T09 status0 补偿
  （preview/runOnce 同源）三个管理入口；统一原因码 `EVENT_OVER_NORMAL_REPLAY_WINDOW`（不复用可配置窗口码），新检查排在各自原有
  窗口判定**之后**（默认 7 天更严，拒绝优先级不变），且都在状态重置/审计写入/MQ 发布之前。T09 preview 新增只读 `refusalCode`、
  `withinSafeWindow` 改为“DB 年龄 + occurredAt 窗口”（与写侧一致）。**无 schema/迁移/COS/删除/新配置/新 HTTP 路由**；不改固定常量、
  原可调窗口或 TTL 交叉校验、relay。测试：定向 **96 tests**（`/tmp/t14-s2-target-unit2.log`）、`VideoStatus*Tests` **565 tests，0 失败/错误，
  181 skipped**（`/tmp/t14-s2-videostatus-unit2.log`），未开隔离栈、未跑 IT/scale。仍无归档/删除能力，旧消费者仍可绕过，
  relay/自动重放不拒绝，未来时间戳仍不拒绝；T14 仍进行中、不勾选。

## 9. 暂不纳入本轮

- 替换 RabbitMQ 为 Kafka、引入 Redis Cluster、全局重构为事件溯源。
- 为追求吞吐直接删除消费登记、幂等键、非负约束或重建门闩。
- 未测量前给出固定生产 QPS、收益倍数或“任意故障 exactly-once”承诺。
- 把已有全局 dirty/hot key 的多 key Lua 原样迁到 Redis Cluster。若未来需要 Cluster，应单独设计 hash slot/分片与热点索引协议。

这些可以在 T16 确认瓶颈后另立计划，不能替代本轮正确性和恢复闭环。
