# 视频统计消费重试退避与 ACK 边界（T08 候选第一部分）

> 状态：T08 **候选第一部分**的代码与纯单元测试已完成，基于 T07 `eec1e01`，**等待父代理独立审查**。
> 本文不修改 `video-status-improvement-plan.md`、`AGENTS.md`/`CLAUDE.md` 或 HTTP API，也不宣称
> 已完成 T08 全部验收，更不宣称通过真实 RabbitMQ/Redis 集成测试。
>
> 本部分范围：三级固定 TTL 重试队列、转发失败后的受控暂停/异步恢复、ACK 异常与业务异常隔离。
> 不在本部分：status0 自动补偿（T09）、DLQ/FAILED/status3 对账处置（T10）、真实故障演练（T16）。

## 1. 旧实现的问题

旧消费者在业务异常后把消息转发到**唯一一个 10 秒 TTL 重试队列**。这带来三个问题：

1. **退避不可分级**：所有重试都固定 10 秒，持续故障时尝试频率过高。
2. **转发失败后立即 requeue**：`basicNack(requeue=true)` 会让原消息马上重新入队，broker 或网络
   短暂故障时形成无界热循环。
3. **ACK 与转发混在同一个 `try`**：转发已成功、随后 ACK 抛错时，旧代码会把 ACK 错误当成
   “转发失败”再次 NACK，甚至可能把已经正常处理成功的消息误送 DLQ。

## 2. Spring AMQP 3.2.11 API 核实结论

本任务没有假设存在 `pause()`。实际核对本地依赖
`spring-rabbit-3.2.11.jar` 后确认：

- `MessageListenerContainer` 暴露的是 Spring `SmartLifecycle` 的 `start()` / `stop()` /
  `isRunning()`，**没有 `pause()`/`resume()`**；
- `RabbitListenerEndpointRegistry#getListenerContainer(String id)` 可按监听器 id 精确取得容器；
- `AbstractMessageListenerContainer#stop()` 会等待消费者退出，因此**不能从 listener 线程同步调用**，
  否则就是“自己等自己”，会卡到 shutdown timeout 甚至死锁。

所以本实现使用：**listener 线程只登记请求 → 专用单线程调度器异步 stop → 有界延迟后 start**。

## 3. 三级固定 TTL 队列

保留原有 10 秒队列的队列名、routing key 和 TTL 参数不变，新增 30 秒、120 秒两级：

| 级别 | Queue | Routing key | TTL | 到期后 |
|---|---|---|---|---|
| 第 1 级 | `video.status.retry.queue.v1`（原样） | `video.status.retry.v1`（原样） | 10,000 ms（原样） | DLX → 主交换机/主队列 |
| 第 2 级 | `video.status.retry.queue.30s.v1` | `video.status.retry.30s.v1` | 30,000 ms | DLX → 主交换机/主队列 |
| 第 3 级 | `video.status.retry.queue.120s.v1` | `video.status.retry.120s.v1` | 120,000 ms | DLX → 主交换机/主队列 |

选路规则（`VideoStatusMessageForwarder#retryRoutingKey`）：

- 第 1 次重试 → 10 秒队列；
- 第 2 次重试 → 30 秒队列；
- 第 3 次及以后 → 120 秒队列。

每个队列只使用**一种固定 TTL**。这样一条 120 秒消息不会堵在 10 秒队列队头，避免不同过期时间
混用造成的队头阻塞。重试消息仍通过 `MessageBuilder.fromClonedMessage` 保留原 body、`messageId`
（eventId）和既有 Header；消费端继续用 eventId + 指纹幂等吸收重复，仍是**至少一次投递**，
不承诺跨 RabbitMQ/MySQL 的 exactly-once。

## 4. 转发失败：受控暂停与异步恢复

新增 `VideoStatusConsumerRecoveryController`：

1. 消费者转发 retry/DLQ 失败时**不 ACK、不 NACK**，只调用 `requestRecovery(reason)`；
2. `AtomicBoolean recoveryInProgress` 是 gate：恢复周期内后续失败请求直接合并，不再排队；
3. 通过专用**单线程** `ScheduledExecutorService` 执行 `container.stop()`，避免在 listener 线程同步
   stop；
4. 停止后按 `app.video-status.consumer-recovery-delay-ms`（默认 10,000 ms）安排唯一一项恢复任务；
5. 恢复时先检查 `isRunning()`，未运行才 `start()`；启动仍失败则保持 gate，只重新安排下一项恢复任务，
   不会并发多个 stop/start；
6. `destroy()` 置 closed、关闭调度器并等待退出；停机期间恢复任务不会再启动已随 Spring 关闭的 listener。

**prefetch 在途消息怎么处理**：转发失败的消息保持 unacked；异步 stop 关闭 channel 后，broker 会重新
投递未确认消息。这样既不丢消息，也不会在 consumer 线程无限 sleep 或立即 requeue 热循环。尝试率由
单实例、单线程、单恢复任务和固定恢复延迟共同限制。

> 说明：JDK 的 `ScheduledThreadPoolExecutor`/延时队列没有容量参数，本类不把它冒充成通用有界队列；
> 这里的“有界”由固定恢复任务形态 + 原子 gate 保证——业务上最多只有一项恢复任务在调度，
> 不随消息数量增长。

## 5. ACK 与业务异常边界

| 场景 | 行为 |
|---|---|
| 业务处理成功 + ACK 成功 | 正常完成 |
| 业务处理成功 + ACK 抛错 | 只把 `IOException` 交给监听容器；**不转发 retry/DLQ、不 NACK** |
| retry/DLQ 发布成功 + ACK 抛错 | ACK 异常独立传播；**不再发布第二次、不转 DLQ** |
| 业务异常 + retry/DLQ 发布成功 + ACK 成功 | 原消息确认，转发消息承担后续重试 |
| 业务异常 + retry/DLQ 发布失败 | 不 ACK、不 NACK，请求异步暂停，原消息由 broker 重投 |
| 非法 payload | 走死信转发；ACL/解析错误不会无限重试 |

关键点：**成功消息的 ACK 失败绝不触发 DLQ**。ACK 失败不是业务失败，也不是转发失败；连接恢复后
重复投递由 eventId 幂等吸收。

## 6. 配置与生命周期

新增配置：

```yaml
app:
  video-status:
    consumer-recovery-delay-ms: 10000
```

启动期校验沿用 `VideoStatusProperties#validate()`：必须为 1..3,600,000 毫秒。未改动
`consumer-max-retries`、原 10 秒队列参数或 T07 的发布/租约参数。

恢复控制器是 Spring `@Component`，实现 `DisposableBean`；调度线程是 daemon 且固定命名为
`video-status-consumer-recovery`，便于线程 dump 定位。

## 7. 单元测试（不连接基础设施）

本部分新增/覆盖的纯单元测试：

- `VideoStatusRetryTopologyTests`（3）：原 10 秒队列名/TTL/DLX 参数不变；30/120 秒队列各自固定 TTL；
  三条 binding 的 exchange/routing key 正确。
- `VideoStatusRetryTierRoutingTests`（3）：第 1/2/3+ 次重试分别路由到 10/30/120 秒队列；转发保留
  eventId、body 和 attempt header；恢复重放使用同一选路。
- `VideoStatusEventConsumerAckBoundaryTests`（5）：成功 ACK 抛错不转 retry/DLQ、不 NACK；retry/DLQ
  发布成功但 ACK 抛错不二次转发；转发失败不 ACK/NACK 且请求异步暂停；非法 payload 只进 DLQ 一次。
- `VideoStatusConsumerRecoveryControllerTests`（6）：gate 合并重复请求；stop 在调度任务中执行；
  有界延迟后 start；stop 失败仍能恢复或释放 gate；start 失败保持 gate 且最多一项下一恢复任务；
  停机后拒绝新请求。

本地定向验证（仅上述测试 + 既有 `VideoStatusTelemetryInstrumentationTests`）：

```text
Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
```

未运行、也未宣称通过：真实 RabbitMQ 队列声明/TTL 到期重投、真实 Redis 故障 120 秒恢复、
真实容器 stop/start、`videoStatusIT=true` 集成套件、完整 `mvnw test`（其中
`BackendApplicationTests#contextLoads` 需要本机基础设施）。

## 8. 未完成 / 风险（如实说明）

1. **未做真实集成验证**：30/120 秒队列的 broker 参数兼容、TTL 到期回主队列、容器 stop 后 prefetch
   未确认消息重投，都需要隔离 RabbitMQ 环境验证。
2. **恢复策略只覆盖转发失败**：ACK 失败交给 Spring 容器/连接恢复；未添加专门指标或人工恢复入口。
3. **恢复任务没有最大次数上限**：启动持续失败时会按固定延迟继续尝试，尝试率有界但不会自动停止，
   需要 T05/T16 的观测与告警确认。
4. **三级映射目前是固定规则**：未配置化。若后续调整 `consumer-max-retries` 或恢复预算，需要重新
   评估 10/30/120 秒的总等待时间。
5. **T10 未实现**：DLQ 内容、失败原因与 Outbox FAILED/status3 的对账处置不在本部分。
6. **未改公共计划/AGENTS/API**：本文只是 T08 教程候选；是否合入及是否勾选计划由父代理决定。

## 9. 回滚

- 删除 30/120 秒队列 Bean/binding 后，forwarder 会回到只使用原 10 秒 routing key；原队列参数未变。
- 回滚恢复控制器和消费者 ACK 改动即可恢复旧行为；未改数据库 schema、Redis key 协议或 eventId/指纹。
- 队列中已有的 30/120 秒消息需要先排空或人工迁回 10 秒队列；不要直接删除 DLQ 作为通用回滚手段。
