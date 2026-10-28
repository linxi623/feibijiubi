# T12-B：重建候选的 MySQL 有界分页读取（父已复跑验证，T12 整项未验收）

> 状态：**父代理已复跑验证（2026-10-23），T12 整项仍未验收**。T12 仍欠“已存在但错误”的
> 强制重建（权限/审计/并发协调）与任何重建 HTTP 接口；0/1,000/100,000 规模测量本轮补上了
> opt-in 测量类并跑出诊断数字（见 §7），但每个规模只有 3 次样本、单机单 JVM，**不是**性能
> 验收，P95 只作诊断，生产容量仍是 T16 的事。因此 T12 不能勾选，也不是生产验收。
>
> **部署前置（硬依赖）**：必须先执行 `database/migrations/t10-video-status-reconciliation-indexes.sql`，
> 见 §2.2。缺这个索引时冷缓存重建会直接失败并进入重试/DLQ，不会自愈。
>
> 本文只描述一件事：`VideoStatusRebuildSnapshotServiceImpl#load(vid)` 读取 MySQL 重建候选
> （`video_status_consumed_event` 中 `process_status IN (0,1)` 的行）时，从“一次全量查询”
> 改成“同一个只读事务内的 keyset 分页”。

## 1. 原来为什么必须改

重建（`VideoStatusRebuildService#ensureInitialized`）的输入是：

```
current = video_status 基线 + 所有“已确认应用但还没落库”的事件（process_status 0/1）
```

T12-A 已经把“逐条 `EXISTS processed` 的 N 次 Redis 往返”压成每 256 条一次 pipeline，但
MySQL 侧仍然是：

```sql
SELECT ... FROM video_status_consumed_event WHERE vid = ? AND process_status IN (0,1) ORDER BY id
```

一条视频的事件行会随时间单调增长（历史行是 status 2，只是不再参与重建）。这条 SQL 的问题
不在“读了多少行”，而在**一次性把该 vid 的全部候选读进 JVM**：

- 没有上界，内存与 GC 压力由数据量决定，代码里看不到上限；
- 单条候选还要 `ObjectMapper.readValue` + 校验，转换成本同样随数据量线性增长且不可中断；
- 一旦某 vid 的候选异常膨胀（补数据、刷库堆积），重建会在持锁慢路径里长时间占用内存与
  事务，而调用方没有任何“预算”可谈。

## 2. 设计：一个事务 + keyset 分页

```
load(vid)  —— @Transactional(readOnly = true, isolation = REPEATABLE_READ)
  ├─ 基线:   video_status WHERE vid = ?                      ← 一致性快照在这里建立
  ├─ 第 1 页: id > NULL  ORDER BY id LIMIT 1000
  ├─ 第 2 页: id > 上一页最后一条 id  ORDER BY id LIMIT 1000
  └─ …直到空页/短页
```

三条硬规则：

1. **不跨事务**。MySQL 的 RR 一致性快照在事务内第一次一致性读时建立，之后同一事务里的
   每一页都读同一份快照。因此“第一页读到的候选在第二页被并发刷库删掉”“分页之后才登记的
   新事件被混进来”都不会发生。把分页搬到事务外（每页一个事务）就没有统一水位，结果可能
   重复也可能遗漏——那才是真正的错误。
2. **不用 OFFSET**。`id > afterId` 是索引上的范围起点，页大小固定且 <= 1000
   （`app.video-status.rebuild-candidate-page-size`，上限 `MAX_REBUILD_CANDIDATE_PAGE_SIZE`）。
   `OFFSET` 需要扫描并丢弃前 N 行，页越深越慢。
3. **不忽略剩余候选**。读到空页或短页才算结束；返回行数多于请求页大小、游标不前进（本页
   最后一条 id <= 上一页游标）、返回 null 都立即失败，而不是“当作读完了”。

### 2.1 为什么 SQL 里写 `FORCE INDEX`

索引 `idx_video_status_consumed_vid_id (vid, id)` 的列顺序正好等于“同一 vid + id 升序”。
实测（隔离 MySQL 8，`EXPLAIN ANALYZE`，同一 vid 300 条 status 0 候选，`LIMIT 100`）：

| 写法 | 计划 | 每页实际读到的索引项 |
| --- | --- | --- |
| 不加提示 | `idx_video_status_consumed_pending` 的两个 `process_status` 区间 + `Using filesort` | 300（该 vid 的全部候选），每页都要重读重排 |
| `FORCE INDEX (idx_video_status_consumed_vid_id)` | `(vid, id)` 有序范围扫描，无 filesort | 140（100 条命中 + 40 条被 status 过滤掉的相邻行） |

不加提示时，优化器按“两个 process_status 区间很便宜”估算，选了需要排序的方案；`ORDER BY id
LIMIT n` 一旦需要 filesort，就必须先读出该 vid 的**全部**候选才能取前 n 条，于是“加了 LIMIT”
变成“页数 × 候选数”的重复扫描——这正是 T12 计划里说的“加了 LIMIT 但忽略剩余候选”的变种。

代价要写清楚：`(vid, id)` 方案的一页会顺着 id 顺序读“命中的候选 + 被 `process_status` 过滤掉
的相邻历史行”，所以**整次重建的总索引扫描量以该 vid 的事件行数为上界**（不是候选数）。
这是有意的取舍——它仍然是线性且有界的，而 filesort 方案是页数乘候选数；若某 vid 的历史行
远超候选行、读取又确实变慢，读取预算会先失败并给出“已转换候选数”，运维可以据此判断是
数据分布问题还是页太小。

该索引是 T10 对账切片引入的 `(vid, id)` 索引（`database/migrations/t10-video-status-reconciliation-indexes.sql`，
并在 `database/feibijiubi.sql` 中镜像），**本切片不新增迁移**。索引缺失时查询直接报错，
不会静默退化成全表扫描。

### 2.2 部署前置：T10 对账索引现在是冷缓存重建的硬依赖

T12-B 之前，`idx_video_status_consumed_vid_id` 只是 T10 只读对账“更快”的前提；T12-B 之后，
它是**冷缓存重建的硬依赖**：

- `selectRebuildCandidatesAfter` 里写的是 `FORCE INDEX (idx_video_status_consumed_vid_id)`。
  索引不存在时 MySQL 直接报 `ERROR 1176 Key 'idx_video_status_consumed_vid_id' doesn't exist`，
  查询失败，**不会**悄悄退化成全表扫描或换用别的索引。
- 该失败发生在 `VideoStatusRebuildSnapshotServiceImpl#load` 里，也就是 `initializeRedis` 之前：
  不会发布半份 `current`，门闩由 `finally` 用同一 token 释放。但在消费链路上它表现为
  `DataAccessException`，`VideoStatusEventConsumer` 把这类异常当可重试：进 10s/30s/120s
  重试队列，用尽后进死信队列。也就是说**索引缺失期间，任何冷缓存视频的统计消费都会重试并
  最终 DLQ，直到运维补索引**。
- 因此部署顺序固定为：先执行 `t10-video-status-reconciliation-indexes.sql`（幂等、只加索引、
  不清数据），用脚本末尾的 `information_schema.statistics` 核对 `(vid, id)` 两列顺序，
  再发布带 T12-B 的写入方。同一部署窗口内不建议先发代码后补索引。
- 候选数超过 `rebuild-max-candidates` 抛 `RetryableMessageException`，同样走重试→DLQ。
  **没有自动修复**：既不会自动截断快照，也不会自动提高上限，更没有强制重建入口。运维必须
  根据错误信息里的 `vid` 与上限判断是数据异常膨胀还是上限/预算配置偏小，然后停写或调整配置
  （上限硬顶 `MAX_REBUILD_MAX_CANDIDATES = 10000000`）并重启（yaml 非热更新）。

## 3. 上界：有界不等于常数内存

候选仍然返回当前 DTO 的 `List<VideoStatusRebuildSnapshot.Candidate>`（调用方
`VideoStatusRebuildServiceImpl` 不改），所以：

- 内存上界 = `rebuild-max-candidates + 1` 条候选（默认 100000；发现第 N+1 条立刻失败，
  不会先把整页塞进去再判断）；
- 转换成本上界同样由这个数字决定，不再由数据量决定；
- 超过上界抛 `RetryableMessageException`，**绝不返回截断快照**：截断会让重建少算已确认事件，
  把 Redis `current` 写成偏小值——那比失败更糟（失败可重试，偏小的 current 会被当成“真实值”）。

参数与校验（`VideoStatusProperties`，启动期 `@PostConstruct` 校验）：

| key | 默认 | 允许范围 |
| --- | --- | --- |
| `app.video-status.rebuild-candidate-page-size` | 1000 | 1..1000 |
| `app.video-status.rebuild-max-candidates` | 100000 | 1..10000000 |
| `app.video-status.rebuild-snapshot-budget-ms` | 10000 | 1..3600000 |

## 4. 预算：只放弃本次读取，不假装中断 SQL

预算用 `System.nanoTime()`（单调时钟，不受系统时间调整影响）从 `load()` 开始计量，
在**取下一页之前**和**转换下一条之前**各检查一次：

- 超预算抛 `RetryableMessageException`，异常信息带“已转换候选数”，便于判断是页太大还是
  JSON 转换太慢；
- 它**不会**中断已经在执行的 SQL，也不承诺单条 SQL 在预算内返回（那需要 statement timeout，
  属于另一层配置）；已经发出的语句仍会跑完，事务随后回滚/结束。

## 5. 失败语义：没有半份初始化

两个新失败点（超上限、超预算）都在 `VideoStatusRebuildSnapshotServiceImpl#load` 内，
而 `initializeRedis` 在 `VideoStatusRebuildServiceImpl` 里、且在 `load` 之后：

```
ensureInitialized
  ├─ vid 锁
  ├─ 写无 TTL 门闩（owner token）
  ├─ 读 current 是否存在
  ├─ load()            ← 失败在这里（超上限/超预算/任何读取异常）
  ├─ classifyConfirmedPending（T12-A 的 pipeline 证据）
  ├─ initializeRedis   ← 从未执行 ⇒ 没有半份 current
  └─ finally：同一 token 释放门闩
```

- 门闩仍然由 `finally` 用同一 token 释放（`video-status-release-gate.lua` 只删自己的 token）；
- 下一次重建用**新的** owner token 重新关闭门闩并完成初始化，旧失败不残留状态；
- 锁、门闩 TTL、初始化脚本的 keys/args 形状、T12-A 的 processed 证据批次全部不变。

## 6. 测试与证据（测试环境，非生产验收）

单测 `VideoStatusRebuildSnapshotPagingTests`（16 例，纯 mock）：

- 0 条候选：只发一次分页查询；
- 恰好跨页（页大小 2 读 5 条）、页大小整倍数（4 条 = 2+2，多读一次空页才确认结束）；
- 游标严格递增（`null → 30 → 60`）；游标不前进立即失败而不是死循环；
- 返回 `null` 页失败，**不**把 null 当成“没有更多候选”的终止信号；
- 页内**每行** id 必须存在且严格递增：末行缺 id（`缺少 id 游标`）与页内乱序
  （`id 未严格递增`，信息带 `previousId`/`currentId`）都显式失败；只看页尾无法发现页内重复/跳过；
- 第一页正常、**第二页**返回行数超过请求页大小时在第二页失败，且不再发第三页查询；
- 候选数 = 上限成功、= 上限+1 失败且信息含“拒绝返回截断快照”；
- 预算在“页间”和“逐条转换”两个位置都能失败，且失败后不再发下一页查询；
- `load` 仍是 `readOnly + REPEATABLE_READ`；快照候选列表不可变。

`VideoStatusRebuildProcessedEvidenceTests#snapshotReadFailurePublishesNoInitializationAndNextAttemptTakesOver`：
快照读取失败时初始化脚本从未执行，下一次重建用新 token 接管并成功。

`VideoStatusRuntimePropertiesContextTests`：三个新配置项的 kebab-case 绑定、默认值与非法值启动失败。

隔离 IT（真实 MySQL 13307 / Redis 16379，`-DvideoStatusIT=true`）：

- `VideoStatusIsolatedIntegrationTests#rebuildPagedCandidatesMatchBaselineInsideOneRepeatableReadSnapshot`：
  写入 1000 条 status 1 候选（正好一页），spy 真实 mapper，在第一页之后由**另一个线程**提交
  一条新的 status 1 候选（id 更大）。断言两次分页查询在同一事务连接、隔离级别为
  REPEATABLE_READ、并发提交的行对快照不可见，最终 `current = 100 + 1000`——既不漏读也不重复计数。
- `#rebuildOverCandidateLimitPublishesNothingAndNextAttemptTakesOver`：上限调成 10、写入 12 条候选，
  重建抛 `RetryableMessageException`，`current` 不存在（无半份初始化）、门闩已释放；恢复上限后
  下一次重建得到 `100 + 12`。

### 6.1 父代理复跑与本轮复验

- 父代理（2026-10-23）普通回归 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test`：
  **497 tests，0 失败/错误，144 skipped**（`/tmp/improvement-parent-unit-final.log`）。
- 父代理 opt-in 隔离回归
  `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusIsolatedIntegrationTests,VideoStatusStatus3ReplayIsolatedTests' test`：
  **31 tests，0 失败/错误，1 skipped**（20 例全链路 + 11 例 B1，`/tmp/improvement-parent-t12b-it.log`）。
- 本轮加了行级 id 校验、4 例分页用例与规模测量类后复跑：普通回归 **504 tests，0 失败/错误，
  147 skipped**（`/tmp/t12b-worker-unit.log`，新增 3 例规模测量默认 skipped）；
  `VideoStatusIsolatedIntegrationTests` **20 tests，0 失败/错误，1 skipped**
  （`/tmp/t12b-worker-it.log`）。
- 历史保留：T10-B1 记录过的两个并发用例（`concurrentColdStartAndDuplicateDeliveryConverge`、
  `concurrentFlushAndConsumptionPreserveAllEvents`）曾经报 2 errors，而本轮两次隔离运行都通过。
  这是**间歇性**并发问题，根因未定位、未修复，不能因为本轮通过就写成“已解决”或删掉历史记录。
- 恢复轮（2026-10-23）：只串行复跑了 `VideoStatusRebuildSnapshotPagingTests` **16 tests，0 失败/
  错误/跳过**（受限堆 `-DargLine=-Xms64m -Xmx512m`）与规模测量类 **3 tests，0 失败/错误/跳过**
  （`/tmp/t12-scale-recovery.log`）；未改代码，普通全量回归与隔离 IT 沿用父代理与本轮前面的记录。

## 7. 规模测量（T12 性能验收的准备，不是验收）

测量类是 `VideoStatusRebuildScaleIsolatedTests`，**默认跳过**，只在显式
`-DvideoStatusScale=true` 时运行；普通 `-Dtest=*Tests` 回归把它记为 skipped，
`-DvideoStatusIT=true` 的按类名过滤也不会包含它。它复用与现有隔离 IT 完全相同的端点保护
（`IsolationGuard`：MySQL 13307 / Redis 16379 / RabbitMQ 15673，拒绝任何覆盖），
只使用 `compose.test.yaml`，不启监听器、不投递/消费消息、不改事件状态。

```bash
docker compose -f compose.test.yaml up -d --wait
./mvnw -DvideoStatusScale=true -Dtest=VideoStatusRebuildScaleIsolatedTests test
# Windows: ./mvnw.cmd -o -DvideoStatusScale=true -Dtest=VideoStatusRebuildScaleIsolatedTests test
```

### 7.1 测量方法

- 每个规模（0 / 1,000 / 100,000 候选）**重复 3 次冷启动**：每次只删本测试 vid 的 `current`，
  门闩与 processed 证据保持真实状态，然后调用真实 `ensureInitialized(vid)`（真实 vid 锁、
  无 TTL 门闩、真实 keyset 分页、真实 pipeline EXISTS）。
- 造数不走逐条网络：MySQL 用 **500 行一条**的多行 `INSERT`，Redis processed 证据用
  **1000 key 一次** pipeline `SET`；候选全部 `process_status=0` 且带真实 processed 证据，
  所以每个候选都会进一次 pipeline `EXISTS`。
- **预设 oracle（独立于重建代码）**：`playTimes = 基线 100 + 候选数`，并交叉核对
  `COUNT(*) WHERE vid=? AND process_status IN (0,1)`、`COUNT(DISTINCT event_id)`、
  `SUM(delta)` 都等于候选数，其余七个计数为 0，门闩已释放，事件状态未被改写（无 status 2）。
  漏读或重复计数都会让断言失败。
- 指标口径：
  - **候选内存上界**是代码上界 `rebuild-max-candidates + 1 = 100001` 条候选（配置值，不是测量值）；
  - **heap**：`System.gc()` 后的已用堆 + 各 heap 池 `resetPeakUsage()` 后的池峰值之和。
    池峰值之和是**上界近似**（各池峰值不在同一时刻，且包含候选以外的临时对象），
    **不是**候选对象的 retained size，也不是精确瞬时峰值；第 1 次重复含 JIT/连接预热，
    数值明显偏大，不能据此推算“单条候选占多少字节”；
  - **pipeline 调用/命令数**由 `RedisUtils` spy 统计，是**逻辑**次数，不是物理 RTT；
  - **重建耗时/门闩持有**是进程内 `System.nanoTime()`；门闩窗口 = 门闩写入调用返回 →
    释放脚本调用返回；
  - **P95 不报告**：每规模只有 3 个样本，输出里写 `p95=n/a(samples=3 < 20, diagnostic only)`。

### 7.2 实测结果（本机隔离环境，2026-10-23，单 JVM）

一次完整类运行（3 个用例 9 次测量，52.98 s，0 失败/错误，`/tmp/t12-scale-full.log`）：

| 候选数 | 重建耗时 ms（3 次） | 门闩持有 ms（3 次） | pipeline 调用 | EXISTS 逻辑命令 | heap 池峰值−运行前（3 次） | current |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 18 / 18 / 22 | 11 / 11 / 15 | 0 | 0 | 12.0 / 0.0 / 0.0 MiB | 100 |
| 1,000 | 164 / 190 / 379 | 155 / 180 / 332 | 4 | 1,000 | 109.0 / 20.0 / 12.0 MiB | 1100 |
| 100,000 | 7,789 / 8,143 / 9,649 | 7,779 / 8,136 / 9,640 | 391 | 100,000 | 169.7 / 209.8 / 240.8 MiB | 100100 |

另一轮单独跑同样三个用例（`/tmp` 未单独保存，输出与上表同量级）：0 → 36/43/228 ms，
1,000 → 163/213/713 ms，100,000 → 7,953/8,129/9,328 ms。

第三轮（2026-10-23 恢复轮，受限堆 `-DargLine=-Xms64m -Xmx512m`，`/tmp/t12-scale-recovery.log`，
3 个用例 9 次测量，43.76 s，0 失败/错误）：

| 候选数 | 重建耗时 ms（3 次） | 门闩持有 ms（3 次） | pipeline 调用 | EXISTS 逻辑命令 | heap 池峰值−运行前（3 次） | current |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 17 / 19 / 24 | 10 / 12 / 17 | 0 | 0 | 11.0 / 0.0 / 0.0 MiB | 100 |
| 1,000 | 179 / 184 / 405 | 171 / 177 / 355 | 4 | 1,000 | 23.2 / 18.0 / 10.0 MiB | 1100 |
| 100,000 | 5,349 / 5,609 / 6,152 | 5,343 / 5,602 / 6,146 | 391 | 100,000 | 131.5 / 182.8 / 174.8 MiB | 100100 |

三轮合计 100,000 候选的 9 个样本落在 **5.3–9.6 s**（第一轮 7.8–9.6 s，第三轮 5.3–6.2 s；
差异来自 JVM 预热/缓存与本机负载，不能当成“优化效果”），门闩持有与重建耗时几乎相同
（差值 < 20 ms）。

可直接读出的结论（仅限测试环境）：

- pipeline 调用次数严格等于 `ceil(候选数/256)`，EXISTS 逻辑命令数严格等于 status0 候选数：
  “每条事件一次 RTT”确实被有界批次 pipeline 取代；
- 0/1,000/100,000 三个规模都**不漏不重**（预设 oracle 每次重复都断言）；
- 100,000 候选时重建耗时与门闩持有都在 5–10 s 量级（三轮 9 个样本 5.3–9.6 s）。

### 7.3 限制与风险（必须一起读）

- **不是性能验收**：单机、单 JVM、无并发写入、无 Redis/MySQL 故障注入、无慢盘/跨机房网络；
  每规模 3 个样本，P95 只作诊断，生产容量仍是 T16。
- 测量把 `rebuild-snapshot-budget-ms` 显式放宽到 1 小时（否则默认 10 s 预算会把测量本身截断），
  其余配置保持默认（页大小 1000、候选上限 100000）。
- **默认预算与默认上限同一量级**：本机 100,000 候选整次重建 5.3–9.6 s（三轮 9 个样本），其中绝大部分是快照读取
  （分页 SQL + 逐条 JSON 转换），而默认 `rebuild-snapshot-budget-ms = 10000`。也就是说默认配置下
  接近上限规模的冷重建可能触发“超出预算”并进入重试/DLQ。本轮**没有**在默认预算下跑 100,000
  规模，所以不能断言默认配置一定会失败；生产上线前必须按实际候选规模与硬件重新评估预算与上限。
- heap 指标只是可观测近似：不要把它写成“候选内存峰值”，也不要据此推算单条候选大小。
- 本类会在隔离库里留下自己的 `video_status_consumed_event` 行（不清测试库），只删除本测试 vid
  自己的 `current`/门闩/dirty/hot 成员与自己的 processed 证据。

## 8. 明确未做

- 生产容量/性能验收（含 P95、真实并发、故障注入）：本轮只给出 3 样本/规模的诊断数字，
  不构成验收，也不外推到生产；
- “已存在但错误”的强制重建（`current` 存在但偏小/偏大）：需要管理员权限、审计与并发协调，
  属于 T12 后续；
- 任何重建 HTTP 接口、deadlock 专项处理、跨批水位协议（释放锁后继续分页需要新的协议设计，
  本切片不做）；
- 任意 Redis 数据丢失、进程崩溃、生产容量：不在本切片保证范围（见 T09/T16）。
