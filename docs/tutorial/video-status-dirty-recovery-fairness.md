# T11：dirty 恢复的公平扫描与失败退避

> 适用代码：`VideoStatusDirtyRecoveryScheduler`、
> `VideoStatusConsumedEventMapper#selectPendingVidsAfter`。
> 状态：候选 `be7d836` 已在测试环境完成 T11 验收（2026-10-22）；验收证据见 §6.1。
> 该结果不代表生产验收，也不包含进程崩溃或真实基础设施故障演练。

## 1. 为什么需要 T11

恢复调度器负责把 MySQL 中 `process_status = 1`（待落库）的视频重新补回 Redis 的
`dirty` Set，让落库调度器有机会再次处理它们。

旧实现每次执行：

```sql
SELECT DISTINCT vid
FROM video_status_consumed_event
WHERE process_status = 1
ORDER BY vid
LIMIT #{limit}
```

它永远从最小 `vid` 开始。如果前 `limit` 个视频持续失败（例如它们一直无法被补回 dirty，
或后续落库一直失败），后面的视频就永远排不到这一批，形成“头部饥饿”：

- 250 个待恢复视频，批量 100；
- 前 100 个持续失败；
- 后 150 个即使健康，也可能永远得不到恢复机会。

T11 的目标不是提高吞吐，而是保证恢复机会公平，并且失败视频不会造成每轮热循环。

## 2. keyset 游标：不跳视频，也不无限扫描

新增 Mapper 查询：

```sql
SELECT DISTINCT vid
FROM video_status_consumed_event
WHERE process_status = 1
  AND vid > #{afterVid}
ORDER BY vid
LIMIT #{limit}
```

调度器在内存中保存 `cursor = 上一轮最后扫描到的 vid`：

1. 本轮查询 `vid > cursor`；
2. 有结果就按 vid 升序处理，并把游标推进到本批最后一个 vid；
3. 没有结果且 `cursor != null`，说明已到末尾：把游标清空，再从最小 vid 回绕查询一次；
4. 如果回绕查询也为空，本轮直接结束。

这样：

- 前 100 个失败不会阻止第 101～200、201～250 个被扫描；
- 250 个视频、批量 100 时，健康尾部最多 3 轮就能全部获得机会（计划要求 ≤ 4 轮）；
- 游标到达末尾后能回绕，不会只扫描一次就停止；
- 每轮最多一次正常查询 + 一次回绕查询，不会无限扫描。

游标只存在当前实例内存。重启或多实例各自丢失游标时，最坏只是从头再扫一遍；
因为 dirty 恢复只是 `SADD`，重复补回不会重复计数，正确性不受影响。

## 3. 单轮预算与游标推进

`round-budget-ms` 仍然限制单轮工作量。预算检查发生在处理下一条之前：

- 如果预算在批内耗尽，游标只推进到**已经处理过**的最后一条；
- 同批剩余视频下一轮会从该游标之后继续，不会因为整批游标被提前推进而饿死；
- 预算检查不会强杀已经开始的 Redis 调用，这与 T06 的预算语义一致。

`be7d836` 修复了候选实现中的一个具体缺陷：如果预算在**首个 vid 尚未执行**时就已经耗尽，
旧候选会把游标回退/清空，丢掉上一轮已经推进的 keyset 进度，极端预算或首条调用很慢时会反复
从头部重扫。现在只有真正处理过至少一条时才推进游标；一条都没执行时保留原有游标。

## 4. 失败视频的本地有界退避

如果某条 `SADD` 失败，调度器记录：

```text
vid -> 下次允许重试的时间（当前常量 2 秒）
```

退避表使用 `LinkedHashMap` LRU，硬上限 4096 条：

- 失败视频不会在每一轮被反复重试，避免热循环；
- 表满后淘汰最久未使用的条目，代价只是可能提前重试一次，不影响正确性；
- 重启/多实例会忘记退避，同样只影响效率；
- 待恢复依据始终是 MySQL 中 `process_status = 1` 的事件，不依赖内存状态。

## 5. 保持 dirty 协议不变

T11 没有引入任何 `SREM`：

- 恢复路径只对 dirty Set 执行 `SADD`；
- 并发消费者新增的 dirty 标记不会被恢复调度器误删；
- 落库调度器仍保留“只补加、不并发 SREM”的既有协议；
- 计数幂等门闩、vid 分布式锁、无 TTL token 门闩均未改动。

## 6. 单测覆盖

`VideoStatusDirtyRecoveryFairnessTests` 覆盖：

1. 250 个 pending、批量 100，前 100 个持续失败：
   后 150 个在不超过 4 轮内全部获得补回机会；失败头部也获得首次机会；
2. 游标到末尾后回绕；游标之后新增 pending 视频能被继续扫描；
   模拟重启（新调度器、游标为 null）后从最小 vid 重新开始，不漏低 vid；
3. 批内预算耗尽时只推进到已处理 vid，下一轮继续处理同批剩余视频；
4. 所有场景均验证恢复路径从未调用 `SREM`。

运行命令：

```bash
./mvnw -Dtest=VideoStatusDirtyRecoveryFairnessTests test
```

Windows PowerShell：

```powershell
.\mvnw.cmd "-Dtest=VideoStatusDirtyRecoveryFairnessTests" test
```

真实 MySQL 隔离验收由 `VideoStatusDirtyRecoveryFairnessIsolatedTests` 承担（`-DvideoStatusIT=true`）：
复用既有连接拒绝护栏，只新增动态独立 vid 数据，不清表、不删除其它测试数据；
直接验证 `selectPendingVidsAfter` 的真实 SQL 游标/回绕，并让调度器在 250 个 pending、
批量 100、前 100 个持续失败时，四轮内触达后 150 个健康 vid。该 IT 不在无基础设施的
默认单测中运行；父代理已在隔离环境复跑，结果见 §6.1。

```powershell
.\mvnw.cmd -o -DvideoStatusIT=true "-Dtest=VideoStatusDirtyRecoveryFairnessIsolatedTests" test
```

### 6.1 验收证据（2026-10-22）

父代理本次实跑命令：

```powershell
.\mvnw.cmd -DvideoStatusIT=true "-Dtest=VideoStatusDirtyRecoveryRealConvergenceTests,VideoStatusDirtyRecoveryFairnessIsolatedTests" test
```

结果：**3 tests，0 failures / 0 errors / 0 skipped**，日志 `/tmp/t11-real-convergence.log`；关键输出：

```text
T11 real convergence vids=250 head=100 tail=150 flushRounds=1 oracleTotal=250
```

证据含义：

- 环境是独立 `compose.test.yaml` 中的**真实 MySQL 和 Redis**，只新增本用例专属的 250 个视频、
  统计基线和 pending 事件，不清理历史数据。
- 故障注入只针对专用头部 100 个 vid 的 `SADD`，不是进程崩溃、真实 Redis/MySQL 宕机或两 JVM 演练。
- 四轮恢复内 150 个健康尾部全部获得真实 dirty 机会；释放故障并越过本地退避后，头部也被重新
  补回，最终 250 个视频全部落库收敛，全程没有 `process_status=3`（不误隔离）。
- 测试 oracle 来自独立 seed 的预期值，并同时核对事件表 `SUM(delta)`；最终 `oracleTotal=250`
  与数据库总和一致，不是用被测代码自身输出反推。
- 公平性单测 `VideoStatusDirtyRecoveryFairnessTests` 的 5 个用例及既有 27 个相关测试由子代理
  运行通过；父代理本次只复跑上述真实 IT，未复跑该批单测。
- `be7d836` 修复了预算在首个 vid 未执行时回退/丢失游标的缺陷，验收覆盖该修复后的实现。

这些结果是**测试环境验收**，不能称为生产验收；生产容量、真实基础设施故障和跨进程恢复仍交 T16。

## 7. 边界与后续

- 游标、失败退避都是进程内状态，重启后重置；这是有意为之，避免新增无界持久状态。
- 多实例会各自维护游标，恢复结果可能重复 `SADD`，但不会重复计数；公平性按单实例轮转保证。
- 本任务不改变 SQL 表结构、不新增 HTTP/MQ 协议、不修改计数幂等和门闩协议。
- 生产环境的真实 Redis/MySQL 故障、进程级重启和容量表现仍属于后续演练范围，不能由本单测替代。
- 本次验收的故障是专用 vid 上的进程内 SADD 注入，不是进程崩溃，也不是生产验收。
