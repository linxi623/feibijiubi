# 视频统计刷库延后（T03）：负增量顺序窗口与错误分类

> 范围：`VideoStatusBatchFlushServiceImpl`（分类 + 上限失败的一次负增量优先备选）
> + `VideoStatusBatchFlushScheduler`（有界本地退避）
> + 新增 `VideoStatusFlushDeferredException`
> + 新增 `VideoStatusConsumedEventMapper.selectPendingNegativeFirstForUpdate`（§2.1）。
> 关联：`video-status-improvement-plan.md`（T03）、`video-status-improvement-baseline.md`（T01 证据）、
> `video-status-lock-free-fast-path.md`（协议）、`video-status-counter-range.md`（T02 计数范围）。
>
> 本文件只描述已实现的行为和已跑过的测试，不承诺未验证的性能或“任意故障 exactly-once”。

## 1. 要解决的现象

互动统计的落库链路是“Redis 先应用，再提交事件状态，最后批量刷库”：

```text
消费: register(status 0) → Lua 改 Redis → markRedisApplied(status 1) → SADD dirty
刷库: SPOP dirty → vid 锁 → SELECT ... process_status=1 FOR UPDATE
      → UPDATE video_status（非负/上界守卫）→ markFlushed(status 2)
```

`markRedisApplied` 是独立事务提交。于是存在一个真实窗口：

1. `+1` 已经把 Redis 从 0 加到 1，但 `markRedisApplied` 还没提交，事件仍是 status 0；
2. `-1` 完成了 Redis 与状态提交，事件是 status 1；
3. flush 只能看到 `-1`。此时 DB 基线是 0，`0 + (-1)` 被非负守卫拒绝（T02 之后是“0 行”，之前是 1690 报错）。

旧实现的处理是：`applyBatchDelta != 1` → `VideoStatusFlushDataException` → `markFlushRepairRequired`，
把**合法的** `-1` 永久隔离成 status 3。

关键判断：**“flush 看不到依赖的 +1”不等于“-1 非法”**。在 status 0 的瞬间查询永远只能看到 0，
用这个瞬间的 0 去证明负增量非法，必然把合法事件误隔离。所以：

- 不能立即证明非法的负净增量 → **延后**；
- 只有能明确证明非法的数据 → 受控修复（status 3）。

对称的上界窗口同样存在（计数范围 0..`Long.MAX_VALUE`）：

1. `-1`（取消）已经把 Redis 从 `Long.MAX_VALUE` 降到 `Long.MAX_VALUE - 1`，但状态提交暂停，事件仍是 status 0；
2. `+1` 完成 Redis 与状态提交，事件是 status 1；
3. flush 只能看到 `+1`，DB 基线是 `Long.MAX_VALUE`，`MAX + 1` 越上界 → 0 行。

这里同样**不能**把一次上界不足当成“永久非法”：取消事件落库后净增量为 0，两个合法事件应一起 FLUSHED。
因此上界不足与下界不足一样走延后，不转 status 3。

批次内部也一样：事件按“正增量优先”排序，`+MAX, +1, -MAX` 这类完全合法的序列会在逐步
`Math.addExact` 的中间步溢出。实现改为按 8 个字段用 `BigInteger` 累计，**只要求最终净量**落在 long 内
（`longValueExact`）；最终净量仍超出 long 时（如 `+MAX, +MAX`）只是“这一批无法一次落库”，
同样延后等待拆批或相反增量落库，不据此转人工修复。

## 2. 错误分类（实现即契约）

`applyBatchDelta` 影响 0 行后，先 `selectByVid` 读取当前基线，再按“能否立即证明非法”分类：

| 情况 | 判定 | 处理 | 事件状态 |
|---|---|---|---|
| 未知 `event_type`（枚举解析失败） | 明确非法 | `VideoStatusFlushDataException` → 受控修复 | 3 |
| `selectByVid` 返回 `null`（统计行缺失） | 明确非法 | 同上 | 3 |
| 批次净增量（`BigInteger` 累计后）超出 long | 无法一次落库 | `VideoStatusFlushDeferredException` → 延后 | 保持 1 |
| 基线 + 增量 > `Long.MAX_VALUE` | **暂态/待依赖确认** | 同上 | 保持 1 |
| 基线 + 增量 < 0 | **暂态/待依赖确认** | 同上 | 保持 1 |
| 守卫成立但 SQL 未命中行 | 暂态（并发/可见性） | `RetryableMessageException` → 重试 | 保持 1 |
| `DataAccessException` / Redis 故障 / 锁超时 | 依赖故障 | 重试（原有分支） | 保持 1 |

补充约定：

- 只有“未知事件类型”和“统计行缺失”两种能立即证明非法的情形转受控修复；
  合法范围内的越界（下界不足、上界不足、批次净量超 long）一律延后，不得误隔离。
- 净零批次（`delta.isZero()`）不写统计，但仍推进 `markFlushed`（T02 要求）。
- 延后时不修改任何统计值，不做 `GREATEST(...,0)`、不写 0、不部分落库：
  `applyBatchDelta` 是一条带守卫的 `UPDATE`，要么整行更新，要么 0 行。
- `markFlushed` 行数不一致仍然抛 `RetryableMessageException`，事务回滚，统计与状态一起回滚。
- 混合字段批次（如 `play +3`、`unlike -1` 且 `unlike=0`）**整批延后**，不做“正增量先落库”的拆分。
  这是有意的取舍：拆批需要跨事务/跨字段的额外协议，最小补丁先保证不误隔离、不部分落库。
  代价是极端情况下一个永久无法成立的负增量会让同 vid 的正增量一起等待；此时靠退避预算告警
  （见 §3）而不是静默隔离，最终处置留给 T09/T10。

### 2.1 上限失败时的**一次**负增量优先备选（拆批公平前进）

`selectPendingForUpdate` 是“正增量优先 + id 升序 + LIMIT”。当 `flushEventBatchSize = 1` 且
DB 已达 `Long.MAX_VALUE`、两个合法事件（`+1`、`-1`）都是 status 1 时，这个排序会让每一轮都
选中同一个 `+1` → 上界不足 → 延后；`-1` 永远排不上队，批次**永不收敛**（合法事件不自动前进）。

最小修复：新增 `VideoStatusConsumedEventMapper.selectPendingNegativeFirstForUpdate`
（同一 `vid` + `process_status = 1`，`ORDER BY CASE WHEN delta < 0 THEN 0 ELSE 1 END, id`，
同样 `FOR UPDATE SKIP LOCKED`，复用已有索引 `idx_video_status_consumed_pending
(process_status, vid, id)`），并且：

- **只在**首轮批次因“上界不足”或“净量超出 long”无法落库时触发（下界不足需要的是正增量，
  换负增量优先没有意义，因此不查备选）；
- **最多一次**：备选批次仍触达边界就延后，不递归、不找第三批；
- **同事务 + 同一 vid 锁**：备选查询在 `flushOneVideo` 的同一个 `@Transactional` 内执行，
  外层仍是 `VideoStatusVidMutex`；
- **不改原行状态挪队列**：备选命中初批已锁定的 id 会被排除；备选可刷时只 `markFlushed`
  备选 ids，初批事件保持 status 1，下一轮重新尝试；
- **原分类不变**：备选批次里的未知 `event_type` / 统计行缺失仍然走受控修复（status 3），
  不会被“延后”吞掉；守卫成立但 0 行仍然重试。

效果（`limit = 1`，DB=`Long.MAX_VALUE`，`+1`/`-1` 都是 status 1）：第 1 轮初批 `+1` 上界不足 →
备选 `-1` 落库（DB=`MAX-1`，`-1` FLUSHED，`+1` 保持 status 1）→ 第 2 轮 `+1` 在余量内落库 →
两轮后两个合法事件都 FLUSHED、DB 回到 `Long.MAX_VALUE`。这只增加“每个批次至多一次”的额外
查询，不改变批量大小、不改变既有排序策略的适用范围。

## 3. 有界本地退避（阻止 500ms 热循环）

延后如果只是“SADD 回去”，`flush-fixed-delay-ms=500` 会让同一 vid 每 500ms 走一次
`vid 锁 + SELECT ... FOR UPDATE`，把暂态问题放大成数据库热循环。因此在调度器里加最小退避：

- **位置**：`SPOP` 之后、**取锁与 SQL 之前**判断。退避未到期时只 `SADD` 回 dirty 就跳过，
  不取 vid 锁、不查事件表。
- **常量**：`DEFER_BACKOFF_MILLIS = 2000`（规范允许 1s..30s）。先固定常量；T05 已接入
  `FLUSH/DEFERRED` 遥测与积压采样，该常量仍未配置化。
- **预算**：同一 vid 连续延后 `DEFER_ALERT_BUDGET = 30` 次以内打 WARN；
  超过后每 30 次打一条 ERROR（约每分钟一条）。**超过预算只告警，仍然不隔离、不改写统计。**
- **成功清理**：正常刷库成功或该 vid 已无待刷事件时移除本地退避状态。
- **有界**：`MAX_DEFERRAL_ENTRIES = 4096`，LRU 淘汰。
- **重启/回绕/多实例**：本地状态丢失只会让某次重试提前发生，**只影响效率**；
  正确性依赖的是仍然保留的 `dirty` 标记与 status 1 事件。

时钟通过 `java.time.Clock` 注入（`Clock.systemUTC()` 为生产默认），测试直接推进时钟，
不靠 sleep 碰运气。

## 4. 明确保留的不变量（不要“顺手优化”掉）

1. **不 `SREM` dirty**：延后、空调度都不删除标记，避免误删消费者刚 `SADD` 的新事件唤醒。
2. **不加全程 vid 锁**：热路径仍无锁，只有 flush/rebuild 拿锁；退避不引入新的锁协议。
3. **不给门闩加 TTL**：本次改动不涉及重建门闩，`noeviction` + token-owned release 保持原样。
4. **不把负增量改成 0**：延后只是等待，不改写统计。
5. **不因一次 0 行就隔离**：必须先 `selectByVid` 分类。
6. **状态与统计同事务**：延后/修复都不会产生“统计已改、状态未改”的半成品。
7. **不靠改状态挪队列**：上限失败的备选只做一次“负增量优先”查询，绝不把事件临时改成
   status 0/2 来让下一轮换个顺序。

## 5. 测试与证据

单测（无需基础设施）：

```bash
./mvnw.cmd -o '-Dtest=VideoStatusFlushOrderingTests,VideoStatusFlushDeferralTests,VideoStatusDirtySchedulingTests' test
```

- `VideoStatusFlushOrderingTests`：**100 轮**确定性窗口回归（`VideoStatusFlushTestFixture` 内存版
  SQL/Redis + 可控时钟）。每轮：`+1` 停在 status 0、`-1` 先可见 → flush 后 `-1` 只能保持 status 1、
  DB 仍为 0、dirty 保留、0 次隔离；释放 `+1` 并推进 31s（> 规范最大常量退避）后再次 flush →
  两个事件均 status 2、DB=0、dirty 清空。另一用例覆盖 `+1` 永久滞留（status0 长期滞留）
  40 轮延后：不隔离、不篡改、dirty 一直保留。
- `VideoStatusFlushDeferralTests`：混合字段整批延后、缺行/未知类型→受控修复、
  上界不足与下界不足同为延后、中间溢出但净量合法必须落库、净量超 long 延后、
  守卫成立但 0 行→重试、退避期内不取锁不查 SQL、成功刷库清空退避、拆批（batch=2）与重复触发收敛、
  status0 长期滞留 35 轮只告警。其中上界窗口用例重复 **100 轮**：DB=`Long.MAX_VALUE`、
  `-1` 的 Redis 已应用但状态提交暂停、`+1` 可见 → `+1` 只延后（status 1、dirty 保留、0 次修复）；
  释放 `-1` 后净零，两个事件一起 FLUSHED，统计保持 `Long.MAX_VALUE`。
- **拆批 + 上限（本次新增，§2.1）**：
  - `splitBatchLimitOneUpperBoundConvergesBothLegalPlusOneAndMinusOne`：`flushEventBatchSize=1`、
    DB=`Long.MAX_VALUE`、`+1` 与 `-1` 都是 status 1 → 第 1 轮只查 **1 次**负增量优先备选并让 `-1`
    FLUSHED（`+1` 保持 status 1、DB=`MAX-1`），第 2 轮 `+1` FLUSHED、DB=`MAX`、dirty 清空、0 次修复；
  - `splitBatchLimitOneUpperBoundConvergesInEveryRound`：同上重复 **100 轮**，每轮都收敛且备选只查 1 次；
  - `netOverflowUsesOneNegativeFirstFallbackToMakeProgress`：初批 `+MAX, +MAX` 净量超 long →
    备选 `-MAX` 落库（不进入 SQL 的只有初批），下一轮备选无新行 → 延后、不递归、0 次修复；
  - `negativeFirstFallbackStillAtBoundaryDefersWithoutRecursion`：备选与初批同 id 时每轮只查 1 次备选、
    只做 1 次 UPDATE 尝试，30 轮全部保持 status 1 + dirty，不隔离；
  - `lowerBoundDeferDoesNotQueryNegativeFallback`：下界不足不查备选（`negativeFirstQueries = 0`）；
  - `unknownTypeInsideNegativeFallbackKeepsControlledRepair`：备选里的未知类型仍转 status 3，
    初批保持 status 1 且 dirty 保留。

隔离集成测试（独立 `feibijiubi-test`，独占 MQ）：

```bash
docker compose -f compose.test.yaml up -d --wait
./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusIsolatedIntegrationTests test
```

`realConsumerBlockedBeforeMarkRedisAppliedDefersLegalMinusOneThenConverges` 用真实 Rabbit 消费者在
`markRedisApplied` 前 latch 阻塞，重复 100 轮：窗口内 `-1` 只延后、不隔离，`dirty` 保留；
释放 `+1` 并推进注入时钟 31 秒后自动收敛为 `DB=Redis=0`、两个事件均 FLUSHED、`quarantined=0`。

已执行结果（本机 Windows + Docker Desktop，隔离环境 MySQL 13307 / Redis 16379 / RabbitMQ 15673）：

- 白名单单测（本次修复后实跑，含 §2.1 新增用例）：
  - `-Dtest=VideoStatusFlushOrderingTests,VideoStatusFlushDeferralTests,VideoStatusDirtySchedulingTests`
    → `21 tests, 0 failures, 0 errors`；
  - `-Dtest=*Tests,!BackendApplicationTests,!VideoStatusIsolatedIntegrationTests,!VideoStatusSqlBaselineTests,!VideoStatusCounterIsolatedTests,!VideoStatusLuaCounterTests`
    → `66 tests, 0 failures, 0 errors, 0 skipped`。
- 隔离 IT：`15 tests, 0 failures, 1 skipped`（可选 benchmark），输出
  `T03 blocked window rounds=100 deferred=100 quarantined=0`。
  此为子代理上一轮结果。父代理最终补跑：87 个无基础设施用例全通过；62 个隔离用例中61通过、1个可选跳过。真实 MySQL 新用例 `upperBoundarySplitBatchMakesProgressUsingRealNegativeFirstQuery` 验证上限依赖未确认时延后、依赖确认后 LIMIT=1 负优先备选先落库，再刷 +1，两个事件都 FLUSHED，DB=Redis=Long.MAX。100轮真实消费者下界窗口也全部收敛。

## 6. 已知限制与后续任务

- **常量退避未配置化**：`DEFER_BACKOFF_MILLIS` / `DEFER_ALERT_BUDGET` 目前是常量；T05 已提供
  `FLUSH/DEFERRED` 计数、最老待处理年龄与积压告警，但常量配置化仍未做，留待后续容量调优。
- **只告警不升级**：永久无法成立的负增量（例如业务侧真的少记了一次）会一直延后并周期性 ERROR，
  需要 T09/T10 提供受控查询与处置；本任务不自动改写统计、不自动隔离。
  上界侧同理：如果计数真的停在 `Long.MAX_VALUE` 且没有取消事件来抵销，`+1` 也会一直延后并告警；
  这是“不误隔离合法事件”的代价，处置（例如业务侧封顶）留给 T09/T10。
  批次净量超 long（如 `+MAX, +MAX`）同样靠后续拆批/相反增量收敛，本任务不做自动拆批。
- **整批延后的连带等待**：见 §2 末尾。§2.1 的备选只解决“上限侧 + 拆批 LIMIT=1 时合法负增量
  排不上队”这一种不收敛边界；下界侧的对称场景（DB=0、只有 `-1` 可见、依赖的 `+1` 迟迟不确认）
  仍然只能靠退避 + 告警，因为负增量优先无法提供缺失的正增量。
- **备选查询的代价**：每次上界/净量超 long 失败多一次 `SELECT ... FOR UPDATE SKIP LOCKED`
  （同一索引、同一事务、每批至多一次）。本轮没有做真实 MySQL 的执行计划与锁等待测量。
- **既有超界数据**：DB 中已存在 `> Long.MAX_VALUE` 的值时 `selectByVid` 会在映射阶段抛
  `DataAccessException`，当前按“DB 故障”重试；需要 T02 的数据盘点与迁移先行。
- **没有验证真实进程 kill/restart**：本地退避的丢失只影响效率这一结论基于代码路径分析，
  真实崩溃演练留给 T16。

## 7. 回滚

回滚只需撤销本任务的代码文件与测试：

- `VideoStatusFlushDeferredException` 不再抛出 → 调度器 `defer` 分支不可达；
- `flushOneVideo` 恢复为“0 行即 `VideoStatusFlushDataException`”；
- 撤销 §2.1 时删除 `selectPendingNegativeFirstForUpdate`（接口 + XML）与 `flushNegativeFirstFallbackOnce`，
  `classifyZeroRowUpdate` 的上界分支恢复为直接抛 `VideoStatusFlushDeferredException`；
  回滚后 `limit = 1` + 上界边界会重新退化为“永不收敛的延后”，需同时回退 `flushEventBatchSize` 边界场景的期望；
- 以上描述仅用于理解撤销代码的影响，不构成安全上线回滚许可。旧版会再次误隔离合法事件；存在延后事件时，优先停投/停刷保留证据并向前修复。必须排空或协调处置、统一停旧启新，并确认兼容后才可撤销。
- 不清空 dirty、事件、processed 或门闩；不能仅凭没有删数据就承诺任意回滚不会重复计数。
