# 菲比啾比视频统计：Redis 实时计数与 RabbitMQ 异步落库设计

> 文档类型：技术设计与实施手册  
> 适用项目：菲比啾比后端  
> 技术栈：Java 17、Spring Boot 3.5、MyBatis、MySQL、Redis、RabbitMQ  
> 文档状态：目标方案，尚未在 Java 代码中落地  
> 最后更新：2026-07-18

---

## 1. 目标与结论

当前用户提供的 `VideoStatsRedisServiceImpl` 草稿只把 `video_status` 的完整状态放入 Redis Hash，并从 Hash 读取状态。它是查询缓存，不具备 Redis 原子计数和异步落库能力。当前工作区尚未找到该类文件，因此本文把它视为待接入或待恢复的目标代码，而不是已经上线的实现。

本文将统计链路升级为方案二：

```text
业务请求确认一次有效互动
        ↓
生成稳定 eventId，并可靠记录统计事件
        ↓
Redis Lua 原子更新实时统计与热门分数
        ↓
RabbitMQ 异步传递统计事件
        ↓
消费者幂等累加 MySQL video_status
        ↓
最终达到 Redis 与 MySQL 一致
```

最终职责如下：

| 数据 | 存储位置 | 职责 |
|---|---|---|
| 用户是否点赞、收藏、投币及用户余额 | MySQL 业务表 | 核心业务事实，事务内强一致 |
| 实时视频聚合统计 | Redis Hash | 客户端主要读取来源，使用 Lua 原子计数 |
| 热门视频顺序 | Redis ZSet | 根据互动增量实时调整热门分数 |
| 持久化视频聚合统计 | MySQL `video_status` | 最终持久化结果、Redis 重建基线 |
| 待投递统计事件 | MySQL `video_stats_outbox` | 解决 MySQL 事务与 RabbitMQ 之间的双写问题 |
| 已消费事件 | MySQL `video_stats_consumed_event` | 保证 RabbitMQ 重复投递时不会重复落库 |
| 统计消息 | RabbitMQ | 异步解耦、削峰、失败重试和死信 |

核心原则：

> RabbitMQ 默认提供“至少一次”投递，不假设消息只出现一次；Redis 和 MySQL 两端都必须通过同一个稳定 `eventId` 实现幂等。

---

## 2. 当前代码与目标方案的差距

当前项目已经具备：

- `VideoStatusMapper` 对 `video_status` 的同步增减；
- `RedisHashOperations` 的 Hash 读写；
- `RedisZSetOperations` 的热门排序操作；
- `RedisUtils#executeScript(...)` 的 Lua 执行能力；
- 用户提供的 `VideoStatsRedisServiceImpl#getOrLoad(...)` 草稿具备 Redis 未命中回源思路，但该类当前不在工作区；
- 点赞、点踩、收藏、投币等 MySQL 事务；
- Redis 异常统一包装。

当前缺少：

- `RedisHashOperations.increment(...)` 或统计专用 Lua；
- RabbitMQ 依赖和连接配置；
- 统计事件模型；
- Outbox 表和发布任务；
- Publisher Confirm 与 Return 处理；
- RabbitMQ 消费者手动 ACK；
- MySQL 消费幂等表；
- 重试队列和死信队列；
- Redis 计数幂等标记；
- 对账和修复任务。

目标方案上线后，`VideoStatusMapper#increasePlayTimes` 等方法不再由请求线程直接调用，而由 MQ 消费者统一调用增量落库方法。

---

## 3. 为什么不能只写“Redis 自增后发送 MQ”

下面这段简单流程不可靠：

```text
HINCRBY 成功
    ↓
发送 RabbitMQ 失败或进程崩溃
    ↓
Redis 已增加，但 MySQL 永远收不到该增量
```

反过来也有问题：

```text
先发送 MQ 成功
    ↓
Redis 更新失败
    ↓
MySQL 最终会增加，但实时 Redis 暂时落后
```

第二种情况可以由消费者修复，第一种情况却可能永久丢失持久化事件。因此可靠链路必须满足：

1. 统计事件先拥有可靠来源；
2. RabbitMQ 可以重复投递，但不能静默丢失；
3. Redis 重复处理同一事件时不能重复计数；
4. MySQL 重复处理同一事件时不能重复计数；
5. 只有 MySQL 事务提交成功后才能 ACK。

本文采用：

```text
MySQL Outbox + RabbitMQ Publisher Confirm + 手动 ACK + 双端 eventId 幂等
```

---

## 4. 总体架构

### 4.1 写入主链路

```text
客户端互动请求
    ↓
业务 Service 校验请求是否产生真实状态变化
    ↓
MySQL 本地事务
    ├── 更新 user_video、用户余额等业务事实
    └── INSERT video_stats_outbox（稳定 eventId + aggregateSequence）
    ↓ 提交成功
事务提交后唤醒 Outbox Relay（只做调度提示，不直接更新 Redis）
    ↓
Outbox Relay 按 id/sequence 发布 RabbitMQ
    ↓
RabbitMQ durable exchange / queue
    ↓
VideoStatsEventConsumer（第一版 concurrency=1；后续按 vid 分区）
    ├── 校验同 vid 的 aggregateSequence
    ├── 用 eventId 幂等更新 Redis
    └── MySQL 事务内幂等累加 video_status
          ↓
事务提交成功后 basicAck
```

`afterCommit` 只负责唤醒 Relay 或缩短下一次扫描等待，不直接应用 Redis，也不绕过 Outbox 发送。这样同一 vid 的事件只经过一条有序消费路径，避免请求线程快速更新 Redis 与 MQ 消费者并发时破坏 `aggregateSequence`。即使唤醒信号丢失，周期 Relay 仍会扫描 Outbox。

### 4.2 消费失败链路

消费失败不能只执行一次 `basicNack(requeue=false)` 并期待 RabbitMQ 自动完成分级重试。消费者必须显式区分暂时性异常与永久性异常：

```text
暂时性失败
    ↓
读取 x-death 得到已重试次数
    ├── 未达上限：使用 mandatory + Confirm 发布到 Retry Exchange
    │                ↓ 发布确认成功
    │              ACK 原消息
    │                ↓ Retry Queue TTL 到期
    │              dead-letter 回 Main Exchange
    └── 已达上限：使用 mandatory + Confirm 发布到 DLX
                     ↓ 发布确认成功
                   ACK 原消息

永久性失败
    ↓
使用 mandatory + Confirm 直接发布到 DLX
    ↓ 发布确认成功
ACK 原消息
```

如果重新发布 Retry 或 DLQ 消息失败、被 Return、NACK 或 Confirm 超时，**不能 ACK 原消息**。此时保留原消息未确认并让通道恢复后重新投递，避免在“转发失败 + 原消息已 ACK”的窗口中丢失事件。

### 4.3 查询链路

```text
查询视频统计
    ↓
优先读取 Redis Hash
    ├── 存在且合法：直接返回实时统计
    └── 不存在：从 MySQL 读取基线并安全初始化 Redis
```

目标方案中统计 Hash 不再是普通的 10 分钟查询缓存，而是实时计数状态。**不能继续让尚未落库的计数随 TTL 自动消失。**

---

## 5. 消息语义设计

### 5.1 事件类型

```java
public enum VideoStatsEventType {
    PLAY,
    LIKE,
    UNLIKE,
    COMMENT,
    COIN,
    SHARE,
    COLLECT,
    DANMU
}
```

取消点赞、取消收藏等不需要额外事件类型，通过负 `delta` 表示：

| 业务行为 | type | delta |
|---|---|---:|
| 播放一次 | `PLAY` | `1` |
| 点赞 | `LIKE` | `1` |
| 取消点赞 | `LIKE` | `-1` |
| 点踩 | `UNLIKE` | `1` |
| 取消点踩 | `UNLIKE` | `-1` |
| 收藏 | `COLLECT` | `1` |
| 取消收藏 | `COLLECT` | `-1` |
| 投两个币 | `COIN` | `2` |
| 分享一次 | `SHARE` | `1` |

### 5.2 消息模型

```java
public record VideoStatsChangedEvent(
        String eventId,
        Integer vid,
        Long aggregateSequence,
        VideoStatsEventType type,
        Integer delta,
        Double hotScoreDelta,
        LocalDateTime occurredAt,
        Integer schemaVersion,
        String traceId
) {
}
```

示例 JSON：

```json
{
  "eventId": "01J2Y6RHM7Z7K4P9Y2H9N3Q8XA",
  "vid": 1001,
  "type": "LIKE",
  "delta": 1,
  "hotScoreDelta": 1.5,
  "occurredAt": "2026-07-18T15:30:12.123",
  "schemaVersion": 1,
  "traceId": "c2b1d9c77a764a32"
}
```

约束：

- `eventId` 在事件第一次创建时生成，后续 Redis 重试、Outbox 重发、MQ 重投都必须复用；
- `aggregateSequence > 0`，由同一 vid 的数据库版本机制原子生成；
- `vid > 0`；
- `delta != 0`；
- 事件类型和 delta 必须由服务端生成，不能接收客户端传入的 Hash 字段名；
- `hotScoreDelta` 由服务端统一权重计算器生成；
- `schemaVersion` 用于未来消息升级；
- 不在消息中携带完整 `VideoStatus` 快照，避免旧快照覆盖新数据。

### 5.3 RabbitMQ 拓扑与路由协议

| 组件 | 类型 | 路由或参数 | 作用 |
|---|---|---|---|
| `video.stats.exchange.v1` | durable direct exchange | `video.stats.changed.v1` | 接收主统计消息 |
| `video.stats.persist.queue.v1` | durable queue | 绑定主 Exchange | 消费者主队列 |
| `video.stats.retry.exchange.v1` | durable direct exchange | `video.stats.retry.v1` | 接收暂时性失败消息 |
| `video.stats.retry.queue.v1` | durable queue | TTL 10 秒，过期后回主 Exchange | 延迟重试 |
| `video.stats.dlx.v1` | durable direct exchange | `video.stats.dead.v1` | 接收永久失败或重试耗尽消息 |
| `video.stats.dlq.v1` | durable queue | 绑定 DLX | 人工检查和受控修复 |

Retry Queue 的完整参数：

```text
x-message-ttl = 10000
x-dead-letter-exchange = video.stats.exchange.v1
x-dead-letter-routing-key = video.stats.changed.v1
```

所有发布都必须设置：

```text
mandatory = true
message delivery mode = PERSISTENT
messageId = eventId
correlationData = eventId + publishAttemptId
```

重试次数以 RabbitMQ 生成的 `x-death` 为可信来源；消费者不信任客户端可以任意构造的 `x-retry-count`。永久性错误显式发布到 DLX，不经过 Retry Queue。

RabbitMQ 的可靠范围是 Broker 已持久接收消息，不代表消费者已经处理完成。主 Queue、Retry Queue、DLQ 均为 durable，消息均为 persistent。

---

## 6. Redis 数据结构

### 6.1 实时统计 Hash

```text
video:stats:v2:{vid}
```

字段：

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

使用 `v2` 是为了避免把当前“10 分钟快照缓存”与新的“实时计数状态”混在一起。

### 6.2 热门 ZSet

```text
feed:hot:videos:v2
```

```text
member = vid 十进制字符串
score  = 当前热门分数
```

### 6.3 Redis 消费幂等 Key

```text
video:stats:processed:v1:{eventId}
```

建议 TTL：30 天，并且必须长于 RabbitMQ 的最大自动重试和常规人工处理窗口。

```text
SET video:stats:processed:v1:{eventId} 1 NX EX 2592000
```

注意：超过幂等 TTL 后，不允许直接把非常旧的 DLQ 消息重新投入主队列。旧消息应通过 MySQL 对账和单视频精确重建处理，避免 Redis 重复计数。

### 6.4 实时统计 Key 的 TTL

`video:stats:v2:{vid}` 不设置普通短 TTL，原因是其中可能包含尚未持久化到 MySQL 的实时变化。

允许删除实时统计 Key 的场景：

- 已确认 RabbitMQ 无积压且 Redis 与 MySQL 已对账；
- 视频已删除或长期不可展示；
- 执行受控的单视频精确重建；
- Redis 故障恢复后从 MySQL 重建。

不能直接沿用当前代码：

```java
private static final Duration CACHE_TTL = Duration.ofMinutes(10);
```

---

## 7. Redis Lua 原子计数

### 7.1 Lua 的职责

一次脚本原子完成：

1. 检查 `eventId` 是否已处理；
2. 检查统计 Hash 已初始化；
3. 根据白名单字段执行 `HINCRBY`；
4. 禁止计数小于零；
5. 对热门 ZSet 执行 `ZINCRBY`；
6. 写入 eventId 幂等标记；
7. 返回处理结果。

返回状态：

```text
APPLIED          首次处理并成功更新
DUPLICATE        eventId 已处理，本次不重复更新
NEEDS_REBUILD    Hash 或 ZSet 尚未安全初始化
NEGATIVE_RESULT  本次变化会导致计数小于零
INVALID_FIELD    非法统计字段
```

### 7.2 Lua 伪代码

文件建议：

```text
src/main/resources/lua/video-stats-increment.lua
```

```lua
-- KEYS[1] stats hash
-- KEYS[2] hot zset
-- KEYS[3] processed event key
-- ARGV[1] field
-- ARGV[2] delta
-- ARGV[3] member
-- ARGV[4] score delta
-- ARGV[5] event ttl seconds

if redis.call('EXISTS', KEYS[3]) == 1 then
    return 'DUPLICATE'
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'NEEDS_REBUILD'
end

if redis.call('ZSCORE', KEYS[2], ARGV[3]) == false then
    return 'NEEDS_REBUILD'
end

local allowed = {
    playTimes = true,
    likeTimes = true,
    unlikeTimes = true,
    commentTimes = true,
    coinTimes = true,
    shareTimes = true,
    collectTimes = true,
    danmuTimes = true
}

if allowed[ARGV[1]] ~= true then
    return 'INVALID_FIELD'
end

local current = tonumber(redis.call('HGET', KEYS[1], ARGV[1]))
local delta = tonumber(ARGV[2])
if current == nil or delta == nil then
    return 'NEEDS_REBUILD'
end

if current + delta < 0 then
    return 'NEGATIVE_RESULT'
end

redis.call('HINCRBY', KEYS[1], ARGV[1], delta)
redis.call('ZINCRBY', KEYS[2], ARGV[4], ARGV[3])
redis.call('SET', KEYS[3], '1', 'EX', ARGV[5])
return 'APPLIED'
```

实际实现还应限制 Lua 数字安全范围。Redis Lua 5.1 使用双精度数，参与计算的整数不能超过 `2^53 - 1`。

### 7.3 初始化、并发与顺序问题

不能对不存在的 Hash 直接执行 `HINCRBY`，否则会从零创建错误统计；也不能简单执行“读 MySQL → HSET 覆盖”，因为初始化期间可能已经有新的 Redis 增量。

第一版采用以下边界：

1. **只支持 Redis 单实例、主从或 Sentinel，不支持 Redis Cluster。** 当前 Lua 同时访问单视频 Hash、全局热门 ZSet 和 eventId Key，它们在 Cluster 中无法保证同 slot；未来迁移 Cluster 时，应把全局 ZSet 更新拆成独立的可重算异步步骤。
2. 同一 `vid` 的 Redis 初始化和事件应用必须通过同一个按 vid 串行执行器；事件进入对应 vid 的有序分区后串行处理。
3. 初始化先写临时 Hash `video:stats:v2:{vid}:building:{token}`，完整校验后再原子切换正式 Hash；初始化状态保存 `INITIALIZING/READY` 和 fencing token，旧初始化任务不得覆盖新状态。
4. ZSet 初始化与 Hash ready 标记由受控 Lua 完成；普通计数脚本只处理状态为 `READY` 的 vid，否则返回 `NEEDS_REBUILD` 并延迟重试。
5. 初始化期间到达的事件保留在同一 vid 的串行队列中，初始化完成后按业务序号继续执行，不能绕过初始化锁直接更新正式 Hash。

事件增加 `aggregateSequence`：

```java
public record VideoStatsChangedEvent(
        String eventId,
        Integer vid,
        Long aggregateSequence,
        VideoStatsEventType type,
        Integer delta,
        Double hotScoreDelta,
        LocalDateTime occurredAt,
        Integer schemaVersion,
        String traceId
) {
}
```

`aggregateSequence` 必须在产生业务事实的 MySQL 事务内，按 vid 从数据库序列表或统计版本列原子递增后写入事件。消费者按 vid 分区串行处理，并验证序号：

- `sequence == lastSequence + 1`：正常应用；
- `sequence <= lastSequence`：重复或旧事件，结合 eventId 幂等处理；
- `sequence > lastSequence + 1`：说明前序事件尚未到达，进入延迟重试，不能先应用负 delta。

这解决了“点赞 +1 尚未处理，取消点赞 -1 先到达”导致的错误负数保护。仅有 eventId 只能解决重复，不能解决乱序。

生产者和消费者都调用统一的 `VideoStatsCounterService#apply(event)`，禁止各自复制初始化、序号检查和 Lua 解析逻辑。

---

## 8. MySQL 表设计

### 8.1 Outbox 表

```sql
CREATE TABLE video_stats_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    aggregate_id INT NOT NULL COMMENT 'vid',
    aggregate_sequence BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload JSON NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=PENDING,1=SENDING,2=SENT,3=FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NULL,
    sending_at DATETIME(3) NULL,
    lease_owner VARCHAR(128) NULL,
    lease_token VARCHAR(64) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at DATETIME(3) NULL,
    UNIQUE KEY uk_video_stats_outbox_event_id (event_id),
    KEY idx_video_stats_outbox_poll (status, next_retry_at, sending_at, id)
) ENGINE=InnoDB;
```

Outbox 与用户业务事实必须在同一个 MySQL 事务中提交。`SENDING` 是带租约的临时状态，不是永久终态：

- 抢占时同时写入 `sending_at`、`lease_owner` 和唯一 `lease_token`；
- `SENDING` 超过 60 秒视为租约过期，由启动恢复任务和周期任务重新置为 `PENDING`；
- Confirm、Return 和超时处理都必须使用 `WHERE event_id=? AND lease_token=?` 条件更新；
- 旧发送尝试迟到的回调不能覆盖新一轮发送状态；
- 租约过期后可能重复发布，重复由消费者 eventId 幂等吸收。

### 8.2 消费幂等表

```sql
CREATE TABLE video_stats_consumed_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    vid INT NOT NULL,
    aggregate_sequence BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    delta BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    process_status TINYINT NOT NULL DEFAULT 0 COMMENT '0=RECEIVED,1=MYSQL_COMMITTED,2=REPAIR_REQUIRED',
    last_error VARCHAR(1000) NULL,
    consumed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    committed_at DATETIME(3) NULL,
    UNIQUE KEY uk_video_stats_consumed_event_id (event_id),
    KEY idx_video_stats_consumed_vid (vid),
    KEY idx_video_stats_consumed_repair (process_status, id)
) ENGINE=InnoDB;
```

唯一索引是最终防线。不能只先 `SELECT` 再 `INSERT`，因为两个并发消费者可能同时查到不存在。

发生 eventId 唯一键冲突时，不能无条件当作成功。消费者必须读取原记录，并比较 `vid`、`event_type`、`delta` 和规范化 `payload_hash`：

- 内容完全一致且状态为 `MYSQL_COMMITTED`：按重复消息成功处理；
- 内容完全一致但状态为 `RECEIVED` 或 `REPAIR_REQUIRED`：继续修复流程，不能直接 ACK；
- 同一个 eventId 对应不同业务内容：视为生产端严重错误，告警并进入 DLQ。

### 8.3 `video_status` 字段类型

高频计数建议把统计字段逐步升级为 `BIGINT UNSIGNED`，Java 实体改为 `Long`。当前 `Integer` 最大约 21 亿，热门视频的播放量长期运行后可能溢出。

### 8.4 增量落库 SQL

不要使用客户端传入的 `${field}` 动态拼列名。推荐把事件转换为固定的八项 delta：

```java
public record VideoStatsDelta(
        long playDelta,
        long likeDelta,
        long unlikeDelta,
        long commentDelta,
        long coinDelta,
        long shareDelta,
        long collectDelta,
        long danmuDelta
) {
}
```

MyBatis SQL 使用固定列名：

```sql
UPDATE video_status
SET play_times   = play_times + #{playDelta},
    like_times   = like_times + #{likeDelta},
    unlike_times = unlike_times + #{unlikeDelta},
    comment_times = comment_times + #{commentDelta},
    coin_times   = coin_times + #{coinDelta},
    share_times  = share_times + #{shareDelta},
    collect_times = collect_times + #{collectDelta},
    danmu_times  = danmu_times + #{danmuDelta}
WHERE vid = #{vid}
  AND play_times + #{playDelta} >= 0
  AND like_times + #{likeDelta} >= 0
  AND unlike_times + #{unlikeDelta} >= 0
  AND comment_times + #{commentDelta} >= 0
  AND coin_times + #{coinDelta} >= 0
  AND share_times + #{shareDelta} >= 0
  AND collect_times + #{collectDelta} >= 0
  AND danmu_times + #{danmuDelta} >= 0;
```

更新行数必须等于 `1`，否则消费事务回滚并进入重试或死信分析，不能使用 `GREATEST(x - 1, 0)` 静默掩盖重复扣减。

---

## 9. RabbitMQ 配置

### 9.1 Maven 依赖

在 `pom.xml` 增加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

### 9.2 应用配置

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    publisher-confirm-type: correlated
    publisher-returns: true
    listener:
      simple:
        acknowledge-mode: manual
        prefetch: 50
        default-requeue-rejected: false

app:
  video-stats:
    outbox:
      batch-size: 100
      fixed-delay-ms: 1000
    redis-event-ttl-days: 30
    consumer-max-retries: 5
```

生产环境不能使用 `guest/guest`，配置应来自环境变量或密钥管理系统。

### 9.3 声明 Exchange、Queue 与 DLQ

建议文件：

```text
config/rabbitmq/VideoStatsRabbitConfig.java
```

主队列绑定 DLX；重试队列设置 TTL，并在过期后路由回主 Exchange。第一版可以使用固定重试延迟，例如 10 秒；进一步可以建立 10 秒、1 分钟、10 分钟多级重试队列。

必须开启：

- durable exchange；
- durable queue；
- persistent message；
- publisher confirm；
- publisher returns；
- consumer manual ack；
- dead-letter exchange。

---

## 10. 生产端实现

### 10.1 业务事务中写 Outbox

以点赞为例，当前代码在事务中同时更新 `user_video` 和 `video_status`。目标方案改为：

```text
1. 校验用户是否真的发生点赞状态变化
2. 更新 user_video
3. 不再同步更新 video_status
4. 生成稳定 eventId
5. 在同一事务 INSERT video_stats_outbox
6. 提交事务
```

伪代码：

```java
@Transactional(rollbackFor = Exception.class)
public void recordLike(
        Integer currentUserId,
        Integer vid,
        Boolean isLike,
        Boolean isSet
) {
    // 校验和 user_video 更新省略

    int delta = isSet ? 1 : -1;
    VideoStatsEventType type = isLike
            ? VideoStatsEventType.LIKE
            : VideoStatsEventType.UNLIKE;

    VideoStatsChangedEvent event = eventFactory.create(
            vid,
            type,
            delta
    );

    videoStatsOutboxMapper.insert(event);
    videoStatsEventAfterCommitPublisher.register(event);
}
```

如果状态没有变化，例如重复点赞，应直接返回，不能创建事件。

### 10.2 投币事务

投币仍必须在同一个 MySQL 事务中完成：

- 扣减用户硬币余额；
- 更新 `user_video` 投币事实；
- 插入 `COIN` 统计 Outbox 事件，`delta = coin`。

不再由请求线程同步修改 `video_status.coin_times`。

### 10.3 播放与分享

播放和分享当前没有复杂用户事实，但仍建议统一插入 Outbox，原因是统一可靠性模型更容易维护。

播放量高后，Outbox 会产生大量追加写入，但它避免了所有请求竞争更新同一个 `video_status` 热点行。真正达到更高流量后，再评估批量事件、Kafka 或专门的计数流水，不在第一版混入两套投递协议。

播放事件必须先经过现有或计划中的有效播放去重，只有一次有效播放才创建一个 Outbox 事件。

### 10.4 `afterCommit` 只唤醒 Relay

事务提交后可以发布一个进程内唤醒信号，让 Outbox Relay 立即开始扫描，但不能在这里直接更新 Redis或绕过 Outbox 发布 RabbitMQ：

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleAfterCommit(VideoStatsOutboxCreatedEvent event) {
    videoStatsOutboxRelay.wakeup();
}
```

唤醒失败不影响业务事实，也不把 Outbox 标为 `SENT`。周期 Relay 仍会兜底扫描。这样所有 Redis 统计变化都由 MQ 消费有序执行，不会出现 afterCommit 和消费者争抢同一 `aggregateSequence`。

---

## 11. Outbox Relay 实现

### 11.1 扫描流程

```text
定时任务每 1 秒执行
    ↓
查询 status=PENDING 且 next_retry_at <= now 的前 100 条
    ↓
抢占为 SENDING
    ↓
逐条或批量发布 RabbitMQ
    ↓
收到该发送尝试的 Confirm/Return 信号
    ↓
ACK 且结算窗口内未 Return：按 leaseToken 标记 SENT
NACK/Return/超时：按 leaseToken 恢复 PENDING，retry_count +1
超过发布重试上限：标记 FAILED 并告警
```

### 11.2 多实例抢占

单实例阶段可以先用短事务查询并更新；多实例部署前应使用：

```sql
SELECT ...
FROM video_stats_outbox
WHERE status = 0
  AND (next_retry_at IS NULL OR next_retry_at <= NOW(3))
ORDER BY id
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

抢占后快速提交，不能在持有数据库行锁期间等待 RabbitMQ Confirm。

### 11.3 发送尝试状态机、Confirm 与 Return

每一次发送都生成独立 `publishAttemptId/leaseToken`，并设置 `mandatory=true`。Confirm 和 Return 通过同一个 attempt 关联，不能只凭 eventId 判断某次发送结果。

```text
SENDING
  ├── NACK ------------------------→ PENDING
  ├── Confirm timeout -------------→ PENDING
  ├── ReturnedMessage ------------→ PENDING
  └── ACK 且确认未发生 Return ----→ SENT
```

Spring AMQP 中 Return 与 Confirm 可能是两个异步信号。实现不能在收到 ACK 的瞬间无条件标记 `SENT`。建议为发送尝试保存短暂结算状态：

1. Return 到达时立即记录 `returned=true`，并按 `leaseToken` 恢复 `PENDING`；
2. NACK 或 Confirm 超时按 `leaseToken` 恢复 `PENDING`；
3. ACK 到达后进入 `ACKED_WAIT_RETURN_WINDOW`，等待一个短结算窗口或依赖当前 Spring AMQP 版本已经验证的回调顺序；
4. 只有 ACK 且该 attempt 没有 Return，才按 `leaseToken` 标记 `SENT`；
5. 任何旧 attempt 的迟到回调均因 leaseToken 不匹配而失效。

这种状态机允许“不确定时重复发布”，但不允许“没有路由到 Queue 却被错误标记 SENT”。重复消息由消费者 eventId 幂等吸收。

消息发布时设置：

```text
messageId = eventId
correlationData.id = eventId + ":" + publishAttemptId
contentType = application/json
contentEncoding = UTF-8
deliveryMode = PERSISTENT
```

### 11.4 Outbox 清理

`SENT` 记录不能无限增长。建议：

- 保留 7～30 天用于审计；
- 分批删除，避免一次大事务；
- `FAILED` 记录必须先处理，不能被普通清理任务删除。

---

## 12. 消费端实现

### 12.1 消费顺序

```text
收到消息
    ↓
校验 schemaVersion、eventId、vid、aggregateSequence、type、delta
    ↓
校验同 vid 的 sequence 是否连续
    ├── 有缺口：进入延迟重试，等待前序事件
    └── 连续：继续
    ↓
使用相同 eventId 调用 Redis 计数服务
    ├── APPLIED：Redis 首次更新
    ├── DUPLICATE：Redis 已由上一次消费尝试更新
    └── NEEDS_REBUILD：执行受控初始化，再重试 Lua
    ↓
开启 MySQL 事务
    ↓
插入或读取 video_stats_consumed_event
    ├── 相同 eventId、相同 payload 且 MYSQL_COMMITTED：重复成功
    ├── 相同 eventId、不同 payload：严重冲突，进入 DLQ
    └── 首次事件：固定八项 delta 更新 video_status
    ↓
标记 MYSQL_COMMITTED 并提交
    ↓
basicAck
```

如果 Redis 已经 `APPLIED`，但 MySQL 因永久性数据问题无法提交，消费记录标记为 `REPAIR_REQUIRED`，消息进入 DLQ。DLQ 不是一致性已经恢复的标志，必须进入第 12.5 节的受控修复流程。

### 12.2 为什么 Redis 和 MySQL 要分别幂等

可能出现：

```text
Redis 更新成功
    ↓
MySQL 更新失败
    ↓
消息重新投递
```

重投时 Redis 必须返回 `DUPLICATE`，但 MySQL 仍应继续尝试。

也可能出现：

```text
MySQL 提交成功
    ↓
消费者在 ACK 前崩溃
    ↓
消息重新投递
```

重投时 MySQL 幂等表必须阻止重复落库。

因此不能用“Redis 已处理”来代替“MySQL 已处理”，也不能反过来。

### 12.3 MySQL 消费事务

建议单独 Service，确保 Spring 事务代理生效：

```java
@Service
@RequiredArgsConstructor
public class VideoStatsPersistenceService {

    private final VideoStatsConsumedEventMapper consumedEventMapper;
    private final VideoStatusMapper videoStatusMapper;

    @Transactional(rollbackFor = Exception.class)
    public void persist(VideoStatsChangedEvent event) {
        boolean firstConsume = consumedEventMapper.tryInsert(event) == 1;
        if (!firstConsume) {
            return;
        }

        VideoStatsDelta delta = VideoStatsDelta.from(event);
        int rows = videoStatusMapper.applyDelta(event.vid(), delta);
        if (rows != 1) {
            throw new BusinessException(500, "视频统计异步落库失败");
        }
    }
}
```

`tryInsert` 可以捕获唯一键冲突并返回 `0`，但其他数据库异常必须继续抛出。

### 12.4 手动 ACK 与显式重试

下面是职责示意，不是可以直接复制上线的完整代码：

```java
@RabbitListener(queues = VideoStatsRabbitConstants.MAIN_QUEUE)
public void consume(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();

    try {
        VideoStatsChangedEvent event = converter.convert(message);
        videoStatsOrderingService.validateNext(event);
        videoStatsCounterService.apply(event);
        videoStatsPersistenceService.persist(event);
        channel.basicAck(deliveryTag, false);
    } catch (RetryableMessageException e) {
        retryPublisher.publishWithConfirm(message);
        channel.basicAck(deliveryTag, false);
    } catch (NonRetryableMessageException e) {
        deadLetterPublisher.publishWithConfirm(message, e);
        channel.basicAck(deliveryTag, false);
    }
}
```

这里的 `publishWithConfirm` 只有在 mandatory 发布未 Return 且 Confirm ACK 后才返回；否则抛异常，原消息保持未 ACK。不能使用 `basicNack(requeue=true)` 无限热循环，也不能在 Retry/DLQ 转发尚未确认时 ACK 原消息。

### 12.5 Redis 已更新但 MySQL 永久失败的修复

事件处理状态：

```text
RECEIVED → REDIS_APPLIED → MYSQL_COMMITTED
                         ↘ REPAIR_REQUIRED
```

当 Redis 已更新，但 `video_status` 行缺失、约束损坏或出现无法通过自动重试恢复的问题时：

1. 将消费记录更新为 `REPAIR_REQUIRED`，保存错误和 payload hash；
2. 消息进入 DLQ并告警；
3. 运维工具按 eventId 检查 Redis processed Key、MySQL 消费记录和业务事实；
4. 优先修复 MySQL 数据后再次调用 `persist(event)`，Redis 因相同 eventId 返回 `DUPLICATE`；
5. 若该事件本身不应成立，发布一个新的补偿事件，使用新的 `compensationEventId` 和相反 delta；补偿事件也走完整 Outbox、MQ 和双端幂等流程；
6. 修复成功后把原记录标记为 `MYSQL_COMMITTED` 或 `COMPENSATED`，保留审计信息。

不能通过删除 Redis processed Key 后直接重放原消息进行“修复”，否则可能重复增加 Redis。对账任务必须扫描 `REPAIR_REQUIRED`，不能只发告警。

---

## 13. 重试与死信

### 13.1 可重试异常

- Redis 连接超时；
- MySQL 短暂不可用；
- 数据库死锁；
- RabbitMQ 短暂网络异常；
- 初始化锁暂时未获得。

### 13.2 不可重试异常

- JSON 无法解析；
- 未知 `schemaVersion`；
- 非法事件类型；
- `vid <= 0`；
- `delta == 0`；
- 事件类型与 delta 不符合服务端规则；
- 数据违反无法自动修复的约束。

### 13.3 重试次数和超期保护

重试次数以 RabbitMQ `x-death` 为准：

```text
最多自动重试 5 次
Retry Queue 固定延迟 10 秒（后续可升级多级退避）
超过上限显式发布 DLQ
```

不能立即 `basicNack(requeue=true)` 无限原地重试，否则故障消息会形成热循环，占满消费者。

系统同时定义：

- 主队列和 Retry Queue 的最大正常滞留窗口；
- DLQ 人工处理 SLA，例如 7 天内完成；
- Redis eventId 幂等 TTL 必须覆盖“最大消息保留 + 最大自动重试 + DLQ SLA”，并额外保留安全余量；
- 消费者校验 `occurredAt`，超过 Redis 幂等安全窗口的消息不能进入普通 apply 流程，直接进入“超期事件修复队列”；
- 重放工具强制查询 MySQL 消费记录和 payload hash，并执行按 vid 精确对账，不允许一键直接回投旧消息。

这把“不要重放旧消息”从人工注意事项提升为系统校验，避免 Redis 幂等 Key 已过期、MySQL 却仍拒绝重复时产生双端分叉。

### 13.4 DLQ 运维

DLQ 消息必须可以查看：

- eventId；
- vid；
- type；
- delta；
- 第一次和最后一次失败时间；
- 重试次数；
- 最后异常；
- traceId。

人工重放前先查询：

1. `video_stats_consumed_event` 是否已有 eventId；
2. MySQL `video_status` 当前值；
3. Redis 幂等 Key 是否仍存在；
4. 消息是否超过 Redis 幂等 TTL。

超过 Redis 幂等 TTL 的旧消息不直接重放，应先完成 MySQL侧判断，再按 vid 精确重建 Redis。

---

## 14. 一致性时序分析

### 14.1 正常情况

```text
业务事务 + Outbox 提交
    ↓
Relay 发布 MQ
    ↓
消费者按 sequence 更新 Redis
    ↓
消费者幂等落库 MySQL
    ↓
ACK
```

### 14.2 Redis 暂时失败

```text
业务事务 + Outbox 提交
    ↓
MQ 发布成功
    ↓
消费者更新 Redis 失败
    ↓
显式进入 Retry Queue
    ↓
Redis 恢复后按相同 eventId/sequence 继续处理
    ↓
MySQL 落库并 ACK
```

实时统计会短暂落后，但事件仍保留在可靠消息链路中。

### 14.3 MQ 立即发布失败

```text
业务事务 + Outbox 提交
    ↓
立即发布失败
    ↓
Outbox Relay 稍后重发
    ↓
消费者处理
```

不会因为一次网络失败永久丢消息。

### 14.4 消费者在 Redis 后崩溃

```text
Redis APPLIED
    ↓
进程崩溃，未落库、未 ACK
    ↓
消息重投
    ↓
Redis DUPLICATE
    ↓
MySQL 首次落库
    ↓
ACK
```

### 14.5 消费者在 MySQL 提交后、ACK 前崩溃

```text
MySQL 已提交
    ↓
未 ACK，消息重投
    ↓
Redis DUPLICATE
    ↓
MySQL eventId 唯一键冲突，视为已处理
    ↓
ACK
```

### 14.6 Redis 整体数据丢失

```text
暂停或降级统计写入
    ↓
确认 MQ 当前积压情况
    ↓
从 MySQL video_status 重建 Hash
    ↓
重新计算 ZSet
    ↓
恢复消费者和写入
```

如果 Redis 丢失时仍有未落库消息，应先按受控流程处理队列，防止使用过旧 MySQL 基线覆盖正在重建的数据。

---

## 15. 与 `VideoStatsRedisServiceImpl` 草稿的改造关系

用户提供的草稿混合了“缓存读取”和“Hash 写入”，但当前工作区没有该类文件。后续若恢复或创建该实现，目标方案建议拆分职责：

```text
VideoStatsQueryService
    负责读取实时统计、缺失时安全初始化

VideoStatsCounterService
    负责按 eventId 执行 Lua 原子计数

VideoStatsRebuildService
    负责从 MySQL 精确重建单视频 Hash 和 ZSet

VideoStatsPersistenceService
    负责消费者侧幂等落库

VideoStatsOutboxService
    负责事务内创建事件

VideoStatsOutboxRelay
    负责可靠发布 RabbitMQ
```

该草稿可以被重命名，或逐步收缩为 Query/Rebuild 能力。它的以下逻辑需要调整：

```java
redisUtils.expire(key, CACHE_TTL);
```

在 v2 实时计数 Key 上删除该短 TTL。

`refresh(VideoStatus)` 不能在运行中随意覆盖实时 Hash，因为覆盖时可能存在尚未落库的 Redis 新增量。它只能用于：

- 新视频首次初始化；
- 暂停该 vid 事件处理后的精确重建；
- Redis 故障恢复；
- 明确完成 MQ 排空和一致性检查后的管理员修复。

---

## 16. 推荐包与文件结构

```text
src/main/java/com/feibijiubi/backend/
├── config/rabbitmq/
│   └── VideoStatsRabbitConfig.java
├── constants/
│   ├── VideoStatsRabbitConstants.java
│   └── VideoStatsRedisConstants.java
├── event/video/
│   ├── VideoStatsChangedEvent.java
│   ├── VideoStatsEventType.java
│   └── VideoStatsEventFactory.java
├── mapper/
│   ├── VideoStatsOutboxMapper.java
│   ├── VideoStatsConsumedEventMapper.java
│   └── VideoStatusMapper.java
├── service/video/
│   ├── VideoStatsQueryService.java
│   ├── VideoStatsCounterService.java
│   ├── VideoStatsRebuildService.java
│   ├── VideoStatsOutboxService.java
│   └── VideoStatsPersistenceService.java
├── service/impl/video/
│   ├── VideoStatsQueryServiceImpl.java
│   ├── VideoStatsCounterServiceImpl.java
│   ├── VideoStatsRebuildServiceImpl.java
│   ├── VideoStatsOutboxServiceImpl.java
│   └── VideoStatsPersistenceServiceImpl.java
├── mq/video/
│   ├── VideoStatsEventPublisher.java
│   ├── VideoStatsOutboxRelay.java
│   └── VideoStatsEventConsumer.java
└── task/
    ├── VideoStatsReconciliationTask.java
    └── VideoStatsOutboxCleanupTask.java

src/main/resources/
├── lua/video-stats-increment.lua
└── com/feibijiubi/backend/mapper/
    ├── VideoStatsOutboxMapper.xml
    ├── VideoStatsConsumedEventMapper.xml
    └── VideoStatusMapper.xml
```

不要把消息发送、Redis Lua、MySQL 落库和业务状态判断全部写进 `UserVideoServiceImpl`。

---

## 17. 分阶段实施顺序

### 阶段 0：修正统计模型

- [ ] 将统计字段由 `Integer` 评估升级为 `Long`；
- [ ] Redis Key 升级为 `video:stats:v2:{vid}`；
- [ ] 取消实时 Hash 的 10 分钟 TTL；
- [ ] 集中定义事件类型、Hash 字段和热度权重；
- [ ] 保留当前同步 MySQL 路径作为迁移回退开关。

### 阶段 1：实现 Redis 幂等 Lua

- [ ] 增加 `video-stats-increment.lua`；
- [ ] 实现 `VideoStatsCounterService`；
- [ ] 实现 eventId 幂等 Key；
- [ ] 实现 sequence 连续性校验和同 vid 串行处理；
- [ ] 实现带 fencing token 的安全初始化；
- [ ] 明确第一版仅支持 Redis 单实例/主从/Sentinel，不支持 Cluster；
- [ ] 测试 `APPLIED`、`DUPLICATE`、`NEEDS_REBUILD`、乱序等待和负数保护。

此阶段先在测试或影子链路中运行，不立即删除 MySQL 同步更新。

### 阶段 2：引入 RabbitMQ 基础设施

- [ ] 增加 `spring-boot-starter-amqp`；
- [ ] 配置连接、Confirm、Return 和手动 ACK；
- [ ] 声明主 Exchange、Queue、Retry Queue、DLX、DLQ；
- [ ] 验证持久消息和服务重启后的队列恢复。

### 阶段 3：实现 Outbox

- [ ] 创建 `video_stats_outbox` 并增加按 vid 原子递增的 `aggregateSequence`；
- [ ] 事务内插入稳定事件；
- [ ] 实现 `afterCommit` 唤醒 Relay；
- [ ] 实现带租约、超时回收和 leaseToken 的 Outbox Relay；
- [ ] 实现 Confirm/Return 发送尝试状态机；
- [ ] 只有 ACK 且未 Return 时标记 `SENT`；
- [ ] 实现失败退避和清理。

### 阶段 4：实现幂等消费者

- [ ] 创建 `video_stats_consumed_event`；
- [ ] 实现固定字段增量 SQL；
- [ ] Redis 和 MySQL 分别幂等；
- [ ] 同 vid 的 sequence 缺口会延迟重试；
- [ ] 第一版消费者并发数为 1，扩容前完成按 vid 分区；
- [ ] eventId 冲突时校验 payload hash；
- [ ] MySQL 提交后才 ACK；
- [ ] Retry/DLQ 使用显式 Confirm 转发；
- [ ] `REPAIR_REQUIRED` 有受控补偿流程。

### 阶段 5：按业务逐步切流

推荐顺序：

```text
分享
  ↓
播放（先完成有效播放去重）
  ↓
收藏/取消收藏
  ↓
点赞/取消点赞、点踩/取消点踩
  ↓
投币
```

每接入一种事件，都先对比旧同步结果与新异步结果。投币最后迁移，因为涉及余额和业务事实事务。

### 阶段 6：删除同步统计更新

只有满足以下条件后，才删除请求线程中的：

```java
videoStatusMapper.increasePlayTimes(...)
videoStatusMapper.increaseLikeTimes(...)
videoStatusMapper.decreaseLikeTimes(...)
videoStatusMapper.increaseCoinTimes(...)
videoStatusMapper.increaseCollectTimes(...)
videoStatusMapper.decreaseCollectTimes(...)
videoStatusMapper.increaseShareTimes(...)
```

条件：

- MQ 稳定运行；
- Outbox 无长期积压；
- Consumer 幂等测试通过；
- Redis 与 MySQL 对账通过；
- DLQ 告警可用；
- 有功能开关可以回退同步链路。

### 阶段 7：对账与运维

- [ ] 定时抽样 Redis 与 MySQL；
- [ ] 提供按 vid 精确重建；
- [ ] 监控 Outbox PENDING/FAILED 数量；
- [ ] 监控 Queue Ready/Unacked 和 DLQ；
- [ ] 监控消费延迟；
- [ ] 建立故障处理手册。

---

## 18. 测试清单

### 18.1 Redis Lua 测试

- [ ] 同一 eventId 执行两次只增加一次；
- [ ] Hash 不存在返回 `NEEDS_REBUILD`；
- [ ] ZSet member 不存在返回 `NEEDS_REBUILD`；
- [ ] 非法字段被拒绝；
- [ ] 取消操作不会产生负数；
- [ ] 点踩的热门分数方向正确；
- [ ] 投两个币统计和热门分数都增加两倍；
- [ ] Hash 和 ZSet 不会半更新。

### 18.2 Outbox 测试

- [ ] 业务事务回滚时 Outbox 也回滚；
- [ ] 业务事务提交时 Outbox 必然存在；
- [ ] `SENDING` 租约超时后可以恢复；
- [ ] 旧 leaseToken 的迟到回调不会修改新发送状态；
- [ ] Confirm ACK 且未 Return 后才标记 `SENT`；
- [ ] Confirm NACK、Return、超时会重试；
- [ ] Return 与 Confirm 回调乱序不会误标 `SENT`；
- [ ] Relay 重复发布保持同一 eventId；
- [ ] 多实例抢占不会同时发送同一行，可能重复发布时由消费者幂等吸收。

### 18.3 消费者幂等测试

- [ ] 同一消息重复投递，MySQL 只增加一次；
- [ ] 同 eventId、不同 payload 会告警并进入 DLQ；
- [ ] Redis 已更新但 MySQL 暂时失败，重投后只补 MySQL；
- [ ] Redis 已更新但 MySQL 永久失败，会进入 `REPAIR_REQUIRED`；
- [ ] MySQL 已提交但 ACK 前崩溃，重投不会重复落库；
- [ ] sequence 缺口不会提前应用负 delta；
- [ ] 前序事件到达后，延迟事件可以继续处理；
- [ ] MySQL 更新行数不是 1 时事务回滚；
- [ ] MySQL 提交前不会 ACK；
- [ ] Retry/DLQ 发布未确认时不会 ACK 原消息；
- [ ] 非法消息进入 DLQ；
- [ ] 暂时性异常进入 Retry Queue；
- [ ] 超过 Redis 幂等安全窗口的旧消息被拒绝普通重放。

### 18.4 业务测试

- [ ] 重复点赞不创建新事件；
- [ ] 取消未点赞状态不创建事件；
- [ ] 收藏状态变化产生正确正负 delta；
- [ ] 投币失败时业务事实和 Outbox 一起回滚；
- [ ] 投两个币只生成一个 `delta=2` 事件；
- [ ] 无效播放不创建事件；
- [ ] Redis 显示值快速变化；
- [ ] MySQL 在可接受延迟内最终追平。

### 18.5 故障演练

- [ ] 关闭 RabbitMQ 后业务事务仍留下 PENDING Outbox；
- [ ] RabbitMQ 恢复后 Relay 自动补发；
- [ ] 关闭 Redis 后消息不丢失并进入重试；
- [ ] 关闭 MySQL 后消费者不 ACK；
- [ ] 消费者重启后未 ACK 消息重新投递；
- [ ] DLQ 消息可以定位并按手册处理；
- [ ] Redis 清空后可以从 MySQL 重建。

---

## 19. 监控指标

至少监控：

```text
video_stats_outbox_pending_total
video_stats_outbox_failed_total
video_stats_outbox_oldest_pending_seconds
video_stats_publish_confirm_nack_total
video_stats_publish_return_total
video_stats_queue_ready
video_stats_queue_unacked
video_stats_consume_success_total
video_stats_consume_retry_total
video_stats_consume_duplicate_total
video_stats_dlq_total
video_stats_consume_latency_ms
video_stats_redis_apply_failure_total
video_stats_reconciliation_mismatch_total
```

关键告警：

- Outbox 最老 PENDING 超过阈值；
- 主队列持续积压；
- DLQ 出现新消息；
- Redis Lua 失败率升高；
- MySQL 异步落库延迟超过业务允许范围；
- Redis 与 MySQL 对账差异持续扩大。

---

## 20. 重要边界与不做事项

第一版不做：

- 不假设 RabbitMQ exactly-once；
- 不允许客户端指定 Redis 字段；
- 不用 MQ 消息携带完整统计快照覆盖 MySQL；
- 不在消费者中自动无限 `requeue=true`；
- 不使用短 TTL 自动删除实时统计 Hash；
- 不在没有 Confirm 的情况下把 Outbox 标记为 `SENT`；
- 不用 Redis 幂等标记代替 MySQL 唯一约束；
- 不在未完成对账和故障演练前删除同步统计更新；
- 不把热门 ZSet 当作视频可见性的事实来源；
- 不把点赞、收藏、投币等用户行为事实只放在 Redis。

RabbitMQ 在这里解决的是异步传递、削峰、重试和解耦，不会自动解决一致性。最终一致性来自以下机制共同作用：

```text
稳定 eventId
+ MySQL Outbox
+ Publisher Confirm/Return
+ durable/persistent
+ Redis Lua 幂等
+ MySQL 唯一键幂等
+ 手动 ACK
+ Retry/DLQ
+ 对账与重建
```

---

## 21. 最终落地结果

完成后，菲比啾比的视频统计链路应为：

```text
用户业务事实
    MySQL 事务保存，保持强一致

统计事件
    与业务事实一起写入 Outbox，保证不会因进程崩溃丢失

Redis Hash
    保存实时聚合统计，Lua 原子更新，详情页优先读取

Redis ZSet
    使用同一个 Lua 根据互动权重实时调整热门分数

RabbitMQ
    异步传递统计事件，削平瞬时写入压力

MySQL video_status
    由消费者按 eventId 幂等累加，作为最终持久化和 Redis 重建来源
```

一句话总结：

> 请求线程不再直接更新 `video_status` 热点行，而是可靠地产生统计事件；Redis 提供实时计数，RabbitMQ 负责异步传递，消费者通过 eventId 幂等地把增量持久化到 MySQL，最后由重试、死信、对账和重建保证最终一致性。

---

## 22. 可编译的第一版完整实现

> 本章不是伪代码，而是按照当前仓库的包名、MyBatis XML 路径、`RedisUtils#executeScript(...)` 签名和 `UserVideoServiceImpl` 业务语义整理的一套第一版闭环代码。
>
> “完整”指：业务事务写 Outbox → Relay 可靠发布 → RabbitMQ 消费 → Redis Lua 幂等计数 → MySQL 幂等落库 → Retry/DLQ。为了让第一版能够真正编译、运行和测试，本章暂不实现多级 Retry Queue、Redis Cluster、按 vid 多队列分区、管理后台 DLQ 重放和复杂的 Confirm 结算状态机；这些生产增强仍按前文阶段逐步补充。

### 22.1 本章实施边界

本章代码采用以下明确约束：

1. 消费者第一版固定 `concurrency = 1`，保证同一队列内的事件按顺序处理；
2. 每个视频使用数据库 `video_stats_sequence` 生成连续序号；
3. `video_status` 增加 `applied_sequence`，作为 Redis 丢失后的重建基线；
4. Redis Hash 使用 `video:stats:v2:{vid}`，不再使用短 TTL；
5. Lua 同时更新 Hash、热门 ZSet、最后序号和 eventId 幂等 Key；
6. Outbox Relay 使用同步等待 Publisher Confirm 的实现，代码简单但吞吐有限；
7. Retry/DLQ 转发也同步等待 Confirm，确认成功后才 ACK 原消息；
8. 当前同步更新 `video_status` 的代码在完成联调前先由功能开关保留，切流后再删除。

### 22.2 数据库迁移 SQL

建议新建迁移文件：

```text
database/migration/V20260718__video_stats_outbox.sql
```

完整 SQL：

```sql
ALTER TABLE video_status
    MODIFY play_times BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '播放次数',
    MODIFY like_times BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞次数',
    MODIFY unlike_times BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点踩次数',
    MODIFY comment_times BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论次数',
    MODIFY coin_times BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '投币次数',
    MODIFY share_times BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '分享次数',
    MODIFY collect_times BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '收藏次数',
    MODIFY danmu_times BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '弹幕次数',
    ADD COLUMN applied_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '已经持久化的最后统计事件序号';

CREATE TABLE video_stats_sequence (
    vid INT NOT NULL,
    last_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (vid),
    CONSTRAINT fk_video_stats_sequence_vid
        FOREIGN KEY (vid) REFERENCES video (vid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='视频统计聚合序号';

CREATE TABLE video_stats_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    aggregate_id INT NOT NULL COMMENT '视频ID',
    aggregate_sequence BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload JSON NOT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=PENDING,1=SENDING,2=SENT,3=FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NULL,
    sending_at DATETIME(3) NULL,
    lease_token VARCHAR(64) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_stats_outbox_event_id (event_id),
    UNIQUE KEY uk_video_stats_outbox_sequence (aggregate_id, aggregate_sequence),
    KEY idx_video_stats_outbox_poll (status, next_retry_at, sending_at, id),
    CONSTRAINT fk_video_stats_outbox_vid
        FOREIGN KEY (aggregate_id) REFERENCES video (vid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='视频统计Outbox';

CREATE TABLE video_stats_consumed_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    vid INT NOT NULL,
    aggregate_sequence BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    delta BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    process_status TINYINT NOT NULL DEFAULT 0
        COMMENT '0=RECEIVED,1=MYSQL_COMMITTED,2=REPAIR_REQUIRED',
    last_error VARCHAR(1000) NULL,
    consumed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    committed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_stats_consumed_event_id (event_id),
    UNIQUE KEY uk_video_stats_consumed_sequence (vid, aggregate_sequence),
    KEY idx_video_stats_consumed_repair (process_status, id),
    CONSTRAINT fk_video_stats_consumed_vid
        FOREIGN KEY (vid) REFERENCES video (vid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='视频统计消费幂等记录';

INSERT INTO video_stats_sequence (vid, last_sequence)
SELECT vid, applied_sequence
FROM video_status
ON DUPLICATE KEY UPDATE
    last_sequence = GREATEST(last_sequence, VALUES(last_sequence));
```

> 如果当前阶段不准备把 `video_status` 的 `INT` 改成 `BIGINT`，Java 代码中的统计字段可暂时保持 `Integer`；但事件 delta、序号和 SQL 运算仍建议使用 `long/Long`，并在上线前完成容量评估。

### 22.3 Maven 依赖和应用配置

在 `pom.xml` 的 `<dependencies>` 中增加：

```xml
<!-- RabbitMQ -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

在 `application.yml` 中增加，密码使用环境变量，不要继续提交真实密钥：

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:127.0.0.1}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
    listener:
      simple:
        acknowledge-mode: manual
        prefetch: 20
        default-requeue-rejected: false

app:
  video-stats:
    async-enabled: false
    redis-event-ttl-days: 30
    outbox-batch-size: 100
    outbox-fixed-delay-ms: 1000
    outbox-lease-seconds: 60
    publish-confirm-timeout-seconds: 5
    consumer-max-retries: 5
```

本地 `compose.yaml` 可以增加 RabbitMQ：

```yaml
services:
  rabbitmq:
    image: rabbitmq:4-management-alpine
    container_name: feibijiubi-rabbitmq
    restart: unless-stopped
    ports:
      - "127.0.0.1:5672:5672"
      - "127.0.0.1:15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest
    volumes:
      - rabbitmq-data:/var/lib/rabbitmq

volumes:
  rabbitmq-data:
```

### 22.4 配置属性和常量

文件：`config/properties/VideoStatsProperties.java`

```java
package com.feibijiubi.backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.video-stats")
public class VideoStatsProperties {
    private boolean asyncEnabled;
    private int redisEventTtlDays = 30;
    private int outboxBatchSize = 100;
    private long outboxFixedDelayMs = 1000;
    private int outboxLeaseSeconds = 60;
    private int publishConfirmTimeoutSeconds = 5;
    private int consumerMaxRetries = 5;
}
```

文件：`constants/VideoStatsRabbitConstants.java`

```java
package com.feibijiubi.backend.constants;

public final class VideoStatsRabbitConstants {
    public static final String MAIN_EXCHANGE = "video.stats.exchange.v1";
    public static final String MAIN_ROUTING_KEY = "video.stats.changed.v1";
    public static final String MAIN_QUEUE = "video.stats.persist.queue.v1";

    public static final String RETRY_EXCHANGE = "video.stats.retry.exchange.v1";
    public static final String RETRY_ROUTING_KEY = "video.stats.retry.v1";
    public static final String RETRY_QUEUE = "video.stats.retry.queue.v1";

    public static final String DEAD_EXCHANGE = "video.stats.dlx.v1";
    public static final String DEAD_ROUTING_KEY = "video.stats.dead.v1";
    public static final String DEAD_QUEUE = "video.stats.dlq.v1";

    private VideoStatsRabbitConstants() {
    }
}
```

文件：`constants/VideoStatsRedisConstants.java`

```java
package com.feibijiubi.backend.constants;

public final class VideoStatsRedisConstants {
    public static final String STATS_PREFIX = "video:stats:v2:";
    public static final String PROCESSED_PREFIX = "video:stats:processed:v1:";
    public static final String HOT_VIDEOS_KEY = "feed:hot:videos:v2";

    public static String statsKey(Integer vid) {
        return STATS_PREFIX + vid;
    }

    public static String processedKey(String eventId) {
        return PROCESSED_PREFIX + eventId;
    }

    private VideoStatsRedisConstants() {
    }
}
```

### 22.5 RabbitMQ 拓扑配置

文件：`config/rabbitmq/VideoStatsRabbitConfig.java`

```java
package com.feibijiubi.backend.config.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.config.properties.VideoStatsProperties;
import com.feibijiubi.backend.constants.VideoStatsRabbitConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.AcknowledgeMode;

@Configuration
@EnableConfigurationProperties(VideoStatsProperties.class)
public class VideoStatsRabbitConfig {

    @Bean
    public DirectExchange videoStatsMainExchange() {
        return new DirectExchange(
                VideoStatsRabbitConstants.MAIN_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    public Queue videoStatsMainQueue() {
        return QueueBuilder.durable(VideoStatsRabbitConstants.MAIN_QUEUE)
                .deadLetterExchange(VideoStatsRabbitConstants.DEAD_EXCHANGE)
                .deadLetterRoutingKey(VideoStatsRabbitConstants.DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding videoStatsMainBinding() {
        return BindingBuilder.bind(videoStatsMainQueue())
                .to(videoStatsMainExchange())
                .with(VideoStatsRabbitConstants.MAIN_ROUTING_KEY);
    }

    @Bean
    public DirectExchange videoStatsRetryExchange() {
        return new DirectExchange(
                VideoStatsRabbitConstants.RETRY_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    public Queue videoStatsRetryQueue() {
        return QueueBuilder.durable(VideoStatsRabbitConstants.RETRY_QUEUE)
                .ttl(10_000)
                .deadLetterExchange(VideoStatsRabbitConstants.MAIN_EXCHANGE)
                .deadLetterRoutingKey(VideoStatsRabbitConstants.MAIN_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding videoStatsRetryBinding() {
        return BindingBuilder.bind(videoStatsRetryQueue())
                .to(videoStatsRetryExchange())
                .with(VideoStatsRabbitConstants.RETRY_ROUTING_KEY);
    }

    @Bean
    public DirectExchange videoStatsDeadExchange() {
        return new DirectExchange(
                VideoStatsRabbitConstants.DEAD_EXCHANGE,
                true,
                false
        );
    }

    @Bean
    public Queue videoStatsDeadQueue() {
        return QueueBuilder.durable(VideoStatsRabbitConstants.DEAD_QUEUE)
                .build();
    }

    @Bean
    public Binding videoStatsDeadBinding() {
        return BindingBuilder.bind(videoStatsDeadQueue())
                .to(videoStatsDeadExchange())
                .with(VideoStatsRabbitConstants.DEAD_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory
    videoStatsListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter rabbitMessageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(20);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
```

> Spring AMQP 新版本可能提示 `Jackson2JsonMessageConverter` 未来弃用。第一版可先沿用 Spring Boot 3.5 可用的转换器；升级 Spring AMQP 大版本时再统一切换新 Jackson 3 转换器，不要在同一次统计改造中混入框架大版本迁移。

### 22.6 事件模型和热度权重

文件：`event/video/VideoStatsEventType.java`

```java
package com.feibijiubi.backend.event.video;

public enum VideoStatsEventType {
    PLAY("playTimes", 1.0),
    LIKE("likeTimes", 1.5),
    UNLIKE("unlikeTimes", -1.0),
    COMMENT("commentTimes", 3.5),
    COIN("coinTimes", 4.0),
    SHARE("shareTimes", 2.5),
    COLLECT("collectTimes", 4.0),
    DANMU("danmuTimes", 2.0);

    private final String redisField;
    private final double scorePerUnit;

    VideoStatsEventType(String redisField, double scorePerUnit) {
        this.redisField = redisField;
        this.scorePerUnit = scorePerUnit;
    }

    public String redisField() {
        return redisField;
    }

    public double calculateHotScore(long delta) {
        return scorePerUnit * delta;
    }
}
```

注意 `UNLIKE` 的规则：点踩 `delta=1` 时热门分数减少，取消点踩 `delta=-1` 时热门分数恢复，因此不能在调用处再次手工取反。

文件：`event/video/VideoStatsChangedEvent.java`

```java
package com.feibijiubi.backend.event.video;

import java.time.LocalDateTime;

public record VideoStatsChangedEvent(
        String eventId,
        Integer vid,
        Long aggregateSequence,
        VideoStatsEventType type,
        Long delta,
        Double hotScoreDelta,
        LocalDateTime occurredAt,
        Integer schemaVersion,
        String traceId
) {
    public void validate() {
        if (eventId == null || eventId.isBlank() || eventId.length() > 64) {
            throw new IllegalArgumentException("eventId 不合法");
        }
        if (vid == null || vid <= 0) {
            throw new IllegalArgumentException("vid 不合法");
        }
        if (aggregateSequence == null || aggregateSequence <= 0) {
            throw new IllegalArgumentException("aggregateSequence 不合法");
        }
        if (type == null || delta == null || delta == 0) {
            throw new IllegalArgumentException("统计类型或增量不合法");
        }
        if (hotScoreDelta == null || !Double.isFinite(hotScoreDelta)) {
            throw new IllegalArgumentException("热门分数增量不合法");
        }
        if (occurredAt == null || schemaVersion == null || schemaVersion != 1) {
            throw new IllegalArgumentException("消息版本或发生时间不合法");
        }
    }
}
```

文件：`event/video/VideoStatsDelta.java`

```java
package com.feibijiubi.backend.event.video;

public record VideoStatsDelta(
        long playDelta,
        long likeDelta,
        long unlikeDelta,
        long commentDelta,
        long coinDelta,
        long shareDelta,
        long collectDelta,
        long danmuDelta
) {
    public static VideoStatsDelta from(VideoStatsChangedEvent event) {
        long delta = event.delta();
        return switch (event.type()) {
            case PLAY -> new VideoStatsDelta(delta, 0, 0, 0, 0, 0, 0, 0);
            case LIKE -> new VideoStatsDelta(0, delta, 0, 0, 0, 0, 0, 0);
            case UNLIKE -> new VideoStatsDelta(0, 0, delta, 0, 0, 0, 0, 0);
            case COMMENT -> new VideoStatsDelta(0, 0, 0, delta, 0, 0, 0, 0);
            case COIN -> new VideoStatsDelta(0, 0, 0, 0, delta, 0, 0, 0);
            case SHARE -> new VideoStatsDelta(0, 0, 0, 0, 0, delta, 0, 0);
            case COLLECT -> new VideoStatsDelta(0, 0, 0, 0, 0, 0, delta, 0);
            case DANMU -> new VideoStatsDelta(0, 0, 0, 0, 0, 0, 0, delta);
        };
    }
}
```

### 22.7 Outbox 实体与 Mapper

文件：`entity/VideoStatsOutbox.java`

```java
package com.feibijiubi.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoStatsOutbox {
    private Long id;
    private String eventId;
    private Integer aggregateId;
    private Long aggregateSequence;
    private String eventType;
    private String payload;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime sendingAt;
    private String leaseToken;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
```

文件：`mapper/VideoStatsSequenceMapper.java`

```java
package com.feibijiubi.backend.mapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VideoStatsSequenceMapper {
    int ensureExists(Integer vid);

    int increase(Integer vid);

    Long selectCurrent(Integer vid);
}
```

文件：`mapper/VideoStatsOutboxMapper.java`

```java
package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoStatsOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VideoStatsOutboxMapper {
    int insert(VideoStatsOutbox outbox);

    List<VideoStatsOutbox> selectPendingForUpdate(
            @Param("limit") int limit,
            @Param("now") LocalDateTime now
    );

    int markSending(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("sendingAt") LocalDateTime sendingAt
    );

    int markSent(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("sentAt") LocalDateTime sentAt
    );

    int markPending(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("lastError") String lastError
    );

    int markFailed(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("lastError") String lastError
    );

    int recoverExpiredSending(
            @Param("expiredBefore") LocalDateTime expiredBefore
    );
}
```

文件：`resources/com/feibijiubi/backend/mapper/VideoStatsSequenceMapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper
        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.feibijiubi.backend.mapper.VideoStatsSequenceMapper">
    <insert id="ensureExists">
        INSERT INTO video_stats_sequence(vid, last_sequence)
        VALUES (#{vid}, 0)
        ON DUPLICATE KEY UPDATE vid = VALUES(vid)
    </insert>

    <update id="increase">
        UPDATE video_stats_sequence
        SET last_sequence = last_sequence + 1
        WHERE vid = #{vid}
    </update>

    <select id="selectCurrent" resultType="java.lang.Long">
        SELECT last_sequence
        FROM video_stats_sequence
        WHERE vid = #{vid}
    </select>
</mapper>
```

文件：`resources/com/feibijiubi/backend/mapper/VideoStatsOutboxMapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper
        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.feibijiubi.backend.mapper.VideoStatsOutboxMapper">
    <insert id="insert" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO video_stats_outbox(
            event_id,
            aggregate_id,
            aggregate_sequence,
            event_type,
            payload,
            status,
            retry_count,
            created_at
        ) VALUES (
            #{eventId},
            #{aggregateId},
            #{aggregateSequence},
            #{eventType},
            #{payload},
            0,
            0,
            NOW(3)
        )
    </insert>

    <select id="selectPendingForUpdate"
            resultType="com.feibijiubi.backend.entity.VideoStatsOutbox">
        SELECT id,
               event_id,
               aggregate_id,
               aggregate_sequence,
               event_type,
               payload,
               status,
               retry_count,
               next_retry_at,
               sending_at,
               lease_token,
               last_error,
               created_at,
               sent_at
        FROM video_stats_outbox
        WHERE status = 0
          AND (next_retry_at IS NULL OR next_retry_at &lt;= #{now})
        ORDER BY id
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
    </select>

    <update id="markSending">
        UPDATE video_stats_outbox
        SET status = 1,
            sending_at = #{sendingAt},
            lease_token = #{leaseToken}
        WHERE id = #{id}
          AND status = 0
    </update>

    <update id="markSent">
        UPDATE video_stats_outbox
        SET status = 2,
            sent_at = #{sentAt},
            sending_at = NULL,
            lease_token = NULL,
            last_error = NULL
        WHERE event_id = #{eventId}
          AND status = 1
          AND lease_token = #{leaseToken}
    </update>

    <update id="markPending">
        UPDATE video_stats_outbox
        SET status = 0,
            retry_count = retry_count + 1,
            next_retry_at = #{nextRetryAt},
            sending_at = NULL,
            lease_token = NULL,
            last_error = #{lastError}
        WHERE event_id = #{eventId}
          AND status = 1
          AND lease_token = #{leaseToken}
    </update>

    <update id="markFailed">
        UPDATE video_stats_outbox
        SET status = 3,
            retry_count = retry_count + 1,
            sending_at = NULL,
            lease_token = NULL,
            last_error = #{lastError}
        WHERE event_id = #{eventId}
          AND status = 1
          AND lease_token = #{leaseToken}
    </update>

    <update id="recoverExpiredSending">
        UPDATE video_stats_outbox
        SET status = 0,
            retry_count = retry_count + 1,
            next_retry_at = NOW(3),
            sending_at = NULL,
            lease_token = NULL,
            last_error = '发送租约超时，已自动恢复'
        WHERE status = 1
          AND sending_at &lt; #{expiredBefore}
    </update>
</mapper>
```

`FOR UPDATE SKIP LOCKED` 必须在事务中执行。不要在 Mapper 查询后立即结束事务、再逐条抢占，否则多实例仍可能同时拿到同一批数据。

### 22.8 事务内创建统计事件

文件：`service/video/VideoStatsOutboxService.java`

```java
package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.event.video.VideoStatsChangedEvent;
import com.feibijiubi.backend.event.video.VideoStatsEventType;

public interface VideoStatsOutboxService {
    VideoStatsChangedEvent createEvent(
            Integer vid,
            VideoStatsEventType type,
            long delta
    );
}
```

文件：`service/impl/video/VideoStatsOutboxServiceImpl.java`

```java
package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.entity.VideoStatsOutbox;
import com.feibijiubi.backend.event.video.VideoStatsChangedEvent;
import com.feibijiubi.backend.event.video.VideoStatsEventType;
import com.feibijiubi.backend.mapper.VideoStatsOutboxMapper;
import com.feibijiubi.backend.mapper.VideoStatsSequenceMapper;
import com.feibijiubi.backend.service.video.VideoStatsOutboxService;
import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoStatsOutboxServiceImpl
        implements VideoStatsOutboxService {

    private final VideoStatsSequenceMapper sequenceMapper;
    private final VideoStatsOutboxMapper outboxMapper;
    private final JsonUtils jsonUtils;

    @Override
    @Transactional(
            propagation = Propagation.MANDATORY,
            rollbackFor = Exception.class
    )
    public VideoStatsChangedEvent createEvent(
            Integer vid,
            VideoStatsEventType type,
            long delta
    ) {
        if (vid == null || vid <= 0 || type == null || delta == 0) {
            throw new BusinessException(400, "视频统计事件参数不合法");
        }

        sequenceMapper.ensureExists(vid);
        if (sequenceMapper.increase(vid) != 1) {
            throw new BusinessException(500, "视频统计序号生成失败");
        }

        Long sequence = sequenceMapper.selectCurrent(vid);
        if (sequence == null || sequence <= 0) {
            throw new BusinessException(500, "视频统计序号数据异常");
        }

        String eventId = UUID.randomUUID().toString();
        VideoStatsChangedEvent event = new VideoStatsChangedEvent(
                eventId,
                vid,
                sequence,
                type,
                delta,
                type.calculateHotScore(delta),
                LocalDateTime.now(),
                1,
                null
        );

        VideoStatsOutbox outbox = new VideoStatsOutbox();
        outbox.setEventId(event.eventId());
        outbox.setAggregateId(event.vid());
        outbox.setAggregateSequence(event.aggregateSequence());
        outbox.setEventType(event.type().name());
        outbox.setPayload(jsonUtils.toJson(event));

        if (outboxMapper.insert(outbox) != 1) {
            throw new BusinessException(500, "视频统计事件创建失败");
        }
        return event;
    }
}
```

`Propagation.MANDATORY` 是有意设计：它强制调用方必须已经处于 MySQL 事务中，防止开发者误把业务事实和 Outbox 拆成两个事务。播放、分享这种原本没有事务的方法，接入时必须补 `@Transactional`。

### 22.9 Redis 初始化和 Lua 原子计数

文件：`resources/lua/video-stats-increment.lua`

```lua
-- KEYS[1] video:stats:v2:{vid}
-- KEYS[2] feed:hot:videos:v2
-- KEYS[3] video:stats:processed:v1:{eventId}
-- ARGV[1] field
-- ARGV[2] delta
-- ARGV[3] vid member
-- ARGV[4] hot score delta
-- ARGV[5] event ttl seconds
-- ARGV[6] aggregate sequence

if redis.call('EXISTS', KEYS[3]) == 1 then
    return 'DUPLICATE'
end

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 'NEEDS_REBUILD'
end

local allowed = {
    playTimes = true,
    likeTimes = true,
    unlikeTimes = true,
    commentTimes = true,
    coinTimes = true,
    shareTimes = true,
    collectTimes = true,
    danmuTimes = true
}

local field = ARGV[1]
if allowed[field] ~= true then
    return 'INVALID_FIELD'
end

local current = tonumber(redis.call('HGET', KEYS[1], field))
local delta = tonumber(ARGV[2])
local incomingSequence = tonumber(ARGV[6])
local lastSequence = tonumber(redis.call('HGET', KEYS[1], 'lastSequence'))

if current == nil or delta == nil
        or incomingSequence == nil or lastSequence == nil then
    return 'NEEDS_REBUILD'
end

if incomingSequence <= lastSequence then
    return 'OLD_SEQUENCE'
end

if incomingSequence ~= lastSequence + 1 then
    return 'SEQUENCE_GAP'
end

if current + delta < 0 then
    return 'NEGATIVE_RESULT'
end

redis.call('HINCRBY', KEYS[1], field, delta)
redis.call('HSET', KEYS[1], 'lastSequence', incomingSequence)
redis.call('ZINCRBY', KEYS[2], ARGV[4], ARGV[3])
redis.call('SET', KEYS[3], '1', 'EX', ARGV[5])
return 'APPLIED'
```

文件：`service/video/VideoStatsCounterService.java`

```java
package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.event.video.VideoStatsChangedEvent;

public interface VideoStatsCounterService {
    ApplyResult apply(VideoStatsChangedEvent event);

    enum ApplyResult {
        APPLIED,
        DUPLICATE,
        NEEDS_REBUILD,
        OLD_SEQUENCE,
        SEQUENCE_GAP,
        NEGATIVE_RESULT,
        INVALID_FIELD
    }
}
```

文件：`service/video/VideoStatsRebuildService.java`

```java
package com.feibijiubi.backend.service.video;

public interface VideoStatsRebuildService {
    void rebuild(Integer vid);
}
```

文件：`service/impl/video/VideoStatsRebuildServiceImpl.java`

```java
package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.constants.VideoStatsRedisConstants;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.video.VideoStatsRebuildService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VideoStatsRebuildServiceImpl
        implements VideoStatsRebuildService {

    private final VideoStatusMapper videoStatusMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void rebuild(Integer vid) {
        VideoStatus status = videoStatusMapper.selectByVid(vid);
        if (status == null) {
            throw new BusinessException(500, "视频统计数据不存在");
        }

        String key = VideoStatsRedisConstants.statsKey(vid);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("vid", String.valueOf(status.getVid()));
        values.put("playTimes", String.valueOf(status.getPlayTimes()));
        values.put("likeTimes", String.valueOf(status.getLikeTimes()));
        values.put("unlikeTimes", String.valueOf(status.getUnlikeTimes()));
        values.put("commentTimes", String.valueOf(status.getCommentTimes()));
        values.put("coinTimes", String.valueOf(status.getCoinTimes()));
        values.put("shareTimes", String.valueOf(status.getShareTimes()));
        values.put("collectTimes", String.valueOf(status.getCollectTimes()));
        values.put("danmuTimes", String.valueOf(status.getDanmuTimes()));
        values.put("lastSequence", String.valueOf(status.getAppliedSequence()));

        redisTemplate.opsForHash().putAll(key, values);
        redisTemplate.opsForZSet().add(
                VideoStatsRedisConstants.HOT_VIDEOS_KEY,
                String.valueOf(vid),
                calculateBaseScore(status)
        );
    }

    private double calculateBaseScore(VideoStatus status) {
        return status.getPlayTimes()
                + status.getLikeTimes() * 1.5
                - status.getUnlikeTimes()
                + status.getCommentTimes() * 3.5
                + status.getCoinTimes() * 4.0
                + status.getShareTimes() * 2.5
                + status.getCollectTimes() * 4.0
                + status.getDanmuTimes() * 2.0;
    }
}
```

为配合上面的代码，`VideoStatus` 增加：

```java
private Long appliedSequence;
```

如果统计字段已升级为 BIGINT，还应将八个统计字段全部由 `Integer` 改成 `Long`。

第一版重建代码必须只由单消费者调用。多消费者或多实例部署前，需要按前文加入 per-vid 锁、临时 Key 和 fencing token，不能把此简单重建实现直接用于并发生产环境。

文件：`service/impl/video/VideoStatsCounterServiceImpl.java`

```java
package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.properties.VideoStatsProperties;
import com.feibijiubi.backend.constants.VideoStatsRedisConstants;
import com.feibijiubi.backend.event.video.VideoStatsChangedEvent;
import com.feibijiubi.backend.service.video.VideoStatsCounterService;
import com.feibijiubi.backend.service.video.VideoStatsRebuildService;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VideoStatsCounterServiceImpl
        implements VideoStatsCounterService {

    private final RedisUtils redisUtils;
    private final VideoStatsRebuildService rebuildService;
    private final VideoStatsProperties properties;
    private final DefaultRedisScript<String> incrementScript;

    public VideoStatsCounterServiceImpl(
            RedisUtils redisUtils,
            VideoStatsRebuildService rebuildService,
            VideoStatsProperties properties
    ) {
        this.redisUtils = redisUtils;
        this.rebuildService = rebuildService;
        this.properties = properties;
        this.incrementScript = new DefaultRedisScript<>();
        this.incrementScript.setLocation(
                new ClassPathResource("lua/video-stats-increment.lua")
        );
        this.incrementScript.setResultType(String.class);
    }

    @Override
    public ApplyResult apply(VideoStatsChangedEvent event) {
        event.validate();

        ApplyResult result = execute(event);
        if (result != ApplyResult.NEEDS_REBUILD) {
            return result;
        }

        rebuildService.rebuild(event.vid());
        result = execute(event);
        if (result == ApplyResult.NEEDS_REBUILD) {
            throw new BusinessException(500, "Redis 视频统计初始化失败");
        }
        return result;
    }

    private ApplyResult execute(VideoStatsChangedEvent event) {
        long ttlSeconds = properties.getRedisEventTtlDays() * 86_400L;
        String result = redisUtils.executeScript(
                incrementScript,
                List.of(
                        VideoStatsRedisConstants.statsKey(event.vid()),
                        VideoStatsRedisConstants.HOT_VIDEOS_KEY,
                        VideoStatsRedisConstants.processedKey(event.eventId())
                ),
                event.type().redisField(),
                String.valueOf(event.delta()),
                String.valueOf(event.vid()),
                String.valueOf(event.hotScoreDelta()),
                String.valueOf(ttlSeconds),
                String.valueOf(event.aggregateSequence())
        );

        if (result == null) {
            throw new BusinessException(500, "Redis 视频统计脚本无返回值");
        }
        try {
            return ApplyResult.valueOf(result);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(500, "未知 Redis 统计结果: " + result);
        }
    }
}
```

### 22.10 MySQL 固定字段增量和消费幂等

文件：`entity/VideoStatsConsumedEvent.java`

```java
package com.feibijiubi.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoStatsConsumedEvent {
    private Long id;
    private String eventId;
    private Integer vid;
    private Long aggregateSequence;
    private String eventType;
    private Long delta;
    private String payloadHash;
    private Integer processStatus;
    private String lastError;
    private LocalDateTime consumedAt;
    private LocalDateTime committedAt;
}
```

文件：`mapper/VideoStatsConsumedEventMapper.java`

```java
package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoStatsConsumedEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VideoStatsConsumedEventMapper {
    int insertReceived(VideoStatsConsumedEvent event);

    VideoStatsConsumedEvent selectByEventId(String eventId);

    int markCommitted(String eventId);

    int markRepairRequired(
            @Param("eventId") String eventId,
            @Param("lastError") String lastError
    );
}
```

在当前 `VideoStatusMapper` 增加：

```java
int applyDelta(
        @Param("vid") Integer vid,
        @Param("sequence") Long sequence,
        @Param("delta") VideoStatsDelta delta
);
```

`VideoStatusMapper.xml` 增加：

```xml
<update id="applyDelta">
    UPDATE video_status
    SET play_times = play_times + #{delta.playDelta},
        like_times = like_times + #{delta.likeDelta},
        unlike_times = unlike_times + #{delta.unlikeDelta},
        comment_times = comment_times + #{delta.commentDelta},
        coin_times = coin_times + #{delta.coinDelta},
        share_times = share_times + #{delta.shareDelta},
        collect_times = collect_times + #{delta.collectDelta},
        danmu_times = danmu_times + #{delta.danmuDelta},
        applied_sequence = #{sequence}
    WHERE vid = #{vid}
      AND applied_sequence + 1 = #{sequence}
      AND play_times + #{delta.playDelta} &gt;= 0
      AND like_times + #{delta.likeDelta} &gt;= 0
      AND unlike_times + #{delta.unlikeDelta} &gt;= 0
      AND comment_times + #{delta.commentDelta} &gt;= 0
      AND coin_times + #{delta.coinDelta} &gt;= 0
      AND share_times + #{delta.shareDelta} &gt;= 0
      AND collect_times + #{delta.collectDelta} &gt;= 0
      AND danmu_times + #{delta.danmuDelta} &gt;= 0
</update>
```

文件：`resources/com/feibijiubi/backend/mapper/VideoStatsConsumedEventMapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper
        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.feibijiubi.backend.mapper.VideoStatsConsumedEventMapper">
    <insert id="insertReceived">
        INSERT INTO video_stats_consumed_event(
            event_id,
            vid,
            aggregate_sequence,
            event_type,
            delta,
            payload_hash,
            process_status,
            consumed_at
        ) VALUES (
            #{eventId},
            #{vid},
            #{aggregateSequence},
            #{eventType},
            #{delta},
            #{payloadHash},
            0,
            NOW(3)
        )
    </insert>

    <select id="selectByEventId"
            resultType="com.feibijiubi.backend.entity.VideoStatsConsumedEvent">
        SELECT id,
               event_id,
               vid,
               aggregate_sequence,
               event_type,
               delta,
               payload_hash,
               process_status,
               last_error,
               consumed_at,
               committed_at
        FROM video_stats_consumed_event
        WHERE event_id = #{eventId}
    </select>

    <update id="markCommitted">
        UPDATE video_stats_consumed_event
        SET process_status = 1,
            committed_at = NOW(3),
            last_error = NULL
        WHERE event_id = #{eventId}
          AND process_status != 1
    </update>

    <update id="markRepairRequired">
        UPDATE video_stats_consumed_event
        SET process_status = 2,
            last_error = #{lastError}
        WHERE event_id = #{eventId}
          AND process_status != 1
    </update>
</mapper>
```

文件：`service/video/VideoStatsPersistenceService.java`

```java
package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.event.video.VideoStatsChangedEvent;

public interface VideoStatsPersistenceService {
    void persist(VideoStatsChangedEvent event, String payloadHash);
}
```

文件：`service/impl/video/VideoStatsPersistenceServiceImpl.java`

```java
package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.entity.VideoStatsConsumedEvent;
import com.feibijiubi.backend.event.video.VideoStatsChangedEvent;
import com.feibijiubi.backend.event.video.VideoStatsDelta;
import com.feibijiubi.backend.mapper.VideoStatsConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.video.VideoStatsPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VideoStatsPersistenceServiceImpl
        implements VideoStatsPersistenceService {

    private final VideoStatsConsumedEventMapper consumedEventMapper;
    private final VideoStatusMapper videoStatusMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persist(
            VideoStatsChangedEvent event,
            String payloadHash
    ) {
        VideoStatsConsumedEvent consumed = new VideoStatsConsumedEvent();
        consumed.setEventId(event.eventId());
        consumed.setVid(event.vid());
        consumed.setAggregateSequence(event.aggregateSequence());
        consumed.setEventType(event.type().name());
        consumed.setDelta(event.delta());
        consumed.setPayloadHash(payloadHash);

        try {
            consumedEventMapper.insertReceived(consumed);
        } catch (DuplicateKeyException e) {
            handleDuplicate(event, payloadHash);
            return;
        }

        VideoStatsDelta delta = VideoStatsDelta.from(event);
        int rows = videoStatusMapper.applyDelta(
                event.vid(),
                event.aggregateSequence(),
                delta
        );
        if (rows != 1) {
            throw new BusinessException(
                    500,
                    "视频统计落库失败，可能存在序号缺口或负数结果"
            );
        }

        if (consumedEventMapper.markCommitted(event.eventId()) != 1) {
            throw new BusinessException(500, "视频统计消费状态更新失败");
        }
    }

    private void handleDuplicate(
            VideoStatsChangedEvent event,
            String payloadHash
    ) {
        VideoStatsConsumedEvent existing =
                consumedEventMapper.selectByEventId(event.eventId());
        if (existing == null) {
            throw new BusinessException(500, "消费幂等记录查询失败");
        }

        boolean samePayload = existing.getVid().equals(event.vid())
                && existing.getAggregateSequence()
                .equals(event.aggregateSequence())
                && existing.getEventType().equals(event.type().name())
                && existing.getDelta().equals(event.delta())
                && existing.getPayloadHash().equals(payloadHash);

        if (!samePayload) {
            throw new IllegalArgumentException(
                    "相同 eventId 对应了不同统计消息"
            );
        }
        if (Integer.valueOf(1).equals(existing.getProcessStatus())) {
            return;
        }

        throw new BusinessException(
                500,
                "统计事件存在未完成消费记录，需要继续修复"
        );
    }
}
```

这里不能简单在捕获 `DuplicateKeyException` 后直接返回。只有 payload 完全相同且 `process_status=MYSQL_COMMITTED` 才能视为成功重复。

### 22.11 Outbox 抢占和可靠发布

文件：`mq/video/VideoStatsOutboxClaimService.java`

```java
package com.feibijiubi.backend.mq.video;

import com.feibijiubi.backend.config.properties.VideoStatsProperties;
import com.feibijiubi.backend.entity.VideoStatsOutbox;
import com.feibijiubi.backend.mapper.VideoStatsOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoStatsOutboxClaimService {

    private final VideoStatsOutboxMapper outboxMapper;
    private final VideoStatsProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public List<VideoStatsOutbox> claimBatch() {
        LocalDateTime now = LocalDateTime.now();
        List<VideoStatsOutbox> pending =
                outboxMapper.selectPendingForUpdate(
                        properties.getOutboxBatchSize(),
                        now
                );

        List<VideoStatsOutbox> claimed = new ArrayList<>();
        for (VideoStatsOutbox outbox : pending) {
            String leaseToken = UUID.randomUUID().toString();
            int rows = outboxMapper.markSending(
                    outbox.getId(),
                    leaseToken,
                    now
            );
            if (rows == 1) {
                outbox.setLeaseToken(leaseToken);
                outbox.setSendingAt(now);
                claimed.add(outbox);
            }
        }
        return claimed;
    }
}
```

文件：`mq/video/VideoStatsEventPublisher.java`

```java
package com.feibijiubi.backend.mq.video;

import com.feibijiubi.backend.config.properties.VideoStatsProperties;
import com.feibijiubi.backend.constants.VideoStatsRabbitConstants;
import com.feibijiubi.backend.event.video.VideoStatsChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class VideoStatsEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final VideoStatsProperties properties;

    public void publish(VideoStatsChangedEvent event, String attemptId)
            throws Exception {
        CorrelationData correlationData = new CorrelationData(
                event.eventId() + ":" + attemptId
        );

        rabbitTemplate.convertAndSend(
                VideoStatsRabbitConstants.MAIN_EXCHANGE,
                VideoStatsRabbitConstants.MAIN_ROUTING_KEY,
                event,
                message -> {
                    message.getMessageProperties()
                            .setMessageId(event.eventId());
                    message.getMessageProperties()
                            .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties()
                            .setContentType("application/json");
                    return message;
                },
                correlationData
        );

        CorrelationData.Confirm confirm = correlationData.getFuture().get(
                properties.getPublishConfirmTimeoutSeconds(),
                TimeUnit.SECONDS
        );
        if (!confirm.isAck()) {
            throw new IllegalStateException(
                    "RabbitMQ NACK: " + confirm.getReason()
            );
        }
        if (correlationData.getReturned() != null) {
            throw new IllegalStateException(
                    "RabbitMQ 消息未路由到队列: "
                            + correlationData.getReturned().getReplyText()
            );
        }
    }
}
```

文件：`mq/video/VideoStatsOutboxRelay.java`

```java
package com.feibijiubi.backend.mq.video;

import com.feibijiubi.backend.config.properties.VideoStatsProperties;
import com.feibijiubi.backend.entity.VideoStatsOutbox;
import com.feibijiubi.backend.event.video.VideoStatsChangedEvent;
import com.feibijiubi.backend.mapper.VideoStatsOutboxMapper;
import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatsOutboxRelay {

    private static final int MAX_PUBLISH_RETRIES = 10;

    private final VideoStatsOutboxClaimService claimService;
    private final VideoStatsOutboxMapper outboxMapper;
    private final VideoStatsEventPublisher publisher;
    private final VideoStatsProperties properties;
    private final JsonUtils jsonUtils;

    @Scheduled(
            fixedDelayString =
                    "${app.video-stats.outbox-fixed-delay-ms:1000}"
    )
    public void relay() {
        if (!properties.isAsyncEnabled()) {
            return;
        }

        recoverExpiredLease();
        List<VideoStatsOutbox> claimed = claimService.claimBatch();
        for (VideoStatsOutbox outbox : claimed) {
            publishOne(outbox);
        }
    }

    private void publishOne(VideoStatsOutbox outbox) {
        try {
            VideoStatsChangedEvent event = jsonUtils.fromJson(
                    outbox.getPayload(),
                    VideoStatsChangedEvent.class
            );
            publisher.publish(event, outbox.getLeaseToken());
            outboxMapper.markSent(
                    outbox.getEventId(),
                    outbox.getLeaseToken(),
                    LocalDateTime.now()
            );
        } catch (Exception e) {
            String error = truncate(e.getMessage());
            if (outbox.getRetryCount() + 1 >= MAX_PUBLISH_RETRIES) {
                outboxMapper.markFailed(
                        outbox.getEventId(),
                        outbox.getLeaseToken(),
                        error
                );
                log.error("视频统计 Outbox 发布最终失败, eventId={}",
                        outbox.getEventId(), e);
                return;
            }

            long delaySeconds = Math.min(
                    300,
                    1L << Math.min(outbox.getRetryCount(), 8)
            );
            outboxMapper.markPending(
                    outbox.getEventId(),
                    outbox.getLeaseToken(),
                    LocalDateTime.now().plusSeconds(delaySeconds),
                    error
            );
            log.warn("视频统计 Outbox 发布失败，将重试, eventId={}",
                    outbox.getEventId(), e);
        }
    }

    private void recoverExpiredLease() {
        LocalDateTime expiredBefore = LocalDateTime.now()
                .minusSeconds(properties.getOutboxLeaseSeconds());
        outboxMapper.recoverExpiredSending(expiredBefore);
    }

    private String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() <= 1000
                ? message
                : message.substring(0, 1000);
    }
}
```

在启动类增加调度支持：

```java
@EnableScheduling
@SpringBootApplication
public class BackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
```

### 22.12 Retry/DLQ 可靠转发和消费者

文件：`mq/video/VideoStatsMessageForwarder.java`

```java
package com.feibijiubi.backend.mq.video;

import com.feibijiubi.backend.config.properties.VideoStatsProperties;
import com.feibijiubi.backend.constants.VideoStatsRabbitConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class VideoStatsMessageForwarder {

    private final RabbitTemplate rabbitTemplate;
    private final VideoStatsProperties properties;

    public void toRetry(Message original) throws Exception {
        publishConfirmed(
                VideoStatsRabbitConstants.RETRY_EXCHANGE,
                VideoStatsRabbitConstants.RETRY_ROUTING_KEY,
                original
        );
    }

    public void toDead(Message original, String reason) throws Exception {
        original.getMessageProperties().setHeader(
                "x-video-stats-failure-reason",
                reason
        );
        publishConfirmed(
                VideoStatsRabbitConstants.DEAD_EXCHANGE,
                VideoStatsRabbitConstants.DEAD_ROUTING_KEY,
                original
        );
    }

    private void publishConfirmed(
            String exchange,
            String routingKey,
            Message message
    ) throws Exception {
        message.getMessageProperties()
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        CorrelationData correlationData =
                new CorrelationData(UUID.randomUUID().toString());

        rabbitTemplate.send(
                exchange,
                routingKey,
                message,
                correlationData
        );

        CorrelationData.Confirm confirm = correlationData.getFuture().get(
                properties.getPublishConfirmTimeoutSeconds(),
                TimeUnit.SECONDS
        );
        if (!confirm.isAck() || correlationData.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ 转发未被可靠确认");
        }
    }
}
```

文件：`common/RetryableMessageException.java`

```java
package com.feibijiubi.backend.common;

public class RetryableMessageException extends RuntimeException {
    public RetryableMessageException(String message) {
        super(message);
    }

    public RetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

文件：`common/NonRetryableMessageException.java`

```java
package com.feibijiubi.backend.common;

public class NonRetryableMessageException extends RuntimeException {
    public NonRetryableMessageException(String message) {
        super(message);
    }

    public NonRetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

文件：`mq/video/VideoStatsEventConsumer.java`

```java
package com.feibijiubi.backend.mq.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.NonRetryableMessageException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.config.properties.VideoStatsProperties;
import com.feibijiubi.backend.constants.VideoStatsRabbitConstants;
import com.feibijiubi.backend.event.video.VideoStatsChangedEvent;
import com.feibijiubi.backend.service.video.VideoStatsCounterService;
import com.feibijiubi.backend.service.video.VideoStatsPersistenceService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatsEventConsumer {

    private final ObjectMapper objectMapper;
    private final VideoStatsCounterService counterService;
    private final VideoStatsPersistenceService persistenceService;
    private final VideoStatsMessageForwarder forwarder;
    private final VideoStatsProperties properties;

    @RabbitListener(
            queues = VideoStatsRabbitConstants.MAIN_QUEUE,
            containerFactory = "videoStatsListenerContainerFactory"
    )
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            process(message);
        } catch (NonRetryableMessageException e) {
            forwardDeadThenAck(message, channel, deliveryTag, e);
            return;
        } catch (RetryableMessageException
                 | RedisOperationException
                 | DataAccessException e) {
            forwardRetryOrDeadThenAck(message, channel, deliveryTag, e);
            return;
        } catch (Exception e) {
            forwardDeadThenAck(
                    message,
                    channel,
                    deliveryTag,
                    new NonRetryableMessageException(
                            "未知或非法统计消息",
                            e
                    )
            );
            return;
        }

        // 业务处理已经成功。ACK 失败不能再把正常消息转入 Retry/DLQ；
        // 让 IOException 交给监听容器处理，连接恢复后由 eventId 幂等吸收重投。
        channel.basicAck(deliveryTag, false);
    }

    private void process(Message message) {
        VideoStatsChangedEvent event = parse(message);
        String payloadHash = sha256(message.getBody());

        VideoStatsCounterService.ApplyResult redisResult =
                counterService.apply(event);
        switch (redisResult) {
            case APPLIED, DUPLICATE ->
                    persistenceService.persist(event, payloadHash);
            case SEQUENCE_GAP, NEEDS_REBUILD ->
                    throw new RetryableMessageException(
                            "统计事件存在序号缺口或需要重建"
                    );
            case OLD_SEQUENCE ->
                    throw new NonRetryableMessageException(
                            "旧序号事件没有对应的 eventId 幂等标记"
                    );
            case NEGATIVE_RESULT, INVALID_FIELD ->
                    throw new NonRetryableMessageException(
                            "统计事件会产生非法计数"
                    );
        }
    }

    private VideoStatsChangedEvent parse(Message message) {
        try {
            VideoStatsChangedEvent event = objectMapper.readValue(
                    message.getBody(),
                    VideoStatsChangedEvent.class
            );
            event.validate();
            return event;
        } catch (Exception e) {
            throw new NonRetryableMessageException("统计消息解析失败", e);
        }
    }

    private void forwardRetryOrDeadThenAck(
            Message message,
            Channel channel,
            long deliveryTag,
            Exception cause
    ) throws IOException {
        try {
            if (retryCount(message) >= properties.getConsumerMaxRetries()) {
                forwarder.toDead(message, cause.getMessage());
            } else {
                forwarder.toRetry(message);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception forwardError) {
            log.error("统计消息重试转发失败，保留原消息未确认", forwardError);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void forwardDeadThenAck(
            Message message,
            Channel channel,
            long deliveryTag,
            Exception cause
    ) throws IOException {
        try {
            forwarder.toDead(message, cause.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (Exception forwardError) {
            log.error("统计消息死信转发失败，保留原消息未确认", forwardError);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private long retryCount(Message message) {
        Object header = message.getMessageProperties()
                .getHeaders()
                .get("x-death");
        if (!(header instanceof List<?> deaths)) {
            return 0;
        }

        return deaths.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(death -> VideoStatsRabbitConstants.RETRY_QUEUE
                        .equals(String.valueOf(death.get("queue"))))
                .mapToLong(death -> {
                    Object count = death.get("count");
                    return count instanceof Number number
                            ? number.longValue()
                            : 0L;
                })
                .sum();
    }

    private String sha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(body);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new NonRetryableMessageException(
                    "统计消息摘要计算失败",
                    e
            );
        }
    }
}
```

上面的 `basicNack(..., requeue=true)` 只用于“Retry/DLQ 转发本身失败”的兜底窗口，不用于正常业务重试。正常暂时性异常会显式进入带 TTL 的 Retry Queue，因此不会形成持续的原地热循环。

业务成功后的 `basicAck` 必须位于业务异常分类之外。如果 Redis 和 MySQL 已经处理成功，但 ACK 因信道中断抛出 `IOException`，应让异常交给监听容器，使原消息在连接恢复后重新投递；不能把它包装成非法消息转入 DLQ。重新投递时 Redis eventId 和 MySQL 唯一键会分别吸收重复。

### 22.13 接入当前 `UserVideoServiceImpl`

先增加依赖：

```java
private final VideoStatsOutboxService videoStatsOutboxService;
private final VideoStatsProperties videoStatsProperties;
```

推荐先保留同步路径，通过 `async-enabled` 灰度切换。抽取统一方法：

```java
private void recordStats(
        Integer vid,
        VideoStatsEventType type,
        long delta,
        Runnable synchronousFallback
) {
    if (videoStatsProperties.isAsyncEnabled()) {
        videoStatsOutboxService.createEvent(vid, type, delta);
        return;
    }
    synchronousFallback.run();
}
```

播放次数方法原本没有事务，接入 Outbox 后必须增加事务：

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void increasePlayCount(Integer vid) {
    validateVideo(vid);

    recordStats(
            vid,
            VideoStatsEventType.PLAY,
            1,
            () -> {
                int rows = videoStatusMapper.increasePlayTimes(vid);
                if (rows != 1) {
                    throw new BusinessException(500, "播放量更新失败");
                }
            }
    );
}
```

`recordLike` 中保留原来的状态判断和 `user_video` 更新，只替换统计更新部分：

```java
long delta = isSet ? 1L : -1L;
VideoStatsEventType type = isLike
        ? VideoStatsEventType.LIKE
        : VideoStatsEventType.UNLIKE;

recordStats(
        vid,
        type,
        delta,
        () -> {
            int rows = isSet
                    ? videoStatusMapper.increaseLikeTimes(vid, isLike)
                    : videoStatusMapper.decreaseLikeTimes(vid, isLike);
            if (rows != 1) {
                throw new BusinessException(500, "视频点赞统计更新失败");
            }
        }
);
```

投币方法必须先完成用户余额和 `user_video` 事实更新，再在同一事务中创建 `COIN` 事件。异步模式下删除请求线程的 `increaseCoinTimes`：

```java
int userRows = userMapper.decreaseCoin(currentUserId, coin);
if (userRows != 1) {
    throw new BusinessException(500, "视频投币失败");
}

userVideo.setCoin(coin);
userVideo.setCoinedAt(LocalDateTime.now());
if (userVideoMapper.updateCoin(userVideo) != 1) {
    throw new BusinessException(500, "视频投币失败");
}

recordStats(
        vid,
        VideoStatsEventType.COIN,
        coin.longValue(),
        () -> {
            if (videoStatusMapper.increaseCoinTimes(vid, coin) != 1) {
                throw new BusinessException(500, "视频投币失败");
            }
        }
);
```

分享方法也必须增加事务：

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void increaseShare(Integer vid) {
    validateVideo(vid);

    recordStats(
            vid,
            VideoStatsEventType.SHARE,
            1,
            () -> {
                if (videoStatusMapper.increaseShareTimes(vid) != 1) {
                    throw new BusinessException(500, "分享失败");
                }
            }
    );
}
```

收藏方法只替换统计部分：

```java
long delta = isCollect ? 1L : -1L;
recordStats(
        vid,
        VideoStatsEventType.COLLECT,
        delta,
        () -> {
            int rows = isCollect
                    ? videoStatusMapper.increaseCollectTimes(vid)
                    : videoStatusMapper.decreaseCollectTimes(vid);
            if (rows != 1) {
                throw new BusinessException(500, "收藏统计更新失败");
            }
        }
);
```

必须继续保留当前代码已有的“状态未变化直接返回”。否则重复点赞、重复收藏会产生新的 eventId 和错误增量。

### 22.14 查询实时统计

文件：`service/video/VideoStatsQueryService.java`

```java
package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.entity.VideoStatus;

public interface VideoStatsQueryService {
    VideoStatus getByVid(Integer vid);
}
```

文件：`service/impl/video/VideoStatsQueryServiceImpl.java`

```java
package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.constants.VideoStatsRedisConstants;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.video.VideoStatsQueryService;
import com.feibijiubi.backend.service.video.VideoStatsRebuildService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class VideoStatsQueryServiceImpl
        implements VideoStatsQueryService {

    private final StringRedisTemplate redisTemplate;
    private final VideoStatsRebuildService rebuildService;
    private final VideoStatusMapper videoStatusMapper;

    @Override
    public VideoStatus getByVid(Integer vid) {
        String key = VideoStatsRedisConstants.statsKey(vid);
        Map<Object, Object> values =
                redisTemplate.opsForHash().entries(key);

        if (values.isEmpty()) {
            rebuildService.rebuild(vid);
            values = redisTemplate.opsForHash().entries(key);
        }
        if (values.isEmpty()) {
            return videoStatusMapper.selectByVid(vid);
        }

        VideoStatus status = new VideoStatus();
        status.setVid(Integer.valueOf(String.valueOf(values.get("vid"))));
        status.setPlayTimes(parseLong(values, "playTimes"));
        status.setLikeTimes(parseLong(values, "likeTimes"));
        status.setUnlikeTimes(parseLong(values, "unlikeTimes"));
        status.setCommentTimes(parseLong(values, "commentTimes"));
        status.setCoinTimes(parseLong(values, "coinTimes"));
        status.setShareTimes(parseLong(values, "shareTimes"));
        status.setCollectTimes(parseLong(values, "collectTimes"));
        status.setDanmuTimes(parseLong(values, "danmuTimes"));
        status.setAppliedSequence(parseLong(values, "lastSequence"));
        return status;
    }

    private Long parseLong(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("Redis 统计字段缺失: " + field);
        }
        return Long.valueOf(String.valueOf(value));
    }
}
```

上述代码假设 `VideoStatus` 的八个统计字段已升级为 `Long`。如果暂时保留 `Integer`，需要将 `parseLong` 改成 `parseInteger`，但不建议长期维持该限制。

`VideoServiceImpl` 查询详情时，将：

```java
VideoStatus videoStatus = videoStatusMapper.selectByVid(vid);
```

替换为：

```java
VideoStatus videoStatus = videoStatsQueryService.getByVid(vid);
```

### 22.15 第一版必须补充的测试

至少创建以下测试类：

```text
src/test/java/com/feibijiubi/backend/service/video/
├── VideoStatsOutboxServiceTests.java
├── VideoStatsPersistenceServiceTests.java
├── VideoStatsCounterServiceTests.java
└── VideoStatsEventConsumerTests.java
```

关键断言：

```java
@Test
void duplicateEventMustOnlyApplyOnce() {
    VideoStatsChangedEvent event = testEvent("event-1", 1L);

    assertThat(counterService.apply(event))
            .isEqualTo(ApplyResult.APPLIED);
    assertThat(counterService.apply(event))
            .isEqualTo(ApplyResult.DUPLICATE);
}
```

```java
@Test
void businessRollbackMustRollbackOutbox() {
    assertThatThrownBy(() -> userVideoService.recordLike(
            uid,
            vid,
            true,
            true
    )).isInstanceOf(RuntimeException.class);

    assertThat(outboxMapper.selectByEventId(...)).isNull();
}
```

```java
@Test
void mysqlDuplicateMustNotIncreaseTwice() {
    persistenceService.persist(event, payloadHash);
    persistenceService.persist(event, payloadHash);

    VideoStatus status = videoStatusMapper.selectByVid(event.vid());
    assertThat(status.getLikeTimes()).isEqualTo(1L);
}
```

```java
@Test
void sameEventIdWithDifferentPayloadMustFail() {
    persistenceService.persist(firstEvent, firstHash);

    assertThatThrownBy(() ->
            persistenceService.persist(conflictingEvent, conflictingHash)
    ).isInstanceOf(IllegalArgumentException.class);
}
```

完整集成测试建议使用 Testcontainers 启动 MySQL、Redis 和 RabbitMQ。只使用 Mockito 无法验证 Lua 原子性、MySQL 唯一约束、Publisher Confirm、`x-death` 和手动 ACK。

### 22.16 推荐实际落地顺序

不要一次性复制所有代码后直接打开异步开关。按以下顺序提交和验证：

1. 执行数据库迁移，升级 `VideoStatus` 类型并保持旧同步 SQL 可用；
2. 添加事件模型、序号表和 Outbox 写入，保持 `async-enabled=false`；
3. 添加 Redis 重建、Lua 和 CounterService，单独完成 Redis 集成测试；
4. 添加 RabbitMQ 拓扑、Relay、消费者和消费幂等；
5. 在测试环境打开 `async-enabled=true`，先迁移分享事件；
6. 对比 Redis、MySQL 和旧同步结果；
7. 再迁移播放、收藏、点赞/点踩，最后迁移投币；
8. 稳定后删除同步 fallback，并开始实现前文的生产增强项。

### 22.17 本章代码仍需在真实环境验证的事项

即使代码已经补全，也不能仅凭文档认定可以直接上线。实际合入仓库后必须验证：

- 当前 Spring Boot 3.5.15 管理的 Spring AMQP 版本中，`CorrelationData#getReturned()` 与 Confirm 回调顺序；
- MySQL 版本是否支持 `FOR UPDATE SKIP LOCKED`；
- `ObjectMapper` 对 `LocalDateTime` 的序列化模块是否由 Spring Boot 正常注册；
- RabbitMQ 消息转换器实际产生的 body 是否与消费者 `ObjectMapper` 解析一致；
- Redis Lua 返回 `String` 时当前 Redis serializer 是否能正确转换；
- 开启异步模式前，`video_status.applied_sequence`、序号表和既有统计是否处于一致基线；
- 多实例部署前必须完成 per-vid 串行、初始化 fencing token 和更严格的 Relay 状态结算；
- 生产环境必须补监控、告警、DLQ 运维和对账任务。

因此，本章代码的正确使用方式是：**先作为第一版实现逐文件落入仓库，再执行编译、集成测试和故障演练；不能把“文档代码完整”误解成“未经验证即可上线”。**