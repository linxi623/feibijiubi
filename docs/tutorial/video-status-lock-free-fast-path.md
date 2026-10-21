# 视频统计：无锁快路径、重建门闩与隔离测试

## 1. 为什么不是简单删锁

Redis 的 Lua 已经保证单个增量更新、热度、dirty、processed 的原子执行。正常消费不需要再围绕 Lua 获取 Redisson 锁。但重建跨越 MySQL 快照与 Redis processed 查询，不能靠单个 Lua 保证全过程一致。

本次不修改 HTTP API、数据库结构、消费状态机，也不引入分片队列。

## 2. 当前协议

### 正常路径

1. `register` 在独立事务登记状态 0，唯一 eventId 与语义摘要检查保持不变。
2. 增量 Lua 先检查该 vid 的重建门闩；不存在门闩且 current 完整时直接执行更新。
3. Lua 内检查 processed，重复事件不再累计。
4. 调用独立事务 `markRedisApplied`，状态变为 1，然后 ACK。

正常路径不调用 `VideoStatusVidMutex`。这里的“无锁”指不获取应用层分布式互斥锁，不意味着 Redis、MySQL 内部没有锁。

### 缺失或被门闩阻止的慢路径

1. Lua 返回 `NEEDS_REBUILD`。
2. 获取该 vid 的 Redisson 锁，先重新执行增量 Lua。其他线程可能已经完成重建，此时直接返回。
3. 仍无法执行时，调用 `ensureInitialized`；它使用同一把可重入锁。
4. 写入 `videoStatusRebuildGate(vid)`，值为随机 UUID，**不设置 TTL**。
5. 关门后再次检查 current；已经存在时无需加载快照，开门返回。
6. current 缺失时，读取 MySQL 可重复读快照：基线 + 状态 0/1 候选。
7. 汇总状态 1，以及 processed 仍存在的状态 0。
8. 初始化 Lua 校验门闩令牌；令牌不匹配返回 `STALE_REBUILD`，禁止旧快照发布。current 已存在时禁止覆盖。
9. finally 中通过 compare-and-delete Lua 释放本次令牌。
10. 仍持有外层慢路径锁时重新执行增量 Lua。

## 3. 并发为什么安全

- **关门与增量原子互斥**：门闩 SET 和增量 Lua 在 Redis 中有先后顺序。先执行的增量已经写入 processed；后执行的增量看到门闩后不写入。
- **关门后再取快照**：已应用事件已登记为状态 0 或 1。状态 0 → 1 可以并发，但两种状态都参与重建，状态 0 依靠 processed 确认。
- **快照之后才登记的事件**：不能穿过门闩；初始化完成后，它在新 current 上正常应用。
- **刷库与重建互斥**：刷库继续持有 vid 锁，因此重建期间基线不会因这条刷库链路变化。
- **正常消费不等待刷库锁**：增量只更新 Redis；状态 1 事件后续批量落库，不依赖刷库时同步修改 current。
- **旧重建不能开新门**：初始化和释放都验证 UUID。Redis 重启导致门闩丢失，或新持锁者接管后，旧令牌不能发布/清理。

前提是所有修改基线、重建 current 的入口遵守同一协调协议，Redisson 锁仍有有效所有权。令牌不是 MySQL fencing token，不声称解决网络分区导致锁租约失效后的任意双主写入。

## 4. 为什么门闩没有 TTL

固定 TTL 可能在慢重建期间先到期，让正常消费者穿过门闩。这里用“下一位 vid 锁持有者接管”代替固定 TTL：

- 进程崩溃：Redisson watchdog 停止续租，锁最终释放；后续消费看到门闩，进入慢路径后覆盖令牌、检查 current 并恢复。
- 释放门闩失败：记录日志，保留门闩；下一条消费接管。没有后续消息时门闩可暂留，不影响查询。
- 快照异常：finally 尝试释放；失败也不会绕过门闩继续写入。

Redis 应使用 **noeviction**，不要把门闩作为普通可淘汰缓存，也不要给门闩手工加 TTL。当前方案面向单实例 Redis；现有 dirty/hot 全局 key 本身也尚未适配 Redis Cluster 跨槽 Lua。

## 5. Dirty Set 与最终调度

刷库调度先 SPOP vid。查到仍有状态 1 时重新 SADD；查到没有时**不再 SREM**。因为无锁消费者可能恰好在 SELECT 返回后执行了 SADD，额外 SREM 会删掉新消息的唤醒。

允许一次空刷库比误删唤醒更安全。Lua 的 SADD 与 MySQL 状态 1 标记仍不是同一个事务：若 SPOP 发生在标记之前，或进程此时崩溃，`VideoStatusDirtyRecoveryScheduler` 仍是最终兜底，必须启用。

`FOR UPDATE SKIP LOCKED` 也可能暂时跳过被重复登记事务锁住的事件，因此一次刷库不等于全部落库；测试应等待最终收敛，而不是强求一次调用完成。

## 6. 故障保证的边界

本次解决的是无锁增量与重建/刷库之间的并发协调，不是 Redis 任意丢数据后的 exactly-once：

- 仅 current 丢失、processed 保留：可重建状态 0/1 的已确认事件。
- 状态 1 即使 processed 丢失，重建仍会纳入。
- **状态 0 的 processed 与 current 同时丢失**：无法从现有数据确定 Redis 曾否应用，仍存在原设计的确认窗口。
- processed 单独丢失/提前过期、超龄状态 0 的自动重放：仍需遵守安全年龄和人工修复策略。
- 状态 3 的人工修复与删除视频不是本轮重构目标，需要单独的操作协调和审计。

不要在运行中的正式 Redis 上用 FLUSHALL 验证本方案。

## 7. 独立 Docker 测试环境

```bash
docker compose -f compose.test.yaml up -d --wait
./mvnw -DvideoStatusIT=true -Dtest=VideoStatusIsolatedIntegrationTests test
# 可选：同一视频、5 并发，比较额外外层锁和正常快路径
./mvnw -DvideoStatusIT=true -DvideoStatusBenchmark=true -Dtest=VideoStatusIsolatedIntegrationTests test
# 不连接基础设施的单元测试
./mvnw '-Dtest=*Tests,!BackendApplicationTests,!VideoStatusIsolatedIntegrationTests' test
```

- 项目名：`feibijiubi-test`，不能与 `compose.yaml` 合并执行。
- MySQL：`127.0.0.1:13307`，Redis：`16379`，RabbitMQ AMQP：`15673`，管理端口：`15674`。
- MySQL/RabbitMQ 使用 `feibijiubi-test_` 前缀的独立命名卷；Redis 不挂生产卷。
- 测试配置完全替代 `application.yml`，不加载 COS/JWT 等无关组件，不启用自动定时任务；测试主动调用 relay/flush，真实监听容器运行 5 个消费者。
- 显式 `-DvideoStatusIT=true` 才运行集成测试。启动 guard 在创建连接之前检查端点，发现环境变量覆盖为其他目标立即失败。
- 测试只新增独立用户/视频，不清空数据库；为可复跑，清理专用测试 RabbitMQ 的主/重试/死信队列。**不可将测试端口映射到正式实例。**
- `src/test/docker/init-schema.sh` 使用 UTF-8 客户端导入完整建库脚本，避免默认客户端字符集导致中文种子数据超长。
- 首次初始化脚本只在空 MySQL 卷运行；失败后不要把“容器健康”当作“表结构完整”。

停止测试环境但保留测试数据：

```bash
docker compose -f compose.test.yaml stop
```

若确需从零初始化，先核实项目名/卷名，**仅对 `compose.test.yaml` 的测试项目**执行 `down -v`，再 `up -d --wait`。不要在原有部署上执行删卷命令。

## 8. 测试与性能解读

真实集成测试覆盖：热路径不加锁、刷库持锁时仍可更新、并发冷启动和重复消息、Redis 应用后未标状态、已落库/待落库混合重建、残留门闩接管、快照期间新登记/刷库阻塞、过期令牌、快照失败恢复、并发刷库、负增量、真实 outbox → RabbitMQ → 消费 → 刷库、重试队列重新投递和死信。

MQ 验收同时检查 MySQL 基线、Redis current、事件最终状态、outbox SENT，以及 RabbitMQ 管理 API 的 ready/unacknowledged；不把“主队列空”当作唯一证据。

性能方法是当前实现外面模拟旧的逐条 vid 锁，与当前无锁快路径比较；每组 500 条、5 并发，并包含登记与标记事务。它不是历史版本端到端压测，存在 JVM 预热、Mockito spy、组别顺序及本机 Docker 资源偏差，不应据此承诺生产提升比例。正式容量评估还需多轮、冷热混合、多视频、异常恢复与 MQ 积压指标。

### 本次本机验证记录

- 编译通过；25 个独立单元测试通过（未运行依赖本地完整配置的 `BackendApplicationTests.contextLoads`）。最终合并复测为 39 项、0 失败、0 错误、1 跳过（性能诊断默认跳过），即 38 项实际执行通过。
- 显式启用诊断对比时，14 个隔离集成测试通过；未启用该参数时其中 1 个性能测试跳过。
- 额外以全新临时 MySQL 卷验证首次初始化：10 张表、13307 测试服务之外的临时容器中导入 112 条分类种子；验证后仅删除该临时项目。
- 一轮单热门视频结果：外加逐条锁 175.4 条/秒、P95 39.37ms、P99 46.33ms；正常快路径 237.7 条/秒、P95 27.78ms、P99 35.86ms。仅供诊断，不是性能 SLA。
- 没有执行生产环境压测、整机断电/网络分区、完整后端进程 kill/restart，或 Redis 全量丢失后的自动修复验收。进程崩溃相关测试使用“残留门闩”和“Redis 成功后未标状态”等可控状态模拟。

## 9. 上线与回滚

不要混跑旧版本：旧 Lua 不识别门闩，会穿过重建屏障。先停止全部旧消费者/刷库任务，再一起部署新版本。无需数据库迁移，不清理 current、processed、消费事件或 outbox。

回滚前停止全部新实例，确认没有进行中的重建，再处理已知残留门闩并统一回滚。不要在在线流量下随意删除门闩。
