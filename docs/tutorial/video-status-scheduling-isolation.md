# 视频统计调度隔离与单轮执行预算（T06）

> 状态：T06 已由父代理独立验收并合入（实现提交 `3e33415`，验收日期 2026-10-22）。
> 本文只描述“调度资源怎么分、一轮最多干多少活”，不改变任何计数、幂等、门闩或状态迁移语义，
> 也不代表已批准生产上线。
>
> 范围：Outbox 投递、批量落库、dirty 恢复三条调度职责。
> 不在范围：Outbox 发布/回写/租约细化（T07）、dirty 公平扫描（T11）、
> status0 补偿（T09）、处置入口（T10）。

## 1. 问题：默认调度器只有一条线程

改动前三条 `@Scheduled` 任务共用 Spring Boot 自动配置的 `taskScheduler`，默认线程池大小是 1。
后果不是“慢一点”，而是**互相拖死**：

- `relay()` 在 `publisher.publish(...)` 里同步等待 RabbitMQ confirm（`publish-confirm-timeout-seconds`，
  默认 5 秒，故障时被拖到上限）。这期间默认调度线程被占住；
- 同一时间，`flushDirtyVideos()` 与 `recoverDirtyVids()` 根本没机会运行：
  已经消费成功、Redis 已生效的事件无法落库，恢复心跳也一起停摆；
- 监控看到的是“整条链路无进展”，但真实原因只是投递在等 confirm。

T06 的验收要求正是这个场景：把发布 confirm 人为阻塞 30 秒，已经消费成功的事件仍要在
10 秒测试预算内落库，恢复调度仍有心跳。

## 2. 设计：三条职责三条单线程调度器

新增 `config/VideoStatusSchedulingConfig`，提供三个稳定 bean 名（`@Scheduled(scheduler = ...)`
按名字查找，写错名字只会在完整上下文启动时才失败，因此单测用反射固定这层契约）：

| Bean 名 | 使用方 | 线程名前缀 | 池大小 |
|---|---|---|---|
| `videoStatusRelayScheduler` | `VideoStatusOutboxRelay#relay` | `video-status-relay-` | 1 |
| `videoStatusFlushScheduler` | `VideoStatusBatchFlushScheduler#flushDirtyVideos` | `video-status-flush-` | 1 |
| `videoStatusRecoveryScheduler` | `VideoStatusDirtyRecoveryScheduler#recoverDirtyVids` | `video-status-recovery-` | 1 |

公共设置：

- **池大小 1 + `fixedDelay`**：`fixedDelay` 的语义是“上一轮结束后才安排下一轮”，
  所以单线程就能保证**同一职责永不重叠执行**；不需要、也不允许再开线程做“并行提速”。
- **固定线程名前缀**：线程 dump 与日志能直接看出是哪条职责卡住。
- **`setRemoveOnCancelPolicy(true)`**：取消/停机后立刻把已取消任务移出队列，不留引用。
- **自定义 `ErrorHandler`**：任务抛异常只记一条带 `scheduler=` 与 `thread=` 的 ERROR，
  **吞掉**异常。`fixedDelay` 任务不会因为一次异常被取消，其它两条调度器也完全不受影响。
  单测用“每 10ms 抛一次异常的任务”验证后续轮次仍在执行。
- **有界停机**：`setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(5)`。
  停机时先停止安排新一轮，最多等 5 秒让运行中的一轮结束；超过 5 秒容器不再等待。
  **不承诺**强杀正在进行的 JDBC/AMQP 调用——是否响应中断由驱动决定。

### 2.1 关于队列：必须诚实说明

`ThreadPoolTaskScheduler` 底层是 `ScheduledThreadPoolExecutor`，它的内部队列按 JDK 设计是
**无界**的。本方案**没有**把它改成有界队列，也**不声称**这是一套通用“有界队列”方案。

这里可以接受它的唯一理由是使用方式：

- 三个调度器只承载 **3 个固定的重复任务**（每个池子 1 个），业务代码不会向它们逐条提交任务，
  因此队列长度**不随消息数/视频数增长**；
- `fixedDelay` 不会在上一轮没结束时堆积下一轮；
- 真正的“每轮工作量”由 `round-budget-ms` 与各批量上限约束。

禁止事项：不要为了“并发落库”把每个 vid 包装成任务提交到某个线程池——那才会把无界队列
变成真实的积压风险。线程总数固定为 **3 条调度线程 + T05 的 1 条 metrics 采样线程**。

## 3. 单轮预算 `app.video-status.round-budget-ms`

新增配置项（毫秒，默认 `5000`，启动期校验与 T04 的其它时长配置同一套规则）：

```yaml
app:
  video-status:
    # 可选，默认 5000；单位毫秒
    round-budget-ms: 5000
```

语义严格限定为：

- 每轮开始时用 `System.nanoTime()` 记起点（`nanoTime` 不受系统时钟回拨影响）；
- **在领取/处理下一条之前**检查是否超预算；
- 超预算就结束本轮，**未处理的工作留在原地**（Outbox 表 / Redis dirty Set / 消费事件表），
  下一轮继续；
- 预算**不能强杀**已经开始的单条工作：publish 由 `publish-confirm-timeout-seconds` 界定，
  落库由 vid 锁等待（100ms）与数据库事务/锁等待界定。

### 3.1 投递：逐条领取，不再预领一批

`VideoStatusOutboxClaimService` 新增 `claimBatch(int limit)`，原无参 `claimBatch()` 保留且行为不变
（仍按 `outbox-batch-size` 领取）。`relay()` 改为：

```text
每轮：最多 outbox-batch-size 条
  检查预算（在每一次领取之前）
  claimBatch(1)  -> 立刻 publish + markSent/markPending
```

为什么不预领 100 条再慢慢 publish：租约（`outbox-lease-seconds`）从领取那一刻开始计时，
预领后排在队尾的消息会在等待 confirm 期间就把租约耗完，被别的实例当成“死消息”接管，
造成无谓的重复投递。逐条领取让租约窗口只覆盖它自己。单条失败分类与重试退避保持原样
（这部分属于 T07 的重构范围，本任务不提前做）。

### 3.2 落库：SPOP 限批 + 每 vid 短锁等待 + 预算

- `SPOP` 批量仍受 `flush-dirty-batch-size` 限制；
- 每个 vid 取锁等待从 **10 秒改为 100 毫秒**（新增 `VideoStatusVidMutex#withLock(vid, waitMillis, ...)`
  重载）。理由：批量落库是“扫一批 vid、能落就落”，一个被长时间占用的视频不该把整轮预算
  耗在等锁上；拿不到锁就把 dirty 放回去，下一轮再试；
- **默认重载（消费、重建等必须拿锁的路径）仍是 10 秒**，异常类型、Watchdog 续租、
  指标语义全部不变；
- 预算用尽时，把**当前这条与剩余全部** dirty 逐个 `SADD` 回去；**绝不 `SREM`**
  （`SREM` 会误删并发消费者刚加入的新标记，导致“无人落库”）；
- 原有的延后退避（2 秒、`BigInteger` 净量、上限回退、告警预算）完全不动。

### 3.3 恢复：预算用尽留 DB，下一轮反哺

`recoverDirtyVids()` 仍然只做“从消费事件表读待处理 vid → `SADD` 回 dirty Set”。
预算用尽时直接结束本轮，**未处理的 vid 不搬进任何内存队列**：它们仍在消费事件表
（`process_status = 1`），下一轮 `selectPendingVids(limit)` 会再次反哺。

已知局限（交给 T11）：当前 `selectPendingVids` 是固定顺序的 `LIMIT`，如果前 N 个 vid
长期失败，后面的 vid 可能长时间轮不到。T11 会用稳定游标/分片 + 失败退避解决。
本任务不提前改公平性算法。

## 4. 多实例与阻塞边界

- 多实例之间**仍然只靠现有机制协调**：Outbox 租约 token + vid 锁。本任务没有引入
  领导者选举、分布式调度锁或新的协调协议；
- 三条调度器是**进程内**资源隔离，不是分布式配额。多实例部署时总并发 = 实例数 × 每实例 1 条；
- 单个阻塞的数据库调用：调度线程不会被“预算”打断。当前只有 T05 的指标查询显式带了
  `timeout="3"`，业务 mapper（落库、领取、恢复查询）**尚未统一添加语句超时**；
  建议在 T16/运维层配合 JDBC `socketTimeout`/`max_execution_time` 设置边界。
  本任务不承诺硬中断事务，也不通过强杀线程来“保证”预算。

## 5. 与测试的兼容性

- 三个调度类仍然可以**纯构造**：`new VideoStatusBatchFlushScheduler(...)` 等不依赖 Spring；
- `VideoStatusOutboxRelay` / `VideoStatusBatchFlushScheduler` / `VideoStatusDirtyRecoveryScheduler`
  的构造器签名未变；
- `VideoStatusVidMutex` 旧的两个重载保留，新增两个带 `waitTimeMillis` 的重载；
- `claimBatch()` 保留，新增 `claimBatch(int)`；
- 现有单测里把 `claimBatch()` 打桩的地方改为 `claimBatch(1)`（并返回
  `thenReturn(List.of(item), List.of())`，否则循环会重复领取同一条 mock 数据），
  把 `withLock(anyInt(), any(Runnable.class))` 的落库路径桩改为
  `withLock(anyInt(), anyLong(), any(Runnable.class))`；消费/重建路径的桩保持不变。

## 6. 新增/调整的测试

| 测试 | 固定的行为 |
|---|---|
| `config/VideoStatusSchedulingConfigTests` | 三个独立单线程 bean、稳定 bean 名、线程名前缀生效、`removeOnCancelPolicy`、`@Scheduled(scheduler=...)` 与 bean 名一致（反射）、任务异常后后续轮次继续、同一调度器内不并发、停机有界且不被长任务拖住 |
| `service/video/VideoStatusSchedulingIsolationTests` | 逐条 `claimBatch(1)`、每轮最多 `outbox-batch-size` 条、预算耗尽后不再领取（剩余留 Outbox）、慢 publish 不被强杀、flush 预算耗尽把剩余 dirty 全部补回且不 `SREM`、一个 vid 锁忙不影响同轮健康 vid、调度轮用 100ms 等待而默认仍是 10 秒、恢复预算耗尽留 DB、`claimBatch` 的 limit 校验 |
| 既有 `VideoStatusOutboxRelayTests` / `VideoStatusTelemetryInstrumentationTests` / `VideoStatusDirtySchedulingTests` / `VideoStatusFlushTestFixture` | 仅把打桩改为新重载/新领取签名，断言语义不变 |

验证命令（无需基础设施）：

```bash
./mvnw '-Dtest=VideoStatusMetricsSamplerTests,VideoStatusMetricsTelemetryTests,VideoStatusMetricsComponentWiringTests,VideoStatusMetricsAuthorizationTests,VideoStatusTelemetryInstrumentationTests,VideoStatusSchedulingConfigTests,VideoStatusSchedulingIsolationTests' test
```

父代理 2026-10-22 独立复跑结果：**69 tests，0 failures / 0 errors / 0 skipped**；日志
`/tmp/improvement-t05-t06-unit.log`。这批同时包含 T05 指标单测，不把 69 拆成 T05/T06 的
虚假细分。

隔离环境验收（消耗真实 MySQL/Redis/RabbitMQ）：

```bash
docker compose -f compose.test.yaml up -d --wait
./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusMetricsIsolatedTests,VideoStatusSchedulingRealFlowTests' test
```

父代理独立复跑结果：**8 tests，0 failures / 0 errors / 0 skipped**；日志
`/tmp/improvement-t05-t06-it.log`。T06 真实链路输出：
`blocked publish millis=30082 flushMillis=62 flushRuns=267 recoveryRuns=144`；
并发落库输出：`baseline=100 flushed=100`。

> 边界必须说清：这里的“阻塞 30 秒”是测试内**受控阻塞**，不是真实网络断连 30 秒；
> “两个实例”实际是两个独立调度器对象跑在**同一个 JVM**、共享同一套 Redis 分布式锁，
> 不是两个 JVM。结论只适用于该隔离测试环境。

## 7. 验收对照（T06）

| 计划验收点 | 本实现 |
|---|---|
| 发布 confirm 阻塞 30 秒，已消费事件仍能在 10 秒预算内落库 | 投递与落库分属两条线程，互不等待；落库轮预算 5 秒（可配），单 vid 锁等待 100ms |
| 恢复调度仍有心跳 | 恢复调度独立线程，`RECOVERY_TICK` 心跳不受投递/落库影响 |
| 阻塞一个视频不应无限阻塞其他视频 | 每 vid 锁等待 100ms，超时只把该 vid 的 dirty 放回，继续下一个 |
| 两个实例同时工作仍保持单次有效落库 | 协调机制未变：Outbox 租约 token + vid 锁；验收用同 JVM 内两个独立调度器共享同一 Redis 锁，baseline=100 / flushed=100 |
| 限定线程数、排队长度、单轮批量、最长处理预算 | 3 条单线程；只提交 3 个固定重复任务（无业务逐条提交）；`outbox-batch-size` / `flush-dirty-batch-size` / `round-budget-ms` |
| 线程名、拒绝策略、优雅停机、异常隔离 | 固定线程名前缀；`removeOnCancelPolicy`；5 秒有界停机；自定义 `ErrorHandler` |

“拒绝策略”说明：三个调度器只承载固定重复任务，正常运行时不会触发拒绝；
停机后 Spring 会拒绝新任务（`acceptTasksAfterContextClose=false` 默认值），
本任务没有自定义 `RejectedExecutionHandler`——因为这里没有“业务提交被拒绝”的语义，
自造一个只会掩盖停机行为。

## 8. 风险、开关与回滚

- **无新增业务开关**：调度隔离与预算对业务行为是“更早结束一轮、更晚再试”，
  不改变最终一致性；`round-budget-ms` 只是可选的调优项，默认值即安全值。
- **回滚**：恢复三个 `@Scheduled` 的默认调度器（删除 `scheduler=` 与配置类）、
  让 relay 回到 `claimBatch()`、让 flush 回到 10 秒等待即可；不涉及数据库/Redis/MQ 数据，
  也不需要清理 Outbox 或 dirty 标记。
- **剩余风险**：单轮预算不能强杀阻塞调用；业务 SQL 尚未统一加语句超时；
  dirty 扫描公平性、Outbox 租约/回写细节仍分别由 T11、T07 承担。
- **验收边界**：受控 30 秒阻塞、同 JVM 双调度器与隔离端点均已验收；未在真实网络断连、
  两 JVM 部署、正式负载和生产环境演练，不构成生产验收，系统级演练交 T16。

## 9. 下一任务

- T07：Outbox 发布/回写/租约细化（本任务刻意不改 `publishOne` 的失败分类与退避）。
- T11：dirty 恢复的稳定游标与失败退避（本任务只加预算，不改扫描顺序）。
