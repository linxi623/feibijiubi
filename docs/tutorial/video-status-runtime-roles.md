# 视频统计运行角色与开关契约（T04）

> 适用范围：`app.video-status.*` 配置组、`VideoStatusOutboxRelay`、`VideoStatusBatchFlushScheduler`、
> `VideoStatusDirtyRecoveryScheduler`、`VideoStatusEventConsumer` 的部署与维护。
>
> 本文只描述**已经实现**的能力。指标/告警（T05）与调度隔离/单轮预算（T06）已验收；
> Outbox 租约细化（T07）、受控强制重建与失败事件处置（T10/T12）尚未实现，文中明确标注。
>
> 相关文档：[完整链路设计](video-status-full-flow-design.md)、
> [批量聚合实现](video-status-batch-aggregation-implementation.md)、
> [无锁快路径协议](video-status-lock-free-fast-path.md)、
> [改进基线报告](video-status-improvement-baseline.md)、[执行计划](video-status-improvement-plan.md)。

## 1. 四个开关 = 四个可独立部署的角色

统计链路被拆成四个角色，每个角色由一个开关控制，**角色之间不互相替代**：

| 角色 | 开关 | 代码位置 | 打开时做什么 | 关闭后做什么（已实现） |
|---|---|---|---|---|
| ① 业务发布 | `app.video-status.async-enabled` | `UserVideoServiceImpl#...`（`isAsyncEnabled()`） | 业务事务内写 `video_status_outbox` 事件，不改 Redis/DB 统计 | 业务事务内**同步**更新统计（旧基线路径），不产生 Outbox 事件 |
| ② Outbox 投递 | `app.video-status.outbox-relay-enabled` | `VideoStatusOutboxRelay#relay()` | 恢复超时租约 → 领取 `status=0` 事件 → 发布到 RabbitMQ → 回写 `status=2` | **不领取、不恢复租约、不发布**；事件留在 Outbox 表（`status=0/1`） |
| ③ 消费 | `spring.rabbitmq.listener.simple.auto-startup` | `VideoStatusEventConsumer`（`@RabbitListener`） | 消费消息、幂等登记、更新 Redis、标记 `process_status=1`、SADD dirty | 不消费；消息留在队列（默认 `false`，不在 `app.video-status.*` 内） |
| ④ 落库/恢复 | `app.video-status.scheduling-enabled` | `VideoStatusBatchFlushScheduler`、`VideoStatusDirtyRecoveryScheduler` | 批量把已消费事件写入 `video_status` 并标 `process_status=2`；反哺丢失的 dirty vid | 不执行批量落库与 dirty 恢复；`process_status=1` 的增量停在 Redis 侧 |

`async-enabled` 是①②④ 的总前提（既有含义，本次未改）：

- `async-enabled=false`：业务不写 Outbox，所以 ② 即使为 `true` 也不会实际投递；④ 的两个调度器
  在 `!asyncEnabled` 时直接返回。③ 与 `async-enabled` 无关，只看 RabbitMQ 监听器开关。
- `async-enabled=true`：① 生效；②③④ 各自按自己的开关独立决定。

启动期会把实际角色矩阵打到 INFO 日志，并对可疑组合打 WARN：

```text
视频统计运行角色：publish=true, relay=true, consume=true, flushRecovery=true
视频统计配置提示：...
```

角色矩阵由 `VideoStatusRuntimeRoles#matrix()` 计算，单元测试固定（见 §6）。

## 2. 合法组合与“单机无人落库”诊断

**没有任何组合会在启动期被拒绝**（除参数非法，见 §5）。`async=true/scheduling=false` 是多角色部署和
维护窗口的合法组合，计划 §1 第 3 条明确不能一律禁止。是否有人落库取决于部署拓扑，代码只负责诊断。

| 场景 | async | relay | listener | scheduling | 结果与提示 |
|---|---|---|---|---|---|
| 单机全功能 | true | true | true | true | 四角色齐全；启动日志无 WARN |
| 单机停投维护 | true | **false** | true | true | 只停投递：不领取/不恢复/不发布，消费与落库继续。WARN：必须有其他实例投递或处于停投窗口 |
| 投递 + 落库分离 | true | true | true | false（落库机） | 落库机只跑 flush/recovery。WARN：本实例不落库 |
| 消费机（不落库） | true | false | true | **false** | 消费机只消费。WARN 同时出现“不投递”和“不落库” |
| 单机“无人落库”误配 | true | true | true | **false** | WARN：`scheduling-enabled=false` 且无其他落库实例时，Redis 已应用增量、`process_status=1` 不再前进，`video_status` 表停止更新 |
| 同步基线（回滚态） | **false** | 任意 | false | 任意 | 业务直接同步写统计；WARN 说明 relay/scheduling 不产生效果 |

“单机无人落库”的**诊断方法**（T05 已提供管理员只读指标；下列 SQL 仍可用于根因定位）：

```sql
-- ① Outbox 是否有待投递积压（relay 关闭时会持续增长）
SELECT status, COUNT(*) FROM video_status_outbox GROUP BY status;   -- 0=待投,1=发送中,2=已发送,3=失败

-- ② 已消费但未落库的事件（scheduling 关闭时会持续增长）
SELECT process_status, COUNT(*) FROM video_status_consumed_event GROUP BY process_status; -- 0=已登记,1=Redis已应用,2=已落库,3=待修复

-- ③ 最老未落库事件年龄（持续变大 = 无人落库）
SELECT MIN(created_at) FROM video_status_consumed_event WHERE process_status IN (0, 1);
```

配合启动日志里的角色矩阵：`flushRecovery=false` + ② 中 `process_status=1` 计数只增不减，即可判定
“当前没有实例承担落库角色”。

## 3. 停投（`outbox-relay-enabled=false`）操作步骤

1. 只对承担投递角色的实例设置 `outbox-relay-enabled=false` 并重启（配置不是热更新）。
2. 观察日志不再出现 `视频统计 Outbox 发布失败` / `最终失败`；`video_status_outbox` 中
   `status=0/1` 不再减少（停投生效）。
3. **允许**此时继续消费与落库：`status=2` 的历史事件已经投递，`video_status_consumed_event` 的
   `process_status=0/1 → 2` 迁移照常进行，dirty 恢复也照常。
4. 恢复投递：改回 `true` 并重启。`status=1` 的残留行会由 `recoverExpiredSending`（租约超时）重新
   领取；`status=0` 直接重新领取。重复投递由消费端 eventId 幂等吸收。
5. 注意：停投期间新业务事件只会堆积在 Outbox 表，不会进队列；**停投不是停业务**，恢复后需要观察
   积压是否在恢复预算内收敛（预算见计划 §3.3）。

## 4. `async-enabled` 切换流程（必须排空 + 重建）

切换 `async-enabled` 会改变“谁写统计”的语义，**禁止在有未处理事件时直接切到同步基线**，
否则同步路径基于旧 DB 值累加、异步路径的增量还没落库，会造成重复计数或旧缓存覆盖新基线。

### 4.1 异步 → 同步（true → false）

1. **停投**：所有实例 `outbox-relay-enabled=false`，停止新增投递。
2. **排空 Outbox**：等待 `video_status_outbox` 中 `status IN (0,1)` 计数为 0；
   `status=3`（FAILED）不允许忽略，按 T07/T10 的处置说明单独处理，不能靠“切同步”掩盖。
3. **排空消费**：等待 `video_status_consumed_event` 中 `process_status IN (0,1)` 计数为 0（全部
   `=2` FLUSHED）。`process_status=3`（待修复）必须先处置。
4. **停消费**：`spring.rabbitmq.listener.simple.auto-startup=false`，确认主队列 ready/unacked 为 0。
5. **核对基线**：`video_status` 与 Redis current 一致（重建后一致的判定见
   [无锁快路径协议 §恢复](video-status-lock-free-fast-path.md)），dirty Set 为空。
6. 切换 `async-enabled=false` 并重启。此时业务事务同步写 `video_status`。
7. **重建 Redis**：同步路径只写 DB，Redis 派生值会落后。重建入口当前**只有**消费慢路径的
   `VideoStatusRebuildService#ensureInitialized`（T12 才提供受控强制重建），因此切换后必须在维护
   窗口内让受影响 vid 走一次重建，**不允许手工 `HSET`/`DEL` 统计 key 或改门闩**。
8. 验证：抽样对比 `video_status` 与 Redis current，并确认业务操作只产生 DB 写入、不再新增 Outbox 行。

### 4.2 同步 → 异步（false → true）

1. 维护窗口内停业务写入入口（或接受切换瞬间的窗口，由运维确认）。
2. 确认 `video_status` 是权威基线，`video_status_consumed_event` 无 `process_status=0/1` 残留。
3. 先 `async-enabled=true`、`outbox-relay-enabled=true`、`scheduling-enabled=true`、监听器仍 `false`
   启动：开始产生 Outbox 事件但先不投递，便于确认写入正常。
4. 打开监听器自动启动，再观察 Outbox `status=0/1` 收敛、`process_status` 推进到 2。
5. **重建 Redis**：以 DB 基线 + 已确认事件为起点重建，避免旧缓存值覆盖新基线；重建方式同上，
   不手工改 key。
6. 验证：一次点赞/播放只产生一条 Outbox 事件、一条消费事件、一次落库；DB 与 Redis 最终一致。

> 当前没有“切换脚本/管理接口”，以上是人工操作步骤。T05 已提供 `/api/admin/video-status/metrics`
> 只读指标；T10/T12 提供查询与受控重建后，应把 4.1/4.2 固化成受审计的操作入口，而不是长期依赖
> 人工 SQL 观察。

### 4.3 维护顺序的受控演练（测试证据，不等于热切换能力）

`VideoStatusIsolatedIntegrationTests#controlledSyncAsyncSyncMaintenanceDrillConvergesWithoutLossOrStaleCache`
在隔离环境（MySQL 13307 / Redis 16379 / RabbitMQ 15673）用**真实开关**和**真实业务入口**把 4.1/4.2
的顺序跑一遍（每次运行创建独立用户/视频，不清理部署数据）：

1. `async-enabled=true` 下调用真实 `UserVideoService#increasePlayCount`：只新增 Outbox(`status=0`)，
   DB 基线与 Redis current 都不变；`relay()` → 真实消费者 → flush 后收敛为 1/1。
2. 停输入并置 `outbox-relay-enabled=false` 后调用 `relay()`：Outbox 仍为 `status=0`、消费表无新行、
   主/重试/死信队列 ready 与 unacked 均为 0；恢复开关后同一事件被投递并收敛（证明停投只停投递）。
3. 切 sync 前显式排空：本 vid 的 Outbox `0/1`=0、`process_status` `0/1`=0、队列为空、dirty 不含该 vid。
4. `async-enabled=false` 后调用同一 `UserVideoService` 入口：直接写 `video_status` 基线，
   不新增 Outbox/消费行；**Redis current 保持在旧值**——同步路径不写缓存，这是既有语义而不是 bug。
5. 在持有 vid 锁、并再次确认无 Outbox `0/1` 与 `process_status` `0/1` 后，只删除本测试 vid 的
   current Hash，再调用 `ensureInitialized` 从 DB 基线重建；断言 current 与 feed 热点分都等于基线、
   门闩已释放、dirty 不含该 vid。
6. 恢复 `async-enabled=true` 后再发一条真实事件：Outbox/消费/FLUSHED 计数精确 +1，current 从基线
   继续累加、不回退到旧缓存；再重复一轮 sync → 重建 → async。终态 DB=Redis=6（4 次异步事件 +
   2 次同步写入），`eventId` 无重复、无残留 dirty。

**这些结论的边界（不要外推）：**

- 测试修改的是应用注入的同一个 `VideoStatusProperties` 实例，所以能在同一 JVM 内直接验证
  “开关 → 真实入口行为”的映射；生产配置**不是**热更新，改开关仍需重启，不存在运行期安全翻转。
- 第 5 步“删 current 再重建”是**测试内的维护动作**：生产没有对外暴露的“删除 current / 强制重建”
  入口（`ensureInitialized` 只在 current 缺失的无锁消费慢路径里被调用），也没有切换脚本/管理接口。
  本用例只证明“按此顺序操作时链路可以收敛”，**不证明**在生产上直接翻转开关是安全的。
- 演练覆盖单实例、单个 vid 的角色组合；多实例下“其他实例仍在投递/消费/落库”不被该用例覆盖，
  仍需按 §3/§4 逐实例停角色并观察全局 SQL 计数。
- 演练前后都要求队列 ready/unacked 为 0；有 Outbox `status=3` 或 `process_status=3` 残留时必须先按
  T07/T10 处置，不能靠切 sync 掩盖（T07/T10 尚未实现）。
- 演练没有验证“新旧消费者混跑”：旧 Lua 不识别重建门闩，切换期间必须保证所有实例运行同一版本。

## 5. 启动期参数校验（`VideoStatusProperties#validate`）

校验在 `@PostConstruct` 执行，绑定完成即失败，**不需要 Spring 代理**，错误信息包含参数名与实际值。

| 参数 | 合法范围 | 说明 |
|---|---|---|
| `redis-event-ttl-days` | 1 – 3650 | Redis 事件幂等 Key 的 TTL |
| `consumer-recovery-auto-replay-max-age-seconds` | > 0 且 **严格小于** TTL 秒数 | 重放安全窗口；超过 TTL 会丢失去重证据 |
| `outbox-batch-size` | 1 – 10000 | 单次领取条数 |
| `flush-dirty-batch-size` | 1 – 10000 | 单次 SPOP 的 dirty vid 数 |
| `flush-event-batch-size` | 1 – 10000 | 单次落库事件数 |
| `outbox-fixed-delay-ms` | 1 – 3600000 | 投递调度间隔 |
| `flush-fixed-delay-ms` | 1 – 3600000 | 落库调度间隔 |
| `flush-recovery-fixed-delay-ms` | 1 – 3600000 | dirty 恢复调度间隔 |
| `outbox-lease-seconds` | 1 – 3600 | 投递租约 |
| `publish-confirm-timeout-seconds` | 1 – 3600 | 发布确认超时 |
| `consumer-max-retries` | 1 – 100 | 消费重试上限（0 视为误配：首败即进 DLQ） |

上界只是“拦截多写/少写一个 0”的粗粒度保护，不是性能上限。

**为什么不用 `@Validated` + 单字段注解**：本仓库有 `spring-boot-starter-validation`，把 `@Validated`
加在 `@ConfigurationProperties` 类上确实会被 `ConfigurationPropertiesBindingPostProcessor` 触发 JSR-303
校验，因此技术上可用；但（a）注解无法表达“重放窗口必须小于 TTL”这类跨字段规则；（b）校验收
依赖 Spring 代理，默认单测必须启动上下文才能验证，而本项目默认单测不启动上下文、不连外部资源
（绑定期失败的覆盖交给 §6 的轻量 `ApplicationContextRunner` 用例，不加载主应用与自动配置）；
（c）两类校验并存会让错误顺序和错误文案不一致。因此统一保留手写 `validate()`，由 `@PostConstruct` 调用。

## 6. 测试证据（T04）

`VideoStatusRuntimeRoleContractTests`（9 个用例）+ `VideoStatusOutboxRelayTests`（新增 2 个，共 6 个）：

| 用例 | 固定什么 |
|---|---|
| `defaultValuesPassStartupValidation` | 默认值必须能通过校验（否则所有环境启动失败） |
| `zeroOrNegativeBatchSizeIsRejected` | 批量 0 / 负数 / 超上界被拒绝，错误信息带参数名 |
| `nonPositiveOrAbsurdDurationsAreRejected` | 调度间隔、确认超时、租约必须为正且不超上界 |
| `retryCountMustBePositiveAndBounded` | 重试次数 0 与超上界被拒绝 |
| `replaySafetyWindowMustStayInsideRedisIdempotencyTtl` | 窗口 0、等于 TTL、大于 TTL 被拒绝；小于 TTL 通过 |
| `relayFlagIsIndependentOfParameterValidation` | 停投不影响参数校验 |
| `allFourRolesEnabledHasNoDiagnostics` | 四角色齐全 = 无 WARN |
| `asyncDisabledMeansPublishAndFlushOffButListenerIndependent` | async=false 时 publish/flush 关闭，消费独立 |
| `relayDisabledKeepsFlushAndConsumeRolesAndIsNotRejected` | 停投不影响消费与落库角色，不被拒绝 |
| `asyncWithoutSchedulingIsLegalButDiagnosedAsNobodyFlushes` | 合法组合 + “无人落库”“监听器未自动启动”诊断 |
| `relayDisabledDoesNotStopBatchFlushScheduler` / `...DirtyRecoveryScheduler` | 停投时落库与恢复调度照常执行 |
| `schedulingDisabledStopsFlushSchedulerAsBefore` | scheduling 既有含义未被改动 |
| `relayFlagFalseStopsClaimRecoverAndPublishButKeepsAsyncOn` | 停投时不 claim / 不 recover / 不 publish |
| `relayFlagDefaultsToTrueSoExistingDeploymentsKeepPublishing` | 默认值 true，历史部署不会被默默停投 |

测试不启动 Spring 上下文、不连接 MySQL/Redis/RabbitMQ，全部为内存对象 + Mockito。

### 6.1 上下文级与维护演练证据

| 测试 | 固定什么 |
|---|---|
| `VideoStatusRuntimePropertiesContextTests`（6 个用例，`ApplicationContextRunner`） | 只注册 `@EnableConfigurationProperties(VideoStatusProperties)` + `VideoStatusRuntimeRoles`，不加载主应用/自动配置：默认值绑定（relay 默认 true）、`outbox-relay-enabled=false` 真实绑定且只关闭投递角色、四角色显式全开的合法矩阵、`async=true/scheduling=false/listener=false` 可启动并被诊断、非法批量与越界重放窗口在 `@PostConstruct` 让上下文启动失败；同时断言上下文中不存在 DataSource/JdbcTemplate/RedisConnectionFactory/RabbitMQ ConnectionFactory/监听容器工厂，即不连 DB/Redis/MQ、不启动消费者 |
| `VideoStatusIsolatedIntegrationTests#controlledSyncAsyncSyncMaintenanceDrillConvergesWithoutLossOrStaleCache` | 隔离环境维护演练（§4.3）：真实 `UserVideoService` 入口 + 真实开关 + 真实 relay/消费者/flush，验证排空、停投、同步写基线、持锁重建、恢复异步后的无遗漏/无重复/无旧缓存覆盖 |

这两类用例都不依赖 `application.yml`：前者完全不建连接，后者在连接前先校验隔离端点（见类内
`IsolationGuard`），并只清理专用测试队列/只操作自己插入的行。

## 7. 未做与边界

- **指标/告警（T05 已实现）**：`GET /api/admin/video-status/metrics` 返回本实例遥测 + 共享 DB
  积压采样，`GET /api/admin/video-status/gate?vid=...` 只读门闩停留时间；告警只写应用日志和 HTTP
  快照，**没有外部投递**。本文的 SQL 诊断仍可用于根因定位。
- **没有强制重建入口**：只有 `ensureInitialized` 慢路径，`process_status=3` / `outbox status=3` 的处置
  入口在 T07/T10。§4.3 的“删 current”只是测试内的维护动作，不是产品能力。
- **没有维护编排**：仓库没有切换脚本、管理接口或受控重建命令，§4 的步骤仍需人工 SQL 观察 + 重启；
  测试证明的是“按该顺序执行时可以收敛”，不是“可以热切换”。
- **没有租约预算细化**：整批发布耗时可能超过 `outbox-lease-seconds`，多实例下会触发租约接管（T07）。
- **调度资源隔离（T06 已实现）**：投递、落库、恢复已拆为三条独立单线程调度器，并有
  `round-budget-ms` 与 100ms 的调度落库锁等待；预算不能强杀已开始的调用，业务 SQL 超时仍未统一，
  且不等于生产容量验收。
- **不改业务吞吐**：本次只加开关契约与校验，不调整批量/线程/索引，也不改 SQL、Redis 脚本与 flush 逻辑。
