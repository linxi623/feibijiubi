# 视频统计批量落库实现手册

## 1. 目标

本文说明当前实现如何把“Redis 已应用”的事件可靠地批量写回 MySQL。

当前批量落库的唯一输入是：

```text
video_status_consumed_event.process_status = 1
```

刷库事务不读取 Redis，也不修改 Redis。Redis current 继续服务实时查询，状态 1 事件负责保证待落库数据不丢失。

## 2. 完整流程

```text
消费者登记 event(status=0)
  -> Redis current + dirty + processed + hot
  -> markRedisApplied(eventId)
  -> event(status=1)
  -> Flush Scheduler 从 dirty Set 弹出 vid
  -> 获取 vid 分布式锁
  -> SELECT status=1 FOR UPDATE LIMIT N
  -> 聚合 VideoStatusDelta
  -> UPDATE video_status
  -> UPDATE consumed_event status=1 -> 2
  -> 同一事务提交
  -> 检查该 vid 是否还有状态 1
```

## 3. 数据库结构

需要保留的统计表：

```sql
CREATE TABLE video_status (
    vid INT NOT NULL,
    play_times INT NOT NULL DEFAULT 0,
    like_times INT NOT NULL DEFAULT 0,
    unlike_times INT NOT NULL DEFAULT 0,
    comment_times INT NOT NULL DEFAULT 0,
    coin_times INT NOT NULL DEFAULT 0,
    share_times INT NOT NULL DEFAULT 0,
    collect_times INT NOT NULL DEFAULT 0,
    danmu_times INT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (vid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

消费事件表保存刷库输入：

```sql
CREATE TABLE video_status_consumed_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    vid INT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    delta BIGINT NOT NULL,
    payload JSON NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    process_status TINYINT NOT NULL DEFAULT 0,
    consumer_retry_count INT NOT NULL DEFAULT 0,
    last_attempt_at DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    consumed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    redis_applied_at DATETIME(3) NULL,
    flushed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_status_consumed_event_id (event_id),
    KEY idx_video_status_consumed_pending (process_status, vid, id),
    KEY idx_video_status_consumed_recovery (process_status, last_attempt_at, id),
    CONSTRAINT fk_video_status_consumed_vid
        FOREIGN KEY (vid) REFERENCES video (vid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

不再需要单独保存刷库批次。事件行本身就是批次记录，状态从 1 变为 2 就是提交证据。

## 4. Mapper 查询

按 vid 锁定一批状态 1 事件：

```xml
<select id="selectPendingForUpdate"
        resultType="com.feibijiubi.backend.entity.VideoStatusConsumedEvent">
    SELECT id, event_id, vid, event_type, delta, payload,
           payload_hash, process_status, consumer_retry_count,
           last_attempt_at, last_error, consumed_at,
           redis_applied_at, flushed_at
    FROM video_status_consumed_event
    WHERE vid = #{vid}
      AND process_status = 1
    ORDER BY id
    LIMIT #{limit}
    FOR UPDATE
</select>
```

批量标记完成：

```xml
<update id="markFlushed">
    UPDATE video_status_consumed_event
    SET process_status = 2,
        flushed_at = NOW(3),
        last_error = NULL
    WHERE process_status = 1
      AND id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">
          #{id}
      </foreach>
</update>
```

更新数量必须等于锁定数量，否则事务回滚并重试。

## 5. Service 实现

接口：

```java
public interface VideoStatusBatchFlushService {
    void flushOneVideo(Integer vid, int limit);

    void markRepairRequired(
            List<Long> consumedEventIds,
            String lastError
    );
}
```

实现顺序：

```java
@Transactional(rollbackFor = Exception.class)
public void flushOneVideo(Integer vid, int limit) {
    List<VideoStatusConsumedEvent> events =
            consumedEventMapper.selectPendingForUpdate(vid, limit);
    if (events.isEmpty()) {
        return;
    }

    List<Long> ids = events.stream()
            .map(VideoStatusConsumedEvent::getId)
            .toList();

    VideoStatusDelta delta;
    try {
        delta = events.stream()
                .map(VideoStatusDelta::from)
                .reduce(VideoStatusDelta.zero(), VideoStatusDelta::plus);
    } catch (IllegalArgumentException e) {
        throw new VideoStatusFlushDataException(
                vid,
                ids,
                "消费事件包含未知统计类型"
        );
    }

    if (!delta.isZero()
            && videoStatusMapper.applyBatchDelta(vid, delta) != 1) {
        throw new VideoStatusFlushDataException(
                vid,
                ids,
                "视频统计批量更新违反非负约束"
        );
    }

    if (consumedEventMapper.markFlushed(ids) != ids.size()) {
        throw new RetryableMessageException(
                "事件 FLUSHED 数量与锁定数量不一致，vid=" + vid
        );
    }
}
```

统计更新和事件状态 2 必须在同一事务内。否则会出现两种错误：

- 统计已增加但状态仍为 1，下次刷库重复累计。
- 状态已变为 2 但统计未增加，事件永久漏算。

## 6. 聚合

事件类型映射为固定八项：

```text
PLAY    -> playDelta
LIKE    -> likeDelta
UNLIKE  -> unlikeDelta
COMMENT -> commentDelta
COIN    -> coinDelta
SHARE   -> shareDelta
COLLECT -> collectDelta
DANMU   -> danmuDelta
```

一批事件先累加成一个 `VideoStatusDelta`，然后执行一次：

```sql
UPDATE video_status
SET play_times = play_times + #{delta.playDelta},
    like_times = like_times + #{delta.likeDelta},
    unlike_times = unlike_times + #{delta.unlikeDelta},
    comment_times = comment_times + #{delta.commentDelta},
    coin_times = coin_times + #{delta.coinDelta},
    share_times = share_times + #{delta.shareDelta},
    collect_times = collect_times + #{delta.collectDelta},
    danmu_times = danmu_times + #{delta.danmuDelta}
WHERE vid = #{vid}
  AND play_times + #{delta.playDelta} >= 0
  AND like_times + #{delta.likeDelta} >= 0
  AND unlike_times + #{delta.unlikeDelta} >= 0
  AND comment_times + #{delta.commentDelta} >= 0
  AND coin_times + #{delta.coinDelta} >= 0
  AND share_times + #{delta.shareDelta} >= 0
  AND collect_times + #{delta.collectDelta} >= 0
  AND danmu_times + #{delta.danmuDelta} >= 0;
```

如果净增量为 0，可以跳过 UPDATE，但仍然必须把命中事件标记为状态 2。

## 7. Dirty Set 和调度

Redis Lua 每成功应用一个事件，就执行：

```text
SADD dirty:video:v1 {vid}
```

`VideoStatusBatchFlushScheduler`：

1. 从 dirty Set 批量弹出 vid。
2. 对每个 vid 获取分布式锁。
3. 调用 `flushOneVideo(vid, flushEventBatchSize)`。
4. 查询该 vid 是否仍有状态 1。
5. 有则重新加入 dirty Set，没有则从 Set 删除。

dirty Set 只是加速索引。`VideoStatusDirtyRecoveryScheduler` 会定期从消费事件表查询状态 1 的 vid 并补回。

## 8. 异常处理

### 8.1 可重试异常

包括 MySQL 暂时不可用、Redis 操作失败、行锁竞争等。处理方式：

```text
重新加入 dirty Set
-> 下一轮继续处理
```

### 8.2 数据异常

包括未知事件类型、非负约束失败等。处理方式：

```text
事件状态 1 -> 3
写入 video_status_consumption_repair_log
不再自动刷库
```

### 8.3 行数不一致

如果锁定 N 条事件，`markFlushed()` 更新不是 N 条，则抛出可重试异常并回滚整个事务。

## 9. 与 Redis current 的关系

Redis current 和 MySQL `video_status` 表达不同阶段：

```text
Redis current = 已落库基线 + 状态 1 实时增量
MySQL video_status = 已落库基线
```

刷库完成时，`video_status` 吸收状态 1 事件，Redis current 不需要做任何反向减法。这样即使 MySQL 提交后 Redis 暂时不可用，数据也不会被重复累计或永久丢失。

## 10. 重建

当 current 缺失时：

```text
rebuilt current
  = video_status baseline
  + status 1 事件
  + processed Key 已存在的 status 0 事件
```

重建完成后，如果存在状态 1 事件，会重新加入 dirty Set，刷库任务继续处理。

## 11. 测试重点

建议覆盖：

- 同一 vid 的多个事件在一个批次中正确聚合。
- 净增量为 0 时事件仍变为状态 2。
- 更新统计失败时事件不会变为状态 2。
- 标记状态 2 行数不一致时统计更新回滚。
- 同一 vid 的并发刷库只能有一个事务成功。
- dirty Set 丢失后恢复任务能从状态 1 补回。
- Redis current 删除后重建结果等于 MySQL 基线加状态 0/1 中已确认的事件。

## 12. 上线迁移

旧结构切换时：

1. 停止所有旧后端实例。
2. 备份 MySQL。
3. 删除旧批次表。
4. 删除旧 Redis 待刷增量和旧清理标记。
5. 从 current Hash 删除旧代次字段。
6. 保留消费事件、current、dirty、processed 和 hot。
7. 部署新版本。

状态 1 事件无需迁移，新版本会直接从消费事件表继续刷库。
