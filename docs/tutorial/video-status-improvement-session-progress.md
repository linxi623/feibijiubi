# Improvement 续作记录（2026-10-22）

目标仍是完成 T09—T16 全部任务（T07/T08/T09 已验收，T10 进行中且第一阶段已合入）；本记录不替代主计划验收表，不代表生产上线许可。

## 暂停交接与候选快照（2026-10-28）

用户本轮要求整理现状并 commit；本次提交用于保存已有候选成果，不代表上线、全部验收或数据库迁移已执行。下方各轮记录保留历史时点，当前摘要以本节和主计划 TODO 表为准。

- 主任务共 16 项：T01—T09、T11 共 10 项已完成；T10、T12、T13、T14、T15、T16 共 6 项仍未完成，不新增勾选。
- 保存范围：T10-B1 重放、T12 分页/证据批量/replace 内部原语、T13 读一致性、T14 容量工具与年龄守卫、T15 幂等/准入/默认关闭清理、T16-B0 基线的现有源码、测试、SQL 脚本与文档。它们的候选/验收边界仍按各章节描述。
- 本轮 T13 补强：管理员详情八计数精确透传和缺行 404 两例；定向 24 tests、0 failures/errors/skipped。父代理已核对 `/tmp/t13-fix-target-unit2-A3BgFA.log` 的成功结果，独立审查无 P0/P1 阻塞；父代理随后修正文档中绝对读取方数量和 mock 测试证明范围的表述。未重跑全量回归，T13 整项仍为候选。临时日志是本机证据，不随 Git 提交持久化。
- T15 清理索引迁移和 `VideoInteractionCleanupIsolatedTests` 仍未执行；未批准本轮连接隔离库做迁移/删除。清理默认关闭，不把脚本入库当作迁移已落库。
- T16-B0 已成功测量，不应因恢复任务再造数；只代表同步准入测试环境基线，不代表并发容量、异步全链路或生产验收。
- 未解决：历史并发收敛失败根因、DLQ/刷库型 status3 处置、强制重建完整入口、归档校验与执行、安全删除、故障和上线演练。T14 的 30 天守卫已有候选实现，但归档/删除能力仍不存在；T15 清理已有候选实现但隔离验收未完成。
- 暂停不等于回滚：播放/分享新请求头契约保留；不混用旧消费者、不任意删除消费账本/processed 证据，不开启清理或强制修复，不声明生产可用性保证。
- JVM 崩溃/重放日志 `hs_err_pid*.log`、`replay_pid*.log` 留在本地，不提交、不删除；敏感 `application.yml` 不纳入本次提交。不推送远程、不操作容器、不执行迁移。
- 恢复顺序建议：先核对本快照与主计划，再申请 T15 隔离迁移/清理验收授权；若发现非本测试过期数据立即停止。其余任务按依赖逐项收口，不重复已完成的基线。

## 本轮落地

- 主分支 `95af60d`：合入 T07 发布/confirm/回写错误分类、三份独立预算及窄范围管理员审计重放。确认成功后回写持续失败不再无界重投，第 10 次进入 FAILED。人工重放仍保留原 eventId，拒绝已有 consumed 等不能证明安全的情形。
- 父代理在候选和主分支各运行一次 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests' test`：每次 61 tests，0 failures/errors/skipped。主分支日志 `/tmp/improvement-t07-main.log`。只使用独立 compose.test.yaml。
- 审查修正：confirm-writeback 迁移去掉 `AFTER lease_recovery_count`，避免不必要的脚本顺序依赖；发布前仍必须执行全部 T07 迁移。重放预算归零有真实 SQL 和隔离测试行值断言，审查提出的“只返回零但数据库未清预算”假设不成立。
- T07 已在 2026-10-22 完成测试环境验收：父代理在主分支实跑 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests,VideoStatusIsolatedIntegrationTests,VideoStatusMetricsIsolatedTests,VideoStatusSchedulingRealFlowTests,VideoStatusDirtyRecoveryRealConvergenceTests' test`，结果 87 tests，0 失败/错误，1 skipped（可选 benchmark），日志 `/tmp/improvement-main-regression3.log`。早前整组 3 failures 的历史保留在回归续作文档。
- T11 已在主仓库基线 `dc41477` 上 `git merge --squash be7d836` 暂存，并于 2026-10-22 完成测试环境验收。父代理实跑 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusDirtyRecoveryRealConvergenceTests,VideoStatusDirtyRecoveryFairnessIsolatedTests' test`：3 tests，0 failures/errors/skipped，日志 `/tmp/t11-real-convergence.log`；输出 `T11 real convergence vids=250 head=100 tail=150 flushRounds=1 oracleTotal=250`。真实 MySQL/Redis，专用头部 SADD 故障注入，四轮尾部获机会，最终 250 收敛且无 status3。候选 `be7d836` 修复了预算在首个 vid 未执行时回退/丢失游标的缺陷。该故障不是进程崩溃，结果不是生产验收。

- T09 第一阶段已合入主分支 `97c7357`（候选 `aab1c64`），并于 2026-10-22 完成**测试环境最终验收**。父代理在主分支实跑普通回归 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test`：356 tests，0 失败/错误，108 skipped，日志 `/tmp/t09-main-unit.log`；opt-in 隔离回归 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusStatus0CompensationTests,VideoStatusStatus0CompensationAuthorizationTests,VideoStatusStatus0CompensationIsolatedIntegrationTests,VideoStatusIsolatedIntegrationTests,VideoStatusOutboxReliabilityIsolatedTests' test`：62 tests，0 失败/错误，1 skipped，日志 `/tmp/t09-main-it.log`。候选早前 43 tests，0 失败/错误/跳过（含 8 真实 IT，`/tmp/t09-parent-final.log`）。默认关闭 enabled/integrity/allow-unproven 三闸门；processed 缺失是 `EVIDENCE_UNKNOWN`，不能判未应用；无证据模式仍受控/默认关，不保证任意 Redis 数据丢失；yaml 非热更新，事故需停全部补偿角色并等待 inflight/租约，不强杀 confirm。早前错开关跳过测试、mapper 参数/时区失败历史已保留，未记录凭证。
- T10 第一阶段已合入主分支 `6fc5e7f`：失败事件分页/详情/预检查/受审计确认已阅，以及单视频只读对账。父代理 2026-10-22 实跑 opt-in IT：121 tests，0 失败/错误/跳过（`/tmp/improvement-t10-main-it.log`，含 T07 replay/T09 compensation）；普通单测 416 tests，0 失败/错误，130 skipped（`/tmp/improvement-t10-main-final-unit.log`）。失败列表缺精确索引返回 503 且不扫描；确认只允许 Outbox FAILED/consumed status3，只写 before==after 审计，status0 只读且 409；对账各来源非原子，账本每状态独立 `cap=limit+1` 截断，缺 `(vid,id)` 索引只跳过事件投影，Redis 异常显式 `READ_FAILED` 不当 0，不自动修复/不证明收敛。**T10 整项仍未完成**：DLQ、status3 受控重放、批量处置未实现，不能勾选。
- T10-B1（2026-10-23，**候选，未父验收**）：consumed status3 单事件 **proven-only** 受控重放。唯一写入口 `POST /api/admin/video-status/failures/{eventId}/replay?source=CONSUMED`；同一事务内 consumed `3→0` CAS + Outbox `SENT→PENDING` CAS + `REPLAY_CONSUMED_TO_RECEIVED` COMPLETED 审计，任一步影响行数非 1 或异常整体回滚。事务内**不** publish，提交后由既有 Outbox relay 领取/confirm/退避/租约恢复投递同 eventId 同 payload，提交后崩溃仍可从 PENDING 恢复；Outbox 行缺失或非 SENT 一律 409，不降级为仅内存发送。刷库型 status3（`redis_applied_at` 非空）拒绝；processed 缺失不当作未应用；无 TTL/TTL 不足/Redis 读取异常均拒绝且不 fail-open；无 unproven 模式，不替代 T09 integrity 前提；双窗口、不改 payload/eventId/retry/age/证据；预检查与重放共用同一判定（`replay.refusalCode`）；重放预算 `status3-replay-max-attempts`（默认 3）有界计数。本块实跑：普通单测 456 tests，0 失败/错误，141 skipped（`/tmp/t10b1-unit.log`）；隔离 IT `VideoStatusStatus3ReplayIsolatedTests` 11 tests，0 失败/错误/跳过（`/tmp/t10b1-it.log`，含真实 relay/消费者/刷库收敛一次与真实事务回滚）；T10 组回归 100 tests（`/tmp/t10b1-t10-group.log`）。**已知既有失败（非本块引入）**：`-DvideoStatusIT=true` 下 `VideoStatusIsolatedIntegrationTests` 的 `concurrentColdStartAndDuplicateDeliveryConverge` 与 `concurrentFlushAndConsumptionPreserveAllEvents` 报 2 errors（该类 17 tests，0 failures）；未修改 HEAD 的基线整组为 1 failure/2 errors/1 skipped（`/tmp/t10b1-baseline.log`，唯一 failure 是下述 lease fixture 问题），并发用例与本块改动无关，本块未修复。`VideoStatusOutboxLeaseTests.roundBudgetSmallerThanConfirmTimeoutIsStillAccepted` 的失败根因是测试 fixture 只设了 confirm=30 / outbox 租约=35，漏设 `status0CompensationLeaseSeconds`（默认 30 < 35，先被补偿租约校验拒绝），已在 fixture 补 35 后通过，生产校验代码未改、未放宽。T10 仍不能勾选；DLQ、批量、刷库型 status3、自动修复仍欠。
- T10-B1 收口（2026-10-23）：父代理把 `VideoStatusStatus3ReplayService#evaluate` eligible 分支的 `redisAppliedEvidencePresent` 从写死的 `true` 改为 `redisEvidence`（eligible 路径必然 `false`），并在 `VideoStatusStatus3ReplayServiceTests` 与 `VideoStatusStatus3ReplayIsolatedTests` 各加 `assertFalse`；只影响预检查 `replay` 子结构展示字段，不改拒绝判定。父代理窄范围复跑：`./mvnw '-Dtest=VideoStatusStatus3ReplayServiceTests,VideoStatusFailureAdminAuthorizationTests,VideoStatusFailureAdminServiceTests' test` 为 54 tests，0 失败/错误/跳过（`/tmp/t10b1-parent-unit.log`）；`./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusStatus3ReplayIsolatedTests' test` 为 11 tests，0 失败/错误/跳过（`/tmp/t10b1-parent-it.log`）。以上只证明 B1 窄范围用例通过，**不构成 B1 最终验收，也不代表 T10 全组或生产验收**。
- B1 三预算运维说明（本轮文档）：保留 Outbox 三份预算，临近耗尽时重放后再失败一次会快速 FAILED；此时 T07 单事件 Outbox 重放因“存在消费账本”一律 409，不能作为兜底；必须停下诊断，不允许清零预算、改状态或换 eventId。已写入 `docs/tutorial/video-status-status3-replay.md`（3.3、8 节）与 `docs/api.md` 10.5。
- T12-A（2026-10-23，**候选，未父验收**）：重建分类的 processed 证据改为有界批次 pipeline 查询。`VideoStatusRebuildServiceImpl` 按快照顺序每 256 条一批（`PROCESSED_EVIDENCE_BATCH_SIZE`）只为 `process_status=0` 候选构造本批 key，调用新增的 `RedisUtils#hasKeys` 一次 pipeline `EXISTS` 取回逐 key 结果并按位置回填；`process_status=1` 不再查询 Redis；批内无 status0 候选不发请求，不构造全量候选 key 集合。`hasKeys` 对 null 返回/条数不符/未知元素抛 `RedisOperationException`，分类路径再拒绝 null 元素，未知不等于“未应用”；失败发生在 `initializeRedis` 之前，无半初始化，`finally` 仍用同一 token 释放门闩，vid 锁/无 TTL 门闩/token-owned release/初始化 keys+args 形状不变。本块实跑：`./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` 为 474 tests，0 失败/错误，142 skipped（新增 `VideoStatusRebuildProcessedEvidenceTests` 9 例与 `RedisUtilsTests` 8 例 pipeline 用例）；隔离 IT `./mvnw -DvideoStatusIT=true -Dtest='VideoStatusIsolatedIntegrationTests#rebuildCountsProcessedEvidenceAcrossBatchBoundary' test` 1 test 通过（真实 MySQL 13307/Redis 16379，300 条 status0 跨 256 边界 + 40 条 status1 重建前删除 processed key，current=100+40+180=320，事件状态不变）。**未完成**：`selectRebuildCandidates` 仍全量加载候选（无分页/流式/水位），0/1,000/100,000 规模的内存/Redis 往返/P95 重建与门闩持续时间未测量，超预算中止与告警、“已存在但错误”的强制重建（权限/审计/并发协调）及重建 HTTP 接口均未实现；T12 整项不能勾选，也不是生产验收。
- T12-B（2026-10-23，**候选，未父验收**）：MySQL 重建候选读取有界化。`selectRebuildCandidates`（无 LIMIT 全量）
  改为 `selectRebuildCandidatesAfter(vid, afterId, limit)`；`VideoStatusRebuildSnapshotServiceImpl#load` 在同一个
  `@Transactional(readOnly=true, isolation=REPEATABLE_READ)` 内先读基线、再按 `id > afterId` 读到空页/短页，
  页大小默认/上限 1000，不用 OFFSET，基线与所有页共享同一 InnoDB 一致性快照。SQL 显式
  `FORCE INDEX (idx_video_status_consumed_vid_id)`：不加提示时优化器选 `(process_status, vid, id)` 两区间 +
  filesort，每页重读该 vid 全部候选（实测 300 条候选 `LIMIT 100`：无提示读 300 条索引项，加提示只读 140 条）；
  该索引由 T10 对账迁移提供并在 schema 镜像，**无新迁移**。候选仍是 DTO list，上界
  `rebuild-max-candidates`（默认 100000）显式配置，超上限抛 `RetryableMessageException` 且不返回截断快照；
  快照读取预算 `rebuild-snapshot-budget-ms`（默认 10000）用 `System.nanoTime()` 在页间与逐条转换前检查，
  超预算失败但不中断已发出的 SQL。锁、无 TTL 门闩、同 token 释放、初始化 keys/args、T12-A 证据批次、
  计数聚合语义均不变；失败都在 `initializeRedis` 之前，无半初始化，下次重建新 token 接管。本块实跑：
  普通单测 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` 497 tests，0 失败/错误，144 skipped
  （`/tmp/t12b-unit-final.log`，新增 `VideoStatusRebuildSnapshotPagingTests` 12 例、`VideoStatusRuntimePropertiesContextTests`
  4 例、`VideoStatusRebuildProcessedEvidenceTests` 1 例）；隔离 IT `-DvideoStatusIT=true -Dtest=VideoStatusIsolatedIntegrationTests`
  20 tests，0 失败/错误，1 skipped（`/tmp/t12b-it-class-final.log`，含新增 1000 条候选同事务 RR 分页与超上限接管两例）。
  **未完成**：0/1,000/100,000 规模性能验收（峰值内存、Redis 往返、P95 重建与门闩持续时间，未测量也不编造）、
  强制重建（权限/审计/并发协调）、重建 HTTP 接口、deadlock 专项与跨批水位协议；T12 整项不能勾选，也不是生产验收。
- T12 验收收口与规模测量准备（worker，2026-10-23）：保留 T12-A/T12-B 全部实现，只做“收口 + 测量 + 文档”。
  父代理已复跑验证：普通回归 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` 为 **497 tests，0 失败/错误，
  144 skipped**（`/tmp/improvement-parent-unit-final.log`）；opt-in 隔离
  `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusIsolatedIntegrationTests,VideoStatusStatus3ReplayIsolatedTests' test`
  为 **31 tests，0 失败/错误，1 skipped**（20 例全链路 + 11 例 B1，`/tmp/improvement-parent-t12b-it.log`）。
  本轮加固：`VideoStatusRebuildSnapshotServiceImpl#loadCandidates` 增加**行级 id 校验**（页内每行 id 必须存在且
  严格递增，`缺少 id 游标`/`id 未严格递增`），不改事务协议、锁、门闩与初始化参数；`VideoStatusRebuildSnapshotPagingTests`
  12 → **16 例**（新增 null 页、末行缺 id、页内 id 乱序、第二页超页大小）。新增 opt-in 规模测量类
  `VideoStatusRebuildScaleIsolatedTests`（默认 skipped，仅 `-DvideoStatusScale=true`，复用现有 IsolationGuard，
  只连 compose.test.yaml，不启监听器、不改事件状态；造数用 500 行多行 INSERT + 1000 key Redis pipeline）：
  0/1,000/100,000 候选各 3 次冷重建，预设 oracle（`current=100+候选数`，交叉核对 DB 行数/去重 eventId/`SUM(delta)`/
  其余七计数/事件状态）每次重复断言。实测（`/tmp/t12-scale-full.log`，52.98 s，0 失败/错误）：0 → 18/18/22 ms；
  1,000 → 164/190/379 ms（4 次 pipeline / 1,000 条 EXISTS）；100,000 → 7,789/8,143/9,649 ms（391 次 pipeline /
  100,000 条 EXISTS），门闩持有与重建耗时几乎相同，heap 池峰值增量 12/109/241 MiB。**口径与限制**：pipeline 数是
  逻辑次数不是物理 RTT；heap 是池峰值之和（上界近似，不是候选 retained size）；每规模 3 样本，P95 输出 `n/a` 仅诊断；
  单机单 JVM、无并发/故障注入，测量把读取预算放宽到 1 小时，**不是性能验收**。**风险**：默认
  `rebuild-snapshot-budget-ms=10000` 与默认 100000 上限同一量级，接近上限规模的冷重建可能触碰预算；未在默认预算下
  跑 100,000，故不断言必然失败，生产前必须按实际规模/硬件重估。**部署硬依赖**：T12-B 上线前必须先执行
  `database/migrations/t10-video-status-reconciliation-indexes.sql`（`FORCE INDEX` 使该索引成为冷缓存重建硬依赖；
  缺索引 MySQL 报 1176，重建在 `initializeRedis` 之前失败、消费链路重试→DLQ；超候选上限同样重试→DLQ，
  **无自动修复**，需运维停写/调配置/重启）。本轮复验：普通回归 **504 tests，0 失败/错误，147 skipped**
  （`/tmp/t12b-worker-unit.log`）；`VideoStatusIsolatedIntegrationTests` **20 tests，0 失败/错误，1 skipped**
  （`/tmp/t12b-worker-it.log`）。历史保留：T10-B1 记录的两个并发用例间歇性 2 errors 仍未定位/未修复，
  本轮两次隔离运行均通过但**不得写成已解决**；T12-A 当时 `RedisUtilsTests` 新增 **8 例**，后续加固又补 **4 例**
  严格回复类型用例（当前累计 12 例），历史数字不改。
- T12 恢复轮（worker，2026-10-23）：上一轮 runner 进程因 JVM 原生内存不足（无法 mmap）退出且未写报告；崩溃日志未回显、未提交，未跟踪的 `hs_err_pid*.log` 保持未跟踪。本轮未 reset/clean/stash/commit，也未改生产代码，只做只读检查 + 串行复验 + 文档同步：确认无遗留 java/mvn 进程、空闲物理内存约 11 GB 后才开始。`MAVEN_OPTS='-Xms64m -Xmx256m' ./mvnw -DskipTests compile` 通过；`-DargLine=-Xms64m -Xmx512m` 下 `VideoStatusRebuildSnapshotPagingTests` **16 tests，0 失败/错误/跳过**；`-DvideoStatusScale=true` 规模类 **3 tests，0 失败/错误/跳过**（`/tmp/t12-scale-recovery.log`，43.76 s）：0 → 17/19/24 ms，1,000 → 179/184/405 ms，100,000 → 5,349/5,609/6,152 ms，391 次 pipeline / 100,000 条 EXISTS，heap 池峰值增量 131.5/182.8/174.8 MiB，预设 oracle 100/1100/100100 全部命中。全量普通回归与隔离 IT 沿用父代理 497/31 与本轮 504/20 记录，本轮未重跑，故不新增数字。文档同步：`AGENTS.md`/`CLAUDE.md` 的 T12-B 段补上行级 id 校验、16 例分页用例、规模测量类与指标口径、父/本轮复验数字、T10 索引硬依赖与间歇并发问题未修复；T12 仍不勾选（强制修复与生产容量验收未完成）。
- B1 收口验证（worker，2026-10-23）：`VideoStatusOutboxLeaseTests#roundBudgetSmallerThanConfirmTimeoutIsStillAccepted` 单独实跑通过（`/tmp/t10b1-worker-lease-after.log`）；普通单测回归 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` 为 456 tests，0 失败/错误，141 skipped（`/tmp/t10b1-worker-unit.log`，skipped 均为 opt-in 隔离开关关闭的用例），未出现新的失败类别。
- T12-C1 内部原语（worker，2026-10-23，**候选、未开放、未父验收**）：新增 `src/main/resources/lua/video-status-replace.lua` 与 `RedisScriptConfig#videoStatusReplaceScript`（`DefaultRedisScript<String>`）。keys/args 与 `video-status-init.lua` 完全一致，语义相反：init 要求 current **不存在**，replace 只允许 current **已存在且完整**（冷 miss → `CURRENT_MISSING`，绝不顺手初始化）。校验顺序：门闩 token → current 类型 → dirty/hot 类型 → current 无 TTL → vid → hot 有限 → 八计数 canonical ≤ `Long.MAX` → `hasPending` → 已有字段集合 → 已有取值 → vid 一致；拒绝码 `STALE_REBUILD`/`CURRENT_MISSING`/`INVALID_REDIS_TYPE`/`CURRENT_HAS_TTL`/`INVALID_ARGUMENT`/`COUNTER_OUT_OF_RANGE`/`UNEXPECTED_FIELDS`/`CURRENT_INVALID_VALUE`/`VID_MISMATCH`，**全部在第一条写命令之前，拒绝即零写入**。未知字段不用无界 `HKEYS`：`HLEN` 与固定上限 9 比较 + 九个已知字段逐个 `HGET`（命令数固定 10）；已有取值也必须是合法 current，否则 `CURRENT_INVALID_VALUE`。写入：一次 `HSET` 九字段 → 仅 `pending=true` 时 `SADD dirty`（`false` **不** `SREM`）→ `ZADD hot`；三条写各自 `pcall`，失败返回阶段码 `REPLACE_HSET_FAILED`/`REPLACE_DIRTY_FAILED`/`REPLACE_HOT_FAILED`，成功 `REPLACED`。**不承诺回滚**：Lua 串行执行≠事务，运行时错误下前序阶段可能已写入，阶段码表达“执行到哪一步、哪一步未知”；也不用“读回相等”证明执行成功。不 DEL current、不改 processed 及其 TTL、不释放门闩（旧 token 永远 `STALE_REBUILD`）、无 unproven 模式。**没有 HTTP 入口、没有 Java 业务调用者、没有注册自动调用**。实跑：opt-in `VideoStatusReplaceLuaIsolatedTests` **24 tests，0 失败/错误/跳过**（`/tmp/t12c1-replace-it2.log`，真实 Redis 16379，脚本经 bean 执行），覆盖正常替换九字段+hot、`pending=false` 不动 dirty 与其它 vid、processed/TTL 不变、冷 miss/类型/TTL/未知字段/缺失字段/损坏取值/旧 owner/缺门闩/vid 不一致/非法参数、`Long.MAX` 与 `2^53+1` 精确、`Long.MAX+1` 越界，以及 ACL 仅对专用临时用户禁用 `HSET`/`SADD`/`ZADD` 的真实运行时部分失败（阶段码 + “Hash 已写/dirty 未写/hot 未写”的既成事实）；普通回归 `528 tests，0 失败/错误，171 skipped`（`/tmp/t12c1-unit3.log`，较上轮 504/147 正好多出本类 24 例 skip）。环境记录：本机 JDK 21 下 Mockito inline mock maker 必须加 `-Djdk.attach.allowAttachSelf=true`，否则提交内存受限时 ByteBuddy 外部进程 attach 失败，整轮回归会误报大量 `Could not initialize plugin: interface org.mockito.plugins.MockMaker`（与被测代码无关）；期间为腾出提交内存曾 `stop` 测试用 mysql/rabbitmq 容器，运行后已 `start` 恢复（数据卷未动）。教程：`docs/tutorial/video-status-replace-primitive.md`。**T12 仍不能勾选**：强制重建权限/审计/并发协调、崩溃恢复、完整性校验与生产容量验收仍缺。
- T13 读路径模式 B（worker，2026-10-23，**候选、未父验收、T13 不勾选**）：父决策采用计划允许的模式 B——
  面向用户的统计读继续以 MySQL `video_status` 为唯一来源，不引入 Redis 实时读，不在 read miss 同步触发重建，
  不为测试放宽 DB 错误守卫，也不为“覆盖 Redis”新增 Redis 调用。代码核查：详情 `selectByVid`、Feed
  `selectFeed`（单条 JOIN 投影，仅 play/comment）、获赞 `countLikeByUid`（`SUM(like_times)`）、管理员详情
  `selectByVid`，四个入口背后的三个服务类均无 Redis 依赖（新增反射守卫）；`VideoStatusServiceImpl#getByVid` **无调用方**，当时被记为
  “唯一 Redis 统计读”——该表述已被 2026-10-28 收口修复更正，见下一条：管理端只读对账也会采样 `current`。
  其行为：miss 回落 DB 且不回填/不取锁/不重建，坏字段显式异常，Redis 故障 `RedisOperationException`
  透传，不读门闩，不伪造 0。口径差异如实记录而非统一：详情缺行 `500 视频统计数据异常`、Feed 缺行整条被
  JOIN 丢弃、`loveCount` 只聚合过审+公开+未删除视频的 `like_times`（不减 unlike、空集为 0）、管理员详情缺行
  `404 视频状态为空`。延迟为最终一致、**无健康时延 SLA**：正常量级约 1～2 s 由 `outbox-fixed-delay-ms=1000`
  + `flush-fixed-delay-ms=500` + 处理推算（非实测），重试 10/30/120 s 与堆积可到分钟级；T05 的 15 s 采样、
  120 s 最老待处理、30 s 心跳仅诊断阈值。热榜 ZSet `feed:hot:videos:v1` **延期保留**：三处写入、零读取、
  无 TTL、删除不 `ZREM`、累计分无时间衰减、内存 O(视频数)，当前用户可见收益 0；接入或删除需独立变更。新增
  `VideoStatusReadConsistencyTests` **14 例**（纯 Mockito：详情/Feed/获赞的正常/缺行/DB 异常、结构守卫、
  miss/门闩/坏字段/Redis 故障、VO 字段面与 Mapper XML 单 `<select>` 口径）；普通回归
  `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` **542 tests，0 失败/错误，171 skipped**
  （`/tmp/t13-unit-worker.log`，`MAVEN_OPTS='-Xms64m -Xmx256m'`、`argLine='-Xms64m -Xmx512m
  -Djdk.attach.allowAttachSelf=true'`，未启动隔离 compose，未跑 `BackendApplicationTests`，日志无密码）。
  教程 `docs/tutorial/video-status-read-consistency.md`，同步 `docs/api.md`、计划表与 T13 决策段。
  **未完成**：端到端 DB 可见延迟实测、生产容量、热榜读/删、`unlike_times` 读面、`getByVid` 接线；T12 仍不勾选、
  T10 仍进行中。另记录父代理 T12-C1 窄范围复跑：`./mvnw -DvideoStatusIT=true
  -Dtest=VideoStatusReplaceLuaIsolatedTests test`（实际 `argLine` 受限堆 `-Xms64m -Xmx512m
  -Djdk.attach.allowAttachSelf=true`）= **24 tests，0 失败/错误/跳过**（`/tmp/t12c1-parent-it.log`），
  仅为原语验证，**不是 T12 验收**。
- T13 收口修复（worker，2026-10-28，**仍是候选、未父验收、T13 不勾选**）：只改测试与文档，**未改生产 Java**。
  先用源码核实：`VideoStatusServiceImpl#getByVid` 仍然**没有调用方**（`grep` 只命中接口/实现/测试），但
  `VideoStatusReconciliationServiceImpl#readRedisSample` 也会 `multiGet` 采样 Redis `current`（只读对账诊断，
  不写 Redis/MySQL、不领租约、不改事件状态，输出不进入用户可见统计响应）。因此原“`getByVid` 是唯一 Redis
  统计读 / 唯一读 Redis 的入口”的说法是错的，已在教程、`docs/api.md`、`AGENTS.md`/`CLAUDE.md`、计划段统一改为：
  用户可见业务统计仍全部来自 MySQL；`getByVid` 是无调用方的只读门面，不能叫“用户读入口”；管理端对账另有
  只读 Redis 采样。测试补 2 例（`VideoStatusReadConsistencyTests` 14→**16**）：管理员详情**八项计数精确透传**
  （`playTimes=2^53+1=9007199254740993`、`likeTimes=Long.MAX_VALUE`，私密视频也照常返回以证明不做可见性过滤，
  且 `selectByVid` 只调一次）与统计行缺失时 `404 视频状态为空`、`userMapper`/`userFollowMapper` 零交互、
  `countVideoByUid` 从未调用。实跑：
  `./mvnw -B -DvideoStatusIT=false -DvideoStatusScale=false -DvideoStatusBaseline=false
  -DargLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'
  -Dtest=VideoStatusReadConsistencyTests,VideoStatusCounterReadTests test` = **24 tests，0 失败/错误，0 skipped**
  （`VideoStatusReadConsistencyTests` 16 + `VideoStatusCounterReadTests` 8；`MAVEN_OPTS='-Xms64m -Xmx256m'`
  串行，末次加 `-o` 离线复用本地依赖），日志 `/tmp/t13-fix-target-unit2-A3BgFA.log`（同轮首跑 `/tmp/t13-fix-target-unit-E1ZD6a.log` 结果相同）。本轮**未**重跑整包普通回归（542 tests 仍属 2026-10-23 历史
  数字）、未启动 Docker/隔离 compose/外部 MySQL/Redis/COS、未执行迁移、未启动 Spring 全应用、未读 `application.yml`
  或秘密环境。**未完成/风险**：`getByVid` 仍无调用方；真实 HTTP 端到端 DB 可见延迟仍未实测；用户详情 500 与
  管理员详情 404 的文案差异保留；T13 仍需父代理独立复验后才谈验收，T12/T14/T15 的协议与状态不因本条改变。

- T14-A 只读容量核查 + 归档 dry-run（worker，2026-10-23，**候选、未父验收、T14 不勾选**）：只交付**测试作用域**
  的只读工具，没有生产代码、没有 HTTP 接口、没有调度器、没有 schema/迁移、未部署。代码在
  `src/test/java/com/feibijiubi/backend/service/video/capacity/`：`VideoStatusCapacityAudit`（只读 IO）、
  `VideoStatusCapacityEstimator`（公式与 UNKNOWN 语义）、`VideoStatusCapacityReport`（模型 + 中文渲染）；
  用例 `VideoStatusCapacityEstimatorTests` 11 例、`VideoStatusCapacityReportTests` 2 例、
  `VideoStatusCapacityAuditIsolatedTests` 8 例（opt-in，仅 `compose.test.yaml` 的 MySQL 13307 / Redis 16379，
  启用前 `IsolationGuard` 拒绝任何端点覆盖）。可测数据=`information_schema` 体积/行数**估算**（明确不执行
  `COUNT(*)`）+ Redis `INFO memory`/`CONFIG GET maxmemory*`/`DBSIZE` + 有界 `SCAN`（访问 key 预算）+ `MEMORY USAGE`
  抽样 processed 单键字节；终态 dry-run 只列 id/时间/状态，`FORCE INDEX` + 时间窗 + `LIMIT 上限+1`，先校验
  `(status, created_at, id)`/`(process_status, consumed_at, id)` 列序再要求 `EXPLAIN key=该索引且 type!=ALL`，
  缺索引/访问路径不符一律 `NOT_EVALUABLE` 且不执行查询、不降级全表扫描（本切片不新增迁移，索引由 T05 迁移提供）。
  **禁删判定**：只有终态（outbox `SENT=2`、consumed `FLUSHED=2`）进候选，`status0/1/3` 与 FAILED 永不列，
  `Window` 拒绝 `terminalStatus!=2`；**终态也一律 `NOT_AUTHORIZED_FOR_DELETION`**——保留期政策虽已于 2026-10-23
  批准（见后文决策条），但归档/删除执行器、归档成功校验、旧消息硬拒绝或去重墓碑、30 天重放守卫都还没有实现，
  所以“processed 还有 TTL”不是删除 DB 幂等证据的依据；报告固定 `writePerformed=false`/`deletionAuthorized=false`/
  `RETENTION_PERIOD_UNDECIDED`（工具代码未改，此处含义是“清理执行与安全证明未实现”，不是“政策未决策”）。
  **只读证据**（真实隔离断言）：
  审计前后被采样行 `SELECT *` 快照相等、MySQL 写语句计数（`SHOW GLOBAL STATUS` 的 Com_*）不变、Redis 写命令计数
  （SET/HSET/SADD/ZADD/DEL/EXPIRE/CONFIG SET/FLUSHALL/FLUSHDB）不变、`DBSIZE` 不变、门闩仍无 TTL 且值不变、
  processed 值与 TTL 只随时间衰减。**容量口径**：可测数据与配置假设（`-DvideoStatusCapacityAssumedDailyEvents`、
  `-DvideoStatusCapacityAssumedRetentionDays`，默认 0=未提供）严格分开；DB 日增给区间（点估计 ×0.5～×1.5），
  样本 <5 个 processed key、假设日事件量未给、未提供假设保留天数、`maxmemory=0` 全部 UNKNOWN 并附原因，**绝不回落 0**。
  实跑：普通回归 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` **563 tests，0 失败/错误，179 skipped**
  （`MAVEN_OPTS='-Xms64m -Xmx256m'`、`argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'` 串行）；
  隔离 `./mvnw -DvideoStatusIT=true -Dtest=VideoStatusCapacityAuditIsolatedTests test` **8 tests，0 失败/错误/跳过**，
  生成临时路径 dry-run 报告（示例 `%TEMP%ideo-status-capacity-audit-<random>.txt`）。该次隔离样本（仅诊断，非生产结论）：
  outbox 估算 5384 行/3.65 MB、consumed 29523 行/27.30 MB、`used_memory≈9.95 MB`、`DBSIZE=34765`、`maxmemory=0`/
  `noeviction`、processed 单键 104～120 B；假设 100,000 事件/天 → DB 日增 80.1～240.3 MB/天、Redis 日增
  10.4～12.0 MB/天、30 天保留 312～360 MB，**Redis 耗尽天数 UNKNOWN**（未设 maxmemory）。报告不含连接串/主机/
  账号密码/完整 eventId/payload/last_error（隔离用例用随机 secret 做负向断言）。教程
  `docs/tutorial/video-status-capacity-audit.md`（含 noeviction 告警与安全恢复：扩容/降载/停写排空，禁止删门闩、
  删 processed、FLUSHALL、改 maxmemory-policy），`docs/api.md` §1.4 注明无新增 HTTP 接口。**未做（阻塞 T14）**：
  归档/删除/30 天守卫的任何实现（政策已于 2026-10-23 批准，见下一条）、归档成功校验、去重墓碑或旧消息硬拒绝、
  小批限速断点续跑的归档执行器、归档中断重跑与归档后重放不重复计数的演练、索引与在线查询锁影响验证、
  生产容量与外部告警。**T14 整项不能勾选**，也不是生产验收。
  关联记录：父代理 T13 窄范围复跑 `VideoStatusReadConsistencyTests` = **14 tests，0 失败/错误/跳过**
  （`/tmp/t13-parent-unit.log`），只证明该单测类通过，**不构成 T13 全链验收**，T13 仍为候选。
- T14 保留期与重放窗口政策（2026-10-23，用户批准，**仅决策、无实现**）：成功终态 Outbox（`SENT=2`）与消费账本
  （`FLUSHED=2`）在线保留 **90 天**，归档副本再保留 **275 天**（合计 365 天）；DLQ **30 天内必须处置**，是处理 SLA
  **不是 TTL**，未解决不自动删除、不配置自动过期；操作审计 **365 天**；最大**普通**事件重放窗口为事件发生后
  **30 天**，超过转人工核查并拒绝普通重放；30 天以内仍须满足全部现有 age、`processed` TTL 与证据约束，
  T07/T09/T10 的拒绝条件一条都不放宽。T15 的 `video_interaction_request` / `video_interaction_window` **不纳入**
  （这两张表的保留期由 T15 自己的 30 天清理切片单独决定，见下方 T15 条目）；此处当时记录的“同一 `requestKey`
  永不重复计数”已被该切片取代为“记录还在即重放，清理删除后同键按新请求处理、仍受 30 分钟窗口约束”。**本轮只同步文档**：Java/SQL/配置/schema/迁移均未改，未执行测试、
  DB/Redis/Docker 操作，未提交；没有归档执行器、没有删除代码、没有 30 天新守卫，dry-run 仍输出
  `NOT_AUTHORIZED_FOR_DELETION`。生产清理前置（必须先实现并验收）：旧消息硬拒绝或去重墓碑、仅终态、安全归档确认、
  限速分页断点重跑、归档后重放不重复计数（含 30 天守卫本身）演练。仍未定、需实现前固定并记录理由：90 天按终态
  完成时间（`sent_at`/`consumed_at` 一类）还是 `created_at` 起算、归档副本的持久化存储与“归档成功”校验方式、
  审计 365 天的时间基准（及派生的 DLQ 30 天 SLA 计时起点等执行细节）。**T14 仍不勾选。**
  （该条所列“仍未定”的起算点/归档存储/审计基准已由下一条“T14 起算点与归档存储确认”取代；其“合计 365 天”是当时
  两句期限的算术相加，**不是**从事件创建固定 365 天——两段起算点不同、归档延后时实际总保留更长，以下一条为准；
  历史记录保持原样不改。）
- T14 起算点与归档存储确认（本轮用户确认，**未新增日历日期；仅决策、无实现，本轮只改文档**）：
  在线 90 天从**终态完成**起算——Outbox `sent_at`（`VideoStatusOutboxMapper#markSent`，发布 confirm 成功后由
  `VideoStatusOutboxRelay#handleConfirmed` 回写）、消费账本 `flushed_at`（`markFlushed` 在批量落库事务内写 `NOW(3)`），
  **不是** `created_at` / `consumed_at`；归档副本 275 天从**归档副本校验成功**起算（不是事件创建时间，也不从
  “在线 90 天结束”自动顺延，因此归档延后时实际总保留可超过 365 天，**不得**写成从事件创建固定 365 天）；
  审计 365 天从**审计行创建**（`video_status_consumption_repair_log.created_at`）起算；DLQ 30 天处置 SLA 从**首次进入
  死信队列**起算，**重投不重置、未解决不自动删除**；归档落在**独立私有 COS 归档桶**，只有**文件完整性 + 记录数量
  校验通过**才允许删除在线数据，**开发阶段只允许隔离测试存储**。已有批准不变：普通重放窗口 30 天、所有更严格守卫保留、
  T15 两表不纳入。**字段核查（只读源码/schema，未读 `application.yml`、未连环境）**：`sent_at` / `flushed_at` 都存在且语义正确，
  但 `sent_at` 写的是 **JVM 时钟**（`LocalDateTime.now()`），两列**都没有索引**（现有 `(status, created_at, id)` /
  `(process_status, consumed_at, id)`）；`flushed_at` 在 T10-B1 重放（`resetRepairToReceived`）会被清空，只能表示
  “最近一次落库完成”；`consumed_at` 是 `insertReceived` 的 `NOW(3)` 登记接收时间，**不是**落库完成时间；审计行
  `created_at`/`completed_at` 都写 `NOW(3)`（无 STARTED 写入路径），**无字段缺口**；**DLQ 首次入队时间完全不存在**
  （只有 durable 队列 `video.status.dlq.v1`，无消费者、无 DB 表，broker 死信元数据代码不读不落库）；**归档校验记录完全
  不存在**；`FileStorageService` 只能同桶 `copyObject`、`objectExists` 只判存在、写不了 manifest，**不适合**归档。
  **终态时间缺失的历史行不得猜成 `created_at`/`consumed_at`、不得回填伪时间，只能保守排除自动删除。**
  **下一步最小可测试切片（建议、待父审，本轮不实现）**：S1 = 30 天旧消息拒绝入口全覆盖（单一窗口判定被消费应用、
  T07 Outbox 重放、T10-B1 status3 重放、T09 status0 补偿重发四处共用；消费侧现有唯一年龄守卫只在重复/重投路径
  `handleReceived`、基准是 `consumed_at` 且依赖账本行存在，首次登记/应用无守卫；**relay 不新增拒绝**，否则重放重置的行会被静默丢弃；
  边界：默认 `redisEventTtlDays=30` 与 30 天同量级，需按“严格小于 processed TTL”落地，属待父裁定的产品/配置决策）；
  S2 = 归档时间字段 + 校验记录（下一轮才建 `(status, sent_at, id)` / `(process_status, flushed_at, id)` 索引与归档记录表，
  只做“可证明”，**不做执行器/删除/限速分页/断点续跑/生产桶**）。T14-A dry-run 工具本轮**未改**，时间列仍是
  `created_at`/`consumed_at`，因此**不是**批准口径的实现。本轮仅改文档：不改 Java/SQL/配置/schema/迁移，不执行测试/迁移/
  DB/Redis/Docker/COS，不新建任何资源，未提交；dry-run 仍 `NOT_AUTHORIZED_FOR_DELETION`；**T14 仍不勾选**。
- T14-S1 消费登记前的 30 天绝对年龄拒绝（worker，2026-10-24，**候选、未父验收、T14 不勾选**）：**一个入口、一个判定、一个常量**。
  新增 `VideoStatusEventAgeGuard`（唯一判定 + `MAX_NORMAL_REPLAY_AGE_DAYS = 30` + 固定安全原因常量）；
  `VideoStatusConsumptionServiceImpl#register` 在 `event.validate()` + `validatePayloadHash(...)` **之后**、
  任何 mapper/序列化**之前**调用它，超龄抛 `NonRetryableMessageException`，走消费者**既有**
  NonRetryable → 死信转发 + ACK 边界（**不进 retry 队列、不是静默 ACK**；消费者异常分类/ACK 边界未改）。
  边界是显式数学定义：严格 `occurredAt.isBefore(now.minusDays(30))` 才拒，**恰好 30 天允许**，纳秒不截断；
  未来时间**不新增拒绝**（保留既有行为，且明确记录**不是防篡改机制**）；缺 `occurredAt` 仍由既有 `validate()` 拒；
  payload/指纹不变。因为守卫早于任何 mapper 交互，**已存在 status1/2 账本行的重复事件同样被拒**，
  不再走 `handleDuplicate` 静默返回 `REDIS_ALREADY_APPLIED`/`ALREADY_FLUSHED`；**未新增** `handleReceived` 调用；
  既有 7 天重投守卫不变（默认 7 天更严，但**可配置**、上限受 processed TTL 约束，**不能假定永远 7 天**）。
  组件用 `Clock.systemDefaultZone()`（与业务侧 `LocalDateTime.now()` 无时区语义一致）+ 可注入 `Clock` 的测试构造；
  `LocalDateTime` 语义不变。**固定常量、无放宽开关**：不改 `VideoStatusProperties`、不改 `application.yml`、
  不新增配置项。**纠正旧建议**：原“30 天必须严格小于 processed TTL”**不成立**——该守卫是纯事件年龄上限，
  从不读 `processed` 证据，与 `redisEventTtlDays` 无先后约束；仍保留“严格小于 TTL”的只有真正依赖
  `processed` 的 7 天自动重放与 T09 窗口。**不夸大写边界**：`register` 内无 MySQL/Redis 写（`REQUIRES_NEW` 空回滚），
  但全流程**不是零写**（消费者转发 DLQ 是 broker 写，遥测记一次失败）。**部署/归档前提**：旧消费者仍会放行
  旧事件，归档安全要求**全体消费者同版本部署**；混版本后删归档终态行或回滚旧消费者会重新放行；本守卫本身
  **不构成**归档授权，dry-run 仍 `NOT_AUTHORIZED_FOR_DELETION`。**未覆盖**：T07 Outbox 重放、T10-B1 status3 重放、
  T09 status0 补偿三入口未接入，管理侧更严的 7 天双窗口守卫保留，所以不得声称“全入口拒绝旧消息”。
  测试：`VideoStatusEventAgeGuardTests` 9 例（固定时钟：窗口内/恰好 30 天/±1 纳秒/超 1 秒/未来/默认时区墙钟一致）、
  `VideoStatusConsumptionRegistrationTests` 6 例（超龄零 mapper 交互、已有 status1 行既不查也不改、FLUSHED 行不被静默吸收、
  恰好 30 天与窗口内登记不变、既有 7 天 `markRepairRequired` 路径在 30 天内不变）、
  `VideoStatusEventConsumerAckBoundaryTests` 新增 1 例（新异常 → DLQ + ACK 一次，非静默 ACK、无 retry 转发、
  不写消费失败记录）。定向 `50 tests，0 失败/错误`（`/tmp/t14-s1-target-unit2.log`）；普通回归
  `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test` = **684 tests，0 失败/错误，201 skipped**
  （`/tmp/t14-s1-unit-full.log`，`MAVEN_OPTS='-Xms64m -Xmx256m'`、
  `argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'`，串行；IT/scale 关闭）。
  **未开隔离栈、未跑 IT/scale、未动容器或服务**；无 schema/迁移/COS/删除/新 HTTP。测试环境候选，非生产验收；T14 仍不勾选。
  教程：`docs/tutorial/video-status-capacity-audit.md` §9.3.1。
- T14-S2 三个管理入口接入同一固定 30 天上界（worker，2026-10-24，**候选、未父验收、T14 不勾选**）：
  **命名提示**：本编号由父代理指定，与计划中作为“下一步建议”的 **S2 = 归档时间字段 + 校验记录**不是同一件事，
  归档侧 S2 仍未实现。范围只包含“把已有固定上界接到已有入口”，**无 schema/迁移/COS/删除/新配置项/新 HTTP 路由**。
  四个共用点（同一 `VideoStatusEventAgeGuard#isOutsideNormalReplayWindow`）：T07 `VideoStatusOutboxReplayService#replay`
  （createdAt/occurredAt 双窗口之后、消费账本查询与 `resetFailedToPending` 之前）、T10-B1
  `VideoStatusStatus3ReplayService#evaluate`（occurredAt 窗口之后、预算**比较**/`processed` 证据与任何写之前；有界
  `countReplayAttempts` 查询作为调用实参在 `evaluate` 之前已执行；precheck 与
  replay 同源）、T09 `VideoStatusStatus0CompensationServiceImpl#evaluate`（preview 与 `compensateOne` 同源）、
  消费登记（T14-S1，不变）。原因码统一为 `EVENT_OVER_NORMAL_REPLAY_WINDOW`（读写两侧同一字符串）；**刻意不复用**
  `CONSUMED_OVER_SAFE_WINDOW` / `STATUS0_OVER_SAFE_WINDOW`——那两个码描述**可配置**窗口（启动校验只要求小于
  `redisEventTtlDays`，因此允许被调到 30 天以上），而这里是**固定政策上界**，混用会让运维无法区分两者、且原码
  的说明文本会变成不成立的说法。**优先级不变**：新检查排在原有“可配置窗口”判定之后，默认 7 天更严，所以默认配置
  下的超龄事件仍返回原原因，新码只在窗口被调到 30 天以上时兜底；恰好 30 天共享 predicate 不拒，但原更严格的
  7 天/TTL/年龄/未来 300s/指纹条件仍可拒。时钟：各服务保持原取时方式（写侧 `LocalDateTime.now()`），T09 preview
  整轮取一次 JVM `now` 与 `runOnce` 共用同一 `evaluate`，租约/冷却/静默期仍用 SQL 的 DB `NOW(3)`，一个 `evaluate`
  内不重复取时间；入口级用例只用“明显在内（20/29 天）/明显在外（31 天）”，纳秒边界仍由固定时钟的 guard 用例负责。
  **T09 写边界（不夸大）**：判定早于 Redis 证据与发布，但拒绝路径**不是零写**（按原协议释放租约并写冷却/诊断
  `last_error`）；它**不**发布 MQ、**不**改业务计数、**不**自动把合法事件隔离成 status3。**T09 读侧收紧**（同一次
  evaluate 的必然结果）：`preview.items[]` 新增 `refusalCode`，`withinSafeWindow` 从“只看 DB 年龄”改为“DB 年龄 +
  occurredAt 窗口”，`eligible` 不再在写侧会拒绝的行上为 true；`selectStatus0CompensationPreview` 额外读 `payload`
  （只读、仍有界），preview 仍**不**访问 Redis。**不变**：固定 30 常量与“恰好 30 天允许”边界、
  `VideoStatusProperties`、可调窗口与 `processed` TTL 交叉校验、relay（不新增拒绝）、无 schema/迁移/新 HTTP 路由。
  测试（纯 Mockito，串行，`-DvideoStatusIT=false -DvideoStatusScale=false`，`MAVEN_OPTS='-Xms64m -Xmx256m'`、
  `argLine='-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true'`）：`VideoStatusOutboxReplayBoundaryTests` 8→**11**
  （45 天窗口下 31 天拒绝且零写、同配置 29 天仍重放、默认 7 天仍返回原 occurredAt 原因）、
  `VideoStatusStatus3ReplayServiceTests` 26→**28**（precheck/replay 同码、零写、无 Redis 读；同配置 29 天仍可重放）、
  `VideoStatusStatus0CompensationTests` 31→**35**（31 天释放同一码、preview 与 runOnce 同码、无发布、无 `processed` 读；
  同配置 20 天仍重发；默认 7 天仍 `STATUS0_OVER_SAFE_WINDOW`）、`VideoStatusConsumptionRegistrationTests` 6→**7**
  （缺 `occurredAt` 由 `validate()` 拒绝且零 mapper 交互）；定向 **96 tests, 0 失败/错误/跳过**
  （`/tmp/t14-s2-target-unit2.log`），相关类回归 **48 tests, 0 失败/错误**（`/tmp/t14-s2-related-unit.log`）。
  **未开隔离栈、未跑 IT/scale、未动容器/DB/Redis/COS**。同时补齐 S1 审核备注：缺 `occurredAt` 用例、清理
  `staleEventWithExistingLedgerRowIsNeitherReadNorChanged` 的两个无用 stub、修正 `existingSevenDayReplayGuardIsUnchangedInsideThirtyDays`
  的活时钟耦合（原用固定时钟派生的 `consumed_at` 去喂活的 `now`）。**仍不做/不得声称**：无归档与删除能力
  （dry-run 仍 `NOT_AUTHORIZED_FOR_DELETION`）；未升级的旧消费者仍会放行旧事件（全兼容部署才能依赖未来归档）；
  relay 与自动重放/恢复路径不新增拒绝；未来时间戳仍不拒绝，因此不是防篡改机制；首次消费前积压超过 30 天的
  **合法**旧事件仍会直接进死信、不产生账本行、不自动补算（relay 确认状态与消费独立，不能断言它一定已 `SENT`）。
  教程：`docs/tutorial/video-status-capacity-audit.md` §9.3.2；`docs/api.md` §1.4 与 §8.8。
- T14-S2 **审查修复轮**（worker，2026-10-24，同一窄切片；**仍候选、未父验收、T14 不勾选**）：
  ① T09 `VideoStatusStatus0CompensationServiceImpl#evaluate` 的 parse 失败分支原返回
  `new Status0Verdict(withinDbWindow, null, e.getMessage())`，会造成“内容无法验证”的行 `withinSafeWindow=true`（与
  接口/`docs/api.md` 语义矛盾）并把原始异常文本透到 `preview.items[].refusalCode` 与 `release` 的 `last_error`；
  现改为 `withinSafeWindow=false`（`occurredAt` 无从证明）+ 白名单原因码（只放行 `parseAndMatch` 真实使用的
  `STATUS0_PAYLOAD_INVALID`/`STATUS0_EVENT_INVALID`/`STATUS0_EVENT_MISMATCH`，其余一律回落
  `STATUS0_EVENT_INVALID`，不回显）。外层 `compensateOne` 的 `startsWith("STATUS0_")` 消毒器语义不同（还覆盖
  证据/发布/释放类码），**未**合并两个分支、**未**扩协议。② 新增单测：malformed payload、`validate()` 失败、
  账本/payload 不一致三例都同时断言读侧 `withinSafeWindow=false`/`eligible=false`/精确码与写侧同码 `release`、
  不发布、不读 Redis；指纹不符一例只覆盖写侧（`STATUS0_FINGERPRINT_MISMATCH`、不发布、不读 Redis），因为它不在
  preview 判定内。③ `previewUsesDatabaseClockForGraceAndLeaseDecisions` 用真实合法 fixture（DB 年龄 100s、
  occurredAt 100s 前、attempts 1<5、无租约/冷却）补正向断言 `withinSafeWindow=true`/`eligible=true`/
  `refusalCode=null`。④ `AGENTS.md`/`CLAUDE.md`（同步、逐字一致）把 T10-B1 的“before the budget”改成“预算**比较**
  之前”，并注明有界 `countReplayAttempts` 查询在 `evaluate` 之前已执行；`docs/api.md` §8.8 同步 preview 语义。
  `VideoStatusStatus0CompensationTests` 35→**39**；定向运行
  `./mvnw -DvideoStatusIT=false -DvideoStatusScale=false '-Dtest=VideoStatusOutboxReplayBoundaryTests,VideoStatusStatus3ReplayServiceTests,VideoStatusStatus0CompensationTests,VideoStatusConsumptionRegistrationTests,VideoStatusEventAgeGuardTests' test`
  = **94 tests, 0 失败/错误/跳过**（`/tmp/t14-s2-review-fix.log`）。未开隔离栈、未跑 IT/scale/全量回归、未动
  容器/DB/Redis/COS，无 schema/迁移/删除；未处理评审报告中超范围的 `hs_err`/`replay` 日志与 `.gitignore` 建议。
- T15：第一阶段 DB 核心（2026-10-23 历史记录）+ **第二阶段 HTTP 接线与 Redis 准入（已接线）**，整体
  **候选、未父验收、未上线**：
  第一阶段（历史，保留原文）：
  新增 `video_interaction_request`（全局唯一 `request_key`，列级 `utf8mb4_bin` 保证大小写敏感；
  state `0 PENDING/1 COUNTED/2 SUPPRESSED`、`event_id`、时间）与 `video_interaction_window`
  （`actor_type+actor_hash+vid+action` 唯一，`last_counted_at` **可 NULL = 从未计数**）；
  幂等迁移 `database/migrations/t15-video-interaction-idempotency.sql` 只新增表、不改/不删既有行，
  已镜像 `database/feibijiubi.sql`（只对隔离 MySQL 13307 执行过，未碰正式资源）。
  `VideoInteractionService#record`：事务外幂等读（命中重放 / 形状冲突 409）→ 同一事务 INSERT 占位
  → 窗口 ODKU 保证行（自赋值，**绝不**用 `NOW(3)` 初始化 `last_counted_at`，否则第一条请求会被自己的
  初始值抑制）→ `SELECT ... FOR UPDATE` → DB `NOW(3)` 守卫更新
  （`last_counted_at IS NULL OR <= TIMESTAMPADD(MINUTE,-30,NOW(3))`，1 行=获准、0 行=抑制且**零写**）
  → 获准才计数（异步复用既有 `createEvent`（`Propagation.MANDATORY`）并保存其 eventId；同步复用既有
  `increasePlayTimes`/`increaseShareTimes` 守卫更新，影响行数非 1 抛 500）→ 终态 CAS。四步同一事务。
  并发同 `requestKey`：唯一键冲突让尝试事务**整体回滚**，回滚后事务外读赢家重放；`record()` 入口断言
  不得在已有事务中调用（违反直接 `IllegalStateException`），传播契约为无外层事务 + `REQUIRED`。
  证据：单测 `VideoInteractionServiceTests` 25 例；opt-in 隔离 `VideoInteractionIsolatedTests` 7 例
  （异步：首次恰好一条 Outbox 事件、`-1799s` 抑制且窗口不变、`-1801s` 再计并推进、被抑制旧键窗口过期后
  仍重放 SUPPRESSED、并发同键只计一次、并发不同键同窗口只计一次、**Outbox 触发器故障整事务回滚**）；
  `VideoInteractionSyncIsolatedTests` 7 例（同步：守卫更新计数、`event_id` NULL、无 Outbox 行、窗口抑制零写、
  **`Long.MAX` 计数失败整事务回滚**、换 vid 409 不额外计数、重放不重复计数、**`requestKey` 大小写敏感**）；
  普通回归 613 tests，0 失败/错误，194 skipped（`/tmp/t15-unit-full2.log`，受限堆串行、排除
  `BackendApplicationTests`）；第二阶段当前工作区普通回归为 **666 tests，0 失败/错误，200 skipped，
  BUILD SUCCESS**（`/tmp/t15b-unit-full.log`，`Tests run` 第 14129 行）。
  第二阶段（当前事实）：`POST /api/videos/{vid}/play-count` 与 `/share` 已接线，必填 `Idempotency-Key`
  （`[A-Za-z0-9._:-]{1,64}`、大小写敏感）、匿名必填 `X-Visitor-Id`（canonical UUID，登录态整个忽略），
  返回 `ApiResponse<VideoInteractionVO>{counted,duplicate,reason}`（`COUNTED`/`WINDOW_SUPPRESSED`）；
  两个头在 Controller 上故意 `required=false`、由服务层给明确 400（否则缺头会退化成 500）。
  旧签名 `increasePlayCount(Integer)`/`increaseShare(Integer)` **已删除且无重载**，新签名只委派
  `VideoInteractionAdmissionService#record`、不加 `@Transactional`。准入固定顺序：参数校验 → 身份解析
  （登录优先 + 域分离 SHA-256，不采集 IP/UA/指纹）→ 视频校验（`PublishedVideoGuard`，400/404/500，与
  点赞/投币/收藏/进度同一份）→ **只读重放（不碰 Redis）** → Redis 两维固定窗口（60 s；单动作 60、合计 120，
  `app.video-interaction.*` 启动期校验、`application.yml` 无条目；超限 429 单一文案；脚本 `null`/负数/异常
  → 503 fail-closed）→ `record`。已批准决策：两个头强制、缺失/非法 400、**不兼容旧客户端且无兼容窗口**；
  视频下架/删除后同一 `requestKey` 重试返回 **404**（视频校验先于重放）；匿名身份用客户端随机 UUID、
  不新增身份机制（可随时更换 → 只约束合作客户端）；30 分钟窗口不可配置；额度在事务前消耗、DB 失败不退。
  第二阶段证据：定向单测 **72 tests**（`/tmp/t15b-target-unit3.log`：HttpMapping 7 + ActorResolver 7 +
  Admission 17 + RateLimit 9 + Service 32）；opt-in 准入隔离 `VideoInteractionAdmissionIsolatedTests`
  **6 tests，0 失败/错误/跳过**（`/tmp/t15b-admission-it4.log`）；第一阶段隔离回归 **20 tests，0 失败/错误**
  （`/tmp/t15b-regression-it1.log`）；维护演练 1 例通过（`/tmp/t15b-drill-maintenance.log`，演练入口
  `countOnePlay()` 每次随机访客 + 随机 `requestKey`）。**未绿**：隔离套件最近一次完整运行
  `VideoStatusIsolatedIntegrationTests` **20 tests，1 failure，1 skipped，BUILD FAILURE**
  （`/tmp/t15b-drill-it2.log`：`concurrentFlushAndConsumptionPreserveAllEvents` `condition did not converge
  in 30 seconds`）——历史并发用例、不经过 T15 入口、根因未定位未修复，不得写成已解决或整组全绿。
  **仍未做**：生产死锁重试、
  容量/压测、服务端下发/签名访客标识、旧客户端兼容窗口、任何生产防刷验收；已知非阻塞缺陷：
  上述错误分类已在恢复轮修正：唯一键冲突后无可读赢家返回 500，不再误报请求冲突 409，事务仍整体回滚。
  父代理定向实跑 `VideoInteractionServiceTests`：33 tests，0 failures/errors/skipped
  （`/tmp/t15-resume-service-unit.log`，含新增 Outbox 唯一键冲突回滚单测）；日志不输出底层异常文本/请求键。
  本轮未重复迁移、造数或故障演练，未复跑全量回归，历史并发失败仍未解决，T15 未验收。
  教程 `docs/tutorial/video-interaction-idempotency.md`（§11 为第二阶段当前事实）。**T15 不勾选**。
- T15 30 天有界清理（本轮候选，默认关闭，**未运行隔离用例**）：用户已确认保留期——`video_interaction_request`
  终态行自 `completed_at` 起 30 天、`video_interaction_window` 连续 30 天无计数即可清理；两者都是**固定常量、
  不可配置**（做成配置能配 0 天，窗口行每轮被删、30 分钟限计被静默绕过）。语义据此改为“记录还在即重放，记录被
  清理删掉后同一 `requestKey` 按新请求处理、仍受 30 分钟窗口”，取代“永远幂等”；清理默认关闭且按批推进，
  **不是** 30 天到点 TTL。实现：`VideoInteractionCleanupService`/`…Impl`/`…Scheduler` +
  `VideoInteractionCleanupSchedulingConfig`（独立单线程线程池 bean **`videoInteractionCleanupTaskScheduler`**，
  与 `@Component` 调度组件 `videoInteractionCleanupScheduler` 不同名，与 T06 投递/落库/恢复/补偿链路隔离，
  5 s 有界停机）+ mapper 4 条语句（两个有界候选 SELECT 带 `FORCE INDEX`，两个按主键等值
  并**重复时间+状态守卫**的 DELETE，空 id 集合 `WHERE 1 = 0` fail closed）；每轮 state=1/state=2/window 各一批，
  服务拒绝在已有事务中运行，各 mapper 独立提交，时间阈值一律由 SQL 计算（`NOW(3)`，不新增 JVM 时钟阈值；
  DB 时钟/会话时区风险仍在）；PENDING/`completed_at IS NULL`/`last_counted_at IS NULL` 永不清理；不碰 outbox/consumed/Redis 证据，无新 HTTP 接口。迁移
  `database/migrations/t15-video-interaction-cleanup-indexes.sql`（`(state,completed_at,id)` / `(last_counted_at,id)`，
  幂等，镜像 `database/feibijiubi.sql`）是开启清理的**硬前置**：缺索引时候选查询报 1176、清理可见失败，不静默
  全表扫描。配置 `app.video-interaction.cleanup-enabled=false`（默认关）/`cleanup-batch-size=500`（1..5000）/
  `cleanup-fixed-delay-ms=60000`（正数），`application.yml` 无条目时即默认值。证据：定向纯单测 **30 tests，
  0 失败/错误/跳过**（`VideoInteractionCleanupServiceTests` 19 + `VideoInteractionCleanupContractTests` 9 +
  `VideoInteractionCleanupRetentionTests` 2，`/tmp/t15-cleanup-reviewfix-unit.log`）；普通回归 **741 tests，0 失败/
  错误，214 skipped**（`/tmp/t15-cleanup-reviewfix-full.log`，其中 opt-in `VideoInteractionCleanupIsolatedTests` 13 例
  整体 skip）。opt-in 隔离类（真 MySQL 13307：过期/新鲜/NULL/PENDING 矩阵、删窗口后首次计数+第二次抑制、
  29 天重放 vs 删除后同键变新请求、选 id→推进时间→删 0 行、真实行锁交错、批有界收敛、重跑幂等、`EXPLAIN`
  命中索引、不碰统计账本）**只编译未运行**，不构成隔离证据；它每轮清理前先检查“是否存在不是本类造出的过期
  候选行”，存在即失败退出、不删未知测试数据。**未运行**任何 IT/scale/容器/DB/Redis/迁移/删除；T15 整项仍未验收、**不勾选**，T16 仍只有设计建议（本切片不做 T16）。
  审查修复轮（同轮，只跑纯单测）：收尾 `@AfterEach` 从“全局 `cleanupOnce()` 轮询”改为**只按本次跟踪的 id 集合**
  调 `deleteExpiredRequestsByIds`/`deleteIdleWindowsByIds`（同一时间/状态守卫、单语句、无 LIMIT），因为原写法在
  foreign guard 拒绝后收尾仍会执行、会把被拒绝的别人的过期行删掉——这被视为**合入阻塞**，不是可接受风险；跟踪
  完整性由 foreign guard 兜底，并加源码文本契约防止回退到全局清理。两个确定性选候选用例补 foreign guard；
  `VideoInteractionService` 接口与 `lookupReplay` javadoc 改为保留期口径；运行手册速率拆成请求 1000 行/分钟 /
  窗口 500 行/分钟（合计 1500/分钟，理想上限非实测）；`feibijiubi.sql` 表注释改保留期口径、历史迁移只加注释不改
  可执行 DDL；properties 类说明与 scheduler 默认值字面量注释补齐；时钟表述收窄为“不新增 JVM 时钟阈值”，
  并写明 DB `NOW(3)`/会话时区/DST 风险仍在且无时区切换测试。修复后定向纯单测 **30 tests，0 失败/错误/跳过**
  （`/tmp/t15-cleanup-reviewfix-unit.log`）、普通回归 **741 tests，0 失败/错误，214 skipped**
  （`/tmp/t15-cleanup-reviewfix-full.log`）；隔离 IT 仍只编译未运行。
  调度 bean 同名阻塞修复轮：`@Component VideoInteractionCleanupScheduler`（默认 bean 名
  `videoInteractionCleanupScheduler`）与线程池 bean 曾同名，Spring Boot 关闭 bean 覆盖时启动会抛
  `BeanDefinitionOverrideException`；线程池改为 `videoInteractionCleanupTaskScheduler`（常量与 `@Bean` 方法名同步），
  组件保留原名。新增无 DB 的真实 Spring 上下文回归 `VideoInteractionCleanupSchedulingWiringTests`（3 例，
  关闭 bean 覆盖）：验证两 bean 类型/名称与唯一性、`@Scheduled(scheduler=...)` 引用可解析、缺 bean 时 refresh 失败
  并点名——把旧常量改回同名后该测试实测以 `BeanDefinitionOverrideException` 失败（随后已还原）。修复后定向纯单测
  **33 tests，0 失败/错误/跳过**（`/tmp/t15-cleanup-target2-unit.log`；调度装配单独
  `/tmp/t15-cleanup-wiring-unit.log`），普通回归 **744 tests，0 失败/错误，214 skipped**
  （`/tmp/t15-cleanup-wiring-full.log`）。父代理此前复跑的 63 tests（`/tmp/t15-cleanup-parent-unit.log`）
  不能验证启动、也不作为修复后证据。隔离 IT 仍只编译未运行，锁足迹/访问路径未实测。
- 恢复契约测试覆盖补充（2026-10-23，**仅测试作用域候选，本轮未实跑**）：`VideoStatusIsolatedIntegrationTests`
  注入真实 `VideoStatusDirtyRecoveryScheduler` bean（该上下文无 `@EnableScheduling`，用例显式调 `recoverDirtyVids()`），
  `await(...)` 增加超时有界诊断（vid / baseline / current / `process_status` 0–3 计数 / dirty 归属与基数，
  不含 payload、eventId、`last_error` 或凭证；30 秒上限未放大）。`concurrentFlushAndConsumptionPreserveAllEvents`
  收尾由“只跑 flush”改为真实 recovery + 真实 flush：`stats.apply` 的 Lua `SADD` 与 `markRedisApplied` 的
  `0→1` 不原子，落库线程在这个窗口 `SPOP` 到该 vid 时既不落库（`selectPendingForUpdate`/`countPendingByVid`
  只看 status 1）也不回填 dirty（调度器从不 `SREM`），recovery 是唯一重新唤醒路径。新增确定性用例
  `dirtyMarkerLostBetweenApplyAndStatusCommitIsRecoveredOnlyByRealRecoveryScheduler`（新 vid、不手工
  `SADD`/`SREM`、不删全局 dirty/门闩/processed）：`register` → `apply`（SADD，status 0）→ 真实 flush 消耗 dirty
  但不落库 → `markRedisApplied`（status 1）→ 证明 DB 有待刷事件而 dirty 已缺失 → 只跑 flush 基线不变 →
  真实 recovery 在有界轮次（`ceil(全库 status-1 distinct vid / flush-dirty-batch-size) + 2`）补回 dirty →
  真实 flush → 独立 oracle（`baseline = current = 101`、事件 status 2、`SUM(delta)` = 1）。已否决把
  `countPendingByVid` 扩成 status 0（改变落库协议且对未提交状态空转）。**不宣称历史根因**：那两个历史并发错误
  （`/tmp/t15b-drill-it2.log`）的日志没有 baseline/current/状态快照，本用例只是确定性机制复现。环境：
  `feibijiubi-test-mysql-1`/`redis-1`/`rabbitmq-1` 均 `Exited (0)`（已停数小时），按约束未启动/重启隔离栈，
  因此本轮**只做 `./mvnw -DskipTests test-compile`（通过），两个 opt-in 方法未实跑**，结论待父代理启动隔离栈后复跑。

## 恢复契约父代理定向验证（后续恢复轮）

- 经用户批准，先确认三项隔离服务仍停止且没有本次 IT 成功日志，再运行 `docker compose -f compose.test.yaml start --wait`；保留原容器/数据卷，无迁移、无重建。
- 仅运行 `VideoStatusIsolatedIntegrationTests#concurrentFlushAndConsumptionPreserveAllEvents+dirtyMarkerLostBetweenApplyAndStatusCommitIsRecoveredOnlyByRealRecoveryScheduler`，受限堆串行：**2 tests，0 failures/errors/skipped，BUILD SUCCESS**，日志 `/tmp/t18-recovery-contract-parent-it.log`。
- 确定性用例输出 `baseline=101 current=101 setupFlushRounds=1 recoveryRounds=1`；证明预设竞态可通过真实 recovery 收敛，不证明历史失败必定由该机制引起，也不是完整隔离套件或生产验收。此前“未实跑”记录保留为当时事实。

## 恢复轮整合普通回归

- 在上述两项隔离测试已成功之后，不重复执行启动/迁移/隔离演练；本轮仅运行 `./mvnw -DvideoStatusIT=false -DvideoStatusScale=false '-Dtest=*Tests,!BackendApplicationTests' test`（Maven 堆 256 MiB、测试堆 512 MiB，启用 self-attach）。
- **668 tests，0 failures/errors，201 skipped，BUILD SUCCESS**，日志 `/tmp/improvement-resume-unit.log`。相比旧 666/200，多出一例错误分类单测与一例默认跳过的确定性恢复 IT；这不是 668 项全部实跑，更不是生产验收。
- 恢复契约只读审查无阻塞项。保留一项测试稳健性风险：`recoveryRoundBound()` 按满批进展估算轮数，慢 Redis 导致单轮预算截断时可能提前失败；当前一轮收敛的证据不能排除此风险，也未任意扩大轮数掩盖它。

## 保留候选与下步

1. T07：**已完成（测试环境验收）**。主分支 `95af60d`，全链路回归 87 tests，0 失败/错误，1 skipped（`/tmp/improvement-main-regression3.log`）；T07 单组 61 tests 0 失败/错误/跳过。非生产验收、非真实进程崩溃/两 JVM 演练。
2. T08：**已完成（测试环境验收）**。已 cherry-pick 到主分支 `0dd98ad`、`b846032`、`92eb8c1`（候选 `b796fc8`）。父代理在独立候选父复验 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusIsolatedIntegrationTests,VideoStatusConsumerRetryLifecycleIsolatedTests' test`：22 tests，0 失败/错误，1 skipped，日志 `/tmp/t08-parent-final.log`；真实 10/30/120s TTL、容器 stop/start、Redis `CLIENT PAUSE 120000`（非进程/网络断连）。
3. T09：**已完成（测试环境验收）**。主分支 `97c7357`（候选 `aab1c64`）；普通回归 356 tests，0 失败/错误，108 skipped（`/tmp/t09-main-unit.log`）；opt-in 隔离回归 62 tests，0 失败/错误，1 skipped（`/tmp/t09-main-it.log`）。无证据模式保持受控/默认关，不能盲目重放超龄或丢失幂等证据的事件，也不保证任意 Redis 数据丢失。非生产验收。
4. T10：**进行中**。第一阶段失败诊断/确认已阅与只读对账已合入主分支 `6fc5e7f`，父代理 opt-in IT 121 tests（0 失败/错误/跳过）与普通单测 416 tests（0 失败/错误，130 skipped）通过。T10-B1（consumed status3 单事件 proven-only 受控重放）为 2026-10-23 候选，父代理已复跑窄范围单测 54 tests 与隔离 IT 11 tests（均 0 失败/错误/跳过，`/tmp/t10b1-parent-unit.log`、`/tmp/t10b1-parent-it.log`），但 B1 仍是候选、未最终裁定；它不覆盖 DLQ、批量、刷库型 status3 或自动修复，T10 整项仍不能勾选或宣称闭环完成，也不是生产验收。
5. T11：**已完成（测试环境验收）**，按计划允许在 T06 后提前交付，原因是独立的 dirty 恢复公平性/失败退避改进，不依赖 T07—T10。验收后主计划已勾选，详细证据见 `video-status-improvement-plan.md` 的 T11 验收记录。
6. T12：**进行中（父已复跑验证，整项未验收）**。T12-A（processed 证据按 256 条一批 pipeline `EXISTS`，status1 不查询，失败 fail closed）与 T12-B（候选 keyset 分页、同事务 RR 快照、行级 id 严格递增校验、候选数上限与读取预算，超限/超预算失败且不返回截断快照、无半初始化）为 2026-10-23 候选，父代理已复跑普通 497 tests 与隔离 31 tests（`/tmp/improvement-parent-unit-final.log`、`/tmp/improvement-parent-t12b-it.log`）；本轮复验 504 tests 与隔离 20 tests（`/tmp/t12b-worker-unit.log`、`/tmp/t12b-worker-it.log`）。T12-C1（2026-10-23 候选）只交付**受控替换 Lua 内部原语**：`video-status-replace.lua` + 独立 bean，24 例 opt-in 隔离测试全绿（`/tmp/t12c1-replace-it2.log`），但**没有 HTTP/Java 入口、没有自动调用、没有审计表与崩溃恢复**，因此只是“将来的强制重建需要的那块脚本”，不能算强制重建已实现。规模测量类 `VideoStatusRebuildScaleIsolatedTests` 已跑出0/1,000/100,000 各 3 次诊断数字（`/tmp/t12-scale-full.log`），但**不是性能验收**（单机单 JVM、3 样本、P95 仅诊断）。**部署前置**：先执行 `t10-video-status-reconciliation-indexes.sql`，否则冷重建失败并重试/DLQ。**仍欠**：生产容量/性能验收、告警、强制重建（权限/审计/并发协调/崩溃恢复/完整性校验）与重建 HTTP 接口；因此 T12 不勾选。
7. T13—T16：T13 已在 2026-10-23 由父决策定为**模式 B**（面向用户统计读保持 MySQL 唯一来源，不接 Redis 实时读、read miss 不触发重建），并交付候选实现与文档（`docs/tutorial/video-status-read-consistency.md`、`VideoStatusReadConsistencyTests` 14 例、普通回归 542 tests/171 skipped，`/tmp/t13-unit-worker.log`），但**仍是候选、未父验收、不能勾选**（2026-10-28 收口修复：更正“`getByVid` 是唯一 Redis 统计读”的错误表述——管理端只读对账也采样 `current`；补管理员详情 2 例后本类 16 例，定向串行 24 tests 0 失败/错误/跳过，`/tmp/t13-fix-target-unit2-A3BgFA.log`（同轮首跑 `/tmp/t13-fix-target-unit-E1ZD6a.log` 结果相同）；仍为候选、不勾选）；未做端到端延迟实测、生产容量、热榜读/删。T14 只交付了 **T14-A 只读核查 + dry-run 候选**（测试作用域工具、8 例隔离 + 13 例纯单测、普通回归 563/179，`docs/tutorial/video-status-capacity-audit.md`），**没有任何清理实现**；保留期与重放窗口政策已于 2026-10-23 批准（90/275 天、DLQ 30 天处置 SLA、审计 365 天、普通重放 30 天，均未实现），**起算点与归档存储已由本轮用户确认**（在线 90 天按 `sent_at`/`flushed_at` 终态完成起算、非创建/接收时间；归档 275 天从归档副本校验成功起算，归档延后时实际总保留可超 365 天；审计 365 天从审计行创建；DLQ 30 天 SLA 从首次入死信队列、重投不重置；独立私有 COS 归档桶，完整性+记录数校验通过才允许删在线数据，开发阶段仅隔离测试存储），字段缺口（`sent_at` 为 JVM 时钟且与 `flushed_at` 均无索引、DLQ 首次入队时间与归档校验记录不存在）与下一步最小切片（S1 30 天旧消息拒绝入口全覆盖——已由 T14-S1 部分落地、其余入口仍欠；S2 归档时间字段+校验记录，仍为**建议待父审**）见该条与 `docs/tutorial/video-status-capacity-audit.md` §9.1.1—§9.3；去重墓碑/归档执行/演练仍欠，T14-S1（2026-10-24 候选）只落地了**消费登记侧**的 30 天绝对年龄拒绝，T14-S2（同日候选）把同一固定上界接到 T07/T10-B1/T09 三个管理入口（relay/自动重放仍不拒绝，非全入口、不构成归档授权），T14 整项未完成、不勾选；T15 已交付第一阶段 DB 核心 + **第二阶段 HTTP 接线/Redis 准入**（`play-count`/`share` 必填 `Idempotency-Key`、匿名必填 `X-Visitor-Id`、429/503、身份解析、旧签名删除；普通回归 666/200（`/tmp/t15b-unit-full.log`），准入隔离 6 例（`/tmp/t15b-admission-it4.log`），隔离套件最近一次完整运行仍有 1 个历史并发用例失败未定位（`/tmp/t15b-drill-it2.log`）），两表保留期/清理、生产死锁重试、容量与生产防刷验收均欠，整项不勾选；T16 仍只有设计建议。归档保留的实现前置、防刷产品语义、压测目标必须在实施前固定并记录理由。尤其删除消费账本不能仅靠 processed TTL 判安全。

## T15 清理增量父代理复验与整合

- 独立工作树基线 `24e3b7e` 保存原有候选；父代理逐文件比对原工作区与快照一致后，仅同步本轮 13 个修改文件和 10 个新增文件，未提交原工作区，未覆盖后续改动。
- 父代理发现并修复清理组件与线程池的同名 Bean 启动冲突；线程池现名 `videoInteractionCleanupTaskScheduler`，新增真实轻量 Spring 上下文测试（禁止 Bean 覆盖）。
- 修复后父代理运行 `VideoInteractionCleanup*Tests,VideoInteractionServiceTests`：**79 tests，0 failures/errors，13 skipped，BUILD SUCCESS**；日志 `/tmp/t15-cleanup-parent-final.log`。13 个跳过均为隔离清理用例，实际执行 66 项；不是数据库锁/执行计划证明。
- 未执行迁移、数据库清理、容器启动或隔离测试；清理默认关闭，T15 仍为候选。T16 按用户批准仅做测试环境基线测量，尚未开始实测，不设生产验收结论。

## T16-B0 同步准入基线父代理实测（本轮接续点）

- 用户批准仅做测试环境基线，并批准启动现有隔离 MySQL/Redis。首次启动 Docker API HTTP 500、MySQL exit 255，原因未确认；只读检查后用户批准一次重试，重试成功。RabbitMQ 未启动，无重建/迁移/删除。
- `VideoInteractionAdmissionBaselineIsolatedTests` 首次实测成功：**3 tests，0 failures/errors/skipped**，日志 `/tmp/t16-b0-parent-baseline.log`。单视频 admission P95 34.886 ms，十视频 P95 24.199 ms，重放 P95 2.952 ms；仅本机串行同步服务调用，非 HTTP，无 MQ，不是异步可见性或容量结论。
- 已同步三个新测试文件，结果见 `video-interaction-test-baseline.md`。本次保留 1 用户、13 视频、440 请求与440窗口行；100次重放不增计数，Outbox 为0。**恢复时不要重复启动、重跑成功基线或重复造数**。
- T15清理默认关闭，隔离清理测试未运行，清理索引未执行；T16-B0窄片完成，不代表T16或全部 improvement 完成。T10闭环、T12强制修复、T14归档等仍保留原未完成状态。

## 执行约束

- cn/global DeepSeek 均衡使用；独立任务可并发4路，编辑任务使用独立工作树。
- 子代理报告需父代理查代码/复跑；未验证的API建议不可直接照搬。
- 不连接正式资源、不部署生产、不清除幂等/门闩/失败证据。隔离数据保留。
