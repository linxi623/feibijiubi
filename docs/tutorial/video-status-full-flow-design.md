# 视频统计系统全流程原理与设计说明

## 1. 文档目的

本文描述当前仓库实际使用的视频统计链路。重点回答四个问题：

1. 互动行为如何变成可靠事件。
2. Redis 如何提供低延迟实时统计。
3. 已消费事件如何批量写回 MySQL。
4. Redis 或 MySQL 任一环节出现异常时，系统如何避免重复和丢失。

当前流程不再维护 Redis 待刷增量副本，也不保存刷库批次或缓存代次。事件表是待落库数据的事实来源，Redis 只保存实时统计、热点分数、待处理视频标记和事件幂等标记。

## 2. 一句话理解

业务事务先写统计事件 Outbox，Outbox 可靠投递 RabbitMQ；消费者先把事件登记到 MySQL，再通过 Redis Lua 原子更新实时统计，同时把该视频加入 dirty Set；定时刷库任务按 vid 读取消费事件表中状态为 1 的一批事件，在一个 MySQL 事务中更新 `video_status` 并把事件改为状态 2。

核心链路：

```text
互动请求
  -> 业务事务 + video_status_outbox
  -> Outbox Relay
  -> RabbitMQ
  -> video_status_consumed_event(status=0)
  -> Redis current + dirty + processed + hot
  -> consumed_event(status=1)
  -> 批量读取 status=1
  -> video_status 累加事件 delta
  -> consumed_event(status=1 -> 2)
  -> MySQL 事务提交
```

## 3. 数据职责

### 3.1 MySQL

`video_status` 保存已经确认落库的统计基线。全部计数列都要求非负。

`video_status_consumed_event` 同时承担三个职责：

- 事件消费幂等日志。
- Redis 是否已经应用该事件的证据。
- 等待批量写回 `video_status` 的持久化队列。

状态含义：

| 状态 | 名称 | 含义 |
| --- | --- | --- |
| 0 | `RECEIVED` | 事件已登记，但尚未确认 Redis 已应用 |
| 1 | `REDIS_APPLIED_PENDING_FLUSH` | Redis 已应用，等待刷入 `video_status` |
| 2 | `FLUSHED` | 已刷入 `video_status` |
| 3 | `REPAIR_REQUIRED` | 数据异常，需要人工处理和审计 |

`video_status_outbox` 保存尚未可靠发布到 RabbitMQ 的业务事件。`video_status_consumption_repair_log` 保存状态 3 的人工修复审计记录。

### 3.2 Redis

| Key | 类型 | 用途 |
| --- | --- | --- |
| `video:status:v1:{vid}` | Hash | 当前实时统计快照 |
| `dirty:video:v1` | Set | 有事件等待批量写回 MySQL 的 vid |
| `video:status:process:v1:{eventId}` | String | 带 TTL 的事件幂等标记 |
| `feed:hot:videos:v1` | ZSet | 实时热点分数 |

`current Hash` 固定保存九项：

```text
vid
playTimes
likeTimes
unlikeTimes
commentTimes
coinTimes
shareTimes
collectTimes
danmuTimes
```

Redis 中不存在独立的待刷 delta Hash。这样做避免了“MySQL 已刷库但 Redis 减法失败”这一跨系统补偿窗口。

### 3.3 RabbitMQ

RabbitMQ 负责削峰和可靠异步投递。主队列消费失败时，消息通过重试队列延迟重投；超过次数后进入死信链路。

消费者使用手动 ACK。只有事件登记、Redis 更新和状态 1 事务都成功后才确认消息。重复投递由消费事件唯一键和 Redis processed Key 共同处理。

## 4. 一致性模型

系统采用最终一致性：

- `video_status` 是已落库基线。
- Redis current 是“MySQL 基线 + 已确认但尚未落库事件”的实时视图。
- 状态 1 的消费事件是跨 MySQL 与 Redis 的持久化对账依据。
- 状态 2 表示 MySQL 已经吸收该事件。

可观察到的短期差异包括：

- Redis 实时值领先 MySQL，直到刷库任务完成。
- 事件已经写入 Redis，但状态 1 尚未提交时，消息会重投。
- Redis current 丢失时，查询暂时降级到 MySQL，随后由重建逻辑恢复。

## 5. 阶段一：业务事件

业务代码调用：

```java
videoStatusService.createEvent(vid, type, delta);
```

`createEvent()` 使用 `Propagation.MANDATORY`，要求调用者已经处于业务事务中。这样业务状态和 Outbox 记录可以原子提交。

事件结构：

```java
record VideoStatusChangedEvent(
        String eventId,
        Integer vid,
        VideoStatusEventType type,
        Long delta,
        Double hotScoreDelta,
        LocalDateTime occurredAt,
        Integer schemaVersion,
        String traceId
) {}
```

约束：

- `eventId` 唯一，用于消费幂等。
- `vid > 0`。
- `delta != 0`。
- `hotScoreDelta` 必须等于事件类型对应的规则计算结果。
- 事件类型由服务端枚举控制，不能由客户端传入 Redis 字段名。

事件 delta 仍表示本次互动的增减量。例如取消点赞使用 `LIKE` 类型和负 delta，取消点踩使用 `UNLIKE` 类型和负 delta。

## 6. 阶段二：Transactional Outbox

RabbitMQ 不能加入 MySQL 本地事务，因此不能直接在业务事务里发送消息。

正确顺序是：

1. 业务数据写入。
2. Outbox 写入同一个 MySQL 事务。
3. 事务提交。
4. Relay 定时领取 Outbox。
5. 使用 publisher confirm 投递 RabbitMQ。
6. 成功后将 Outbox 标记为已发送。

如果应用在第 3 步和第 6 步之间崩溃，Outbox 仍是待发送状态，Relay 会重新投递。因此消费者必须支持至少一次投递。

Outbox 领取使用租约和条件更新，避免多个实例永久卡住同一条记录。发送超时或确认失败时记录错误并按退避时间重试。

## 7. 阶段三：消费者

消费者处理顺序：

```text
校验消息
  -> 计算语义 payload hash
  -> register(consumed_event)
  -> 必要时执行 Redis Lua
  -> markRedisApplied(status=1)
  -> ACK
```

### 7.1 先登记，再更新 Redis

MySQL 登记记录是 Redis 是否应用事件的耐久证据。若先更新 Redis，再登记 MySQL，应用可能在两步之间崩溃，导致 Redis 已增加但系统没有记录。

### 7.2 register

首次登记插入状态 0，返回 `NEEDS_REDIS_APPLY`。

如果 eventId 已存在，必须比较 vid、事件类型、delta 和语义 payload hash，不能直接视为重复成功。相同 eventId 对应不同内容属于严重数据错误。

已有事件的分支：

| MySQL 状态 | 处理 |
| --- | --- |
| 0 | 在安全年龄内允许继续应用 Redis |
| 1 | 视为 Redis 已应用，不再重复执行 Lua |
| 2 | 已经落库，直接确认消息 |
| 3 | 进入人工修复流程 |

状态 0 存在自动重放安全年龄，默认不超过 Redis processed Key 的 TTL。

### 7.3 Redis 应用结果

| 结果 | 含义 | 处理 |
| --- | --- | --- |
| `APPLIED` | Lua 已完成更新 | 标记状态 1 |
| `DUPLICATE` | processed Key 已存在 | 标记状态 1 |
| `NEEDS_REBUILD` | current 缺失或内容不完整 | 重建后重试 |
| `NEGATIVE_RESULT` | 统计结果会小于 0 | 数据异常 |
| `INVALID_FIELD` | 字段白名单校验失败 | 不可重试错误 |
| `INVALID_REDIS_TYPE` | Redis Key 类型错误 | 告警并停止自动覆盖 |

## 8. Redis Lua 写入协议

`video-status-increment.lua` 的 Key：

```text
KEYS[1] video:status:v1:{vid}
KEYS[2] dirty:video:v1
KEYS[3] video:status:process:v1:{eventId}
KEYS[4] feed:hot:videos:v1
```

参数：

```text
ARGV[1] 当前统计字段，例如 playTimes
ARGV[2] 事件 delta
ARGV[3] vid
ARGV[4] hotScoreDelta
ARGV[5] processed Key TTL 秒数
```

Lua 内部顺序：

1. 检查 current 是否存在。
2. 检查 current、dirty Set、hot ZSet 类型。
3. 校验 current 字段白名单。
4. 校验参数和 TTL。
5. 检查 processed Key 是否已存在。
6. 预判统计结果是否非负。
7. 更新 current。
8. `SADD dirty`。
9. 更新 hot ZSet。
10. 写入 processed Key。

所有读写在一个 Lua 脚本中执行，Redis 单线程模型保证脚本执行期间不会插入其他命令。

## 9. Redis 初始化与重建

### 9.1 触发条件

以下情况需要初始化或重建：

- Lua 发现 current 不存在。
- 运维主动删除 current 后由下一次事件触发重建。
- 历史数据迁移后需要建立实时视图。

### 9.2 重建数据来源

重建快照在 `REPEATABLE_READ` 只读事务中读取：

1. `video_status` 的 MySQL 基线。
2. 该 vid 下状态为 0 或 1 的消费事件。

状态 1 事件全部计入。

状态 0 事件只有满足以下条件才计入：

```text
processed:{eventId} 在 Redis 中存在
```

该标记证明事件已经实际应用到 Redis，只是状态 1 尚未提交。没有 processed Key 的状态 0 事件不能提前计入。

### 9.3 初始化公式

```text
Redis current = MySQL video_status baseline
              + 已确认的 status 0/1 事件 delta

Redis hotScore = MySQL 基线对应分数
               + 已确认事件的 hotScoreDelta
```

有状态 1 事件时，初始化脚本会把 vid 加入 dirty Set；没有待落库事件时不会加入。

### 9.4 Lua 初始化脚本

```text
KEYS[1] current Hash
KEYS[2] dirty Set
KEYS[3] hot ZSet

ARGV[1] vid
ARGV[2] hotScore
ARGV[3..10] 八项统计值
ARGV[11] 是否存在待落库事件
```

脚本先完成所有类型和数值校验，再写入 current，并根据 `hasPending` 决定是否加入 dirty Set。

## 10. 查询路径

读取顺序：

1. 优先读取 Redis current Hash。
2. Hash 不存在时读取 MySQL `video_status`。
3. Redis 命中时解析八个统计字段并返回。

查询线程不会主动执行重建，避免普通读请求触发跨系统写入。重建由消费者在遇到 `NEEDS_REBUILD` 时执行。

## 11. 批量刷库

### 11.1 为什么从消费事件表读取

状态 1 事件已经持久化到 MySQL，并且明确代表：

```text
Redis 已应用，但 video_status 尚未落库
```

因此刷库只需要：

1. 从 `video_status_consumed_event` 读取状态 1。
2. 按 vid 加分布式锁。
3. 锁定一批事件。
4. 聚合成固定八项 delta。
5. 更新 `video_status`。
6. 将同一批事件标记为状态 2。

不需要读取 Redis 待刷增量，也不需要在 MySQL 提交后回删 Redis 数据。

### 11.2 事务边界

`flushOneVideo()` 使用一个 MySQL 事务：

```text
SELECT ... FOR UPDATE
  -> 聚合事件
  -> UPDATE video_status
  -> UPDATE consumed_event SET status=2
  -> COMMIT
```

统计更新和状态 2 必须同事务提交。否则刷新统计后崩溃会重复累计，或者先标记状态 2 会永久丢失统计。

### 11.3 并发控制

- dirty Set 负责发现候选 vid。
- Redisson 按 vid 加锁，防止同一视频同时刷库。
- SQL 使用 `FOR UPDATE` 锁定本批状态 1 事件。
- 刷完后如果仍存在待处理事件，vid 会重新加入 dirty Set。

### 11.4 数据异常

如果事件类型无法识别，或 `video_status` 更新违反非负约束，本批事件转为状态 3，并写入修复审计。瞬时数据库或 Redis 故障则保留 dirty 标记并重试。

## 12. dirty 恢复

Redis dirty Set 是性能索引，不是唯一事实来源。如果 Redis 丢失或弹出后进程崩溃，`VideoStatusDirtyRecoveryScheduler` 会查询：

```sql
SELECT DISTINCT vid
FROM video_status_consumed_event
WHERE process_status = 1
```

再把 vid 补回 dirty Set。因此待刷事件不会因为 dirty Set 丢失而永久搁置。

## 13. 关键崩溃窗口

### 13.1 业务事务已提交，Outbox 未发送

Outbox 仍在 MySQL，Relay 继续投递。

### 13.2 MQ 已收到，Outbox 未标记发送成功

消息会重复投递，消费事件唯一键负责去重。

### 13.3 状态 0 已提交，Redis 尚未执行

消息重投后仍是状态 0，可继续执行 Redis Lua。

### 13.4 Redis 已执行，状态 1 尚未提交

processed Key 已存在。消息重投时可以返回 `DUPLICATE`，状态 0 事件也会在重建时通过 processed Key 被识别为已确认。

### 13.5 状态 1 已提交，ACK 尚未发送

消息重投时读到状态 1，不再执行 Redis，最终仍由刷库任务处理。

### 13.6 刷库事务执行中崩溃

统计更新和状态 2 同时回滚，状态 1 事件仍在下一次刷库批次中。

### 13.7 刷库已提交，dirty 尚未刷新

状态已经是 2，恢复扫描只查询状态 1。即使 dirty 中残留 vid，下一次刷库找不到待处理事件并安全结束。

## 14. 幂等层次

| 层次 | 幂等手段 | 防止的问题 |
| --- | --- | --- |
| Outbox | eventId 唯一键 | 重复创建事件 |
| 消费登记 | eventId 唯一键 + payload hash | 重复消息和事件内容冲突 |
| Redis | processed Key | 重复累加统计 |
| 刷库 | `SELECT FOR UPDATE` + 状态条件更新 | 重复写回 MySQL |
| 业务更新 | 非负条件 SQL | 异常负统计 |

这些机制分别保护不同边界，不能互相替代。

## 15. 配置

主要配置：

```yaml
app:
  video-status:
    async-enabled: true
    outbox-relay-enabled: true
    scheduling-enabled: true
    redis-event-ttl-days: 30
    outbox-batch-size: 100
    outbox-fixed-delay-ms: 1000
    outbox-lease-seconds: 60
    publish-confirm-timeout-seconds: 5
    consumer-max-retries: 5
    consumer-recovery-auto-replay-max-age-seconds: 604800
    flush-fixed-delay-ms: 500
    flush-dirty-batch-size: 100
    flush-event-batch-size: 1000
    flush-recovery-fixed-delay-ms: 5000
```

`scheduling-enabled` 是批量刷库和 dirty 恢复任务的总开关。

## 16. 上线迁移

从旧结构迁移时，需要短暂停机，禁止新旧版本混跑。

迁移步骤：

1. 停止旧后端。
2. 备份 MySQL。
3. 删除旧批次表。
4. 删除旧 Redis 待刷增量和旧清理标记 Key。
5. 从 current Hash 删除旧代次字段。
6. 保留 current、dirty、processed、hot 和消费事件表。
7. 部署新版本。

其中“旧 Redis 待刷增量”和“旧清理标记”只属于已删除功能，新代码不会读取。删除它们不会影响 current 统计值和 hot ZSet。

## 17. 当前边界

- Redis 使用 `noeviction` 更安全；processed Key 和 dirty Set 都是正确性相关数据。
- 系统以单应用实例为主要部署模型；扩展到多实例时仍需保留 vid 锁和事件行锁语义。
- 状态 3 的人工修复需要运营闭环。
- Redis Hash 字段级损坏不会自动覆盖，避免误删仍然有效的实时数据。

## 18. 代码导航

| 职责 | 主要类 |
| --- | --- |
| 事件创建 | `VideoStatusServiceImpl` |
| Outbox 投递 | `VideoStatusOutboxRelay` |
| MQ 消费 | `VideoStatusEventConsumer` |
| 消费状态 | `VideoStatusConsumptionServiceImpl` |
| Redis 原子更新 | `video-status-increment.lua` |
| Redis 重建 | `VideoStatusRebuildServiceImpl` |
| 批量刷库 | `VideoStatusBatchFlushServiceImpl` |
| 刷库调度 | `VideoStatusBatchFlushScheduler` |
| dirty 恢复 | `VideoStatusDirtyRecoveryScheduler` |

## 19. 核心检查清单

- [ ] 业务事务和 Outbox 是否同一事务提交。
- [ ] 消费者是否先登记 MySQL，再更新 Redis。
- [ ] Redis Lua 是否先校验再写入。
- [ ] processed Key TTL 是否大于状态 0 自动重放年龄。
- [ ] 重建是否以 MySQL 基线加已确认状态 0/1 事件为准。
- [ ] 刷库是否只读取状态 1，并在同一事务写统计和状态 2。
- [ ] dirty Set 丢失后是否能从状态 1 恢复。
- [ ] 所有统计更新是否满足非负约束。
