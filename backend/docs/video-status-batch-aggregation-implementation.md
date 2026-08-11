# 视频统计 V2：从当前仓库出发的逐步实施手册

> 目标：按照本文档从上到下执行，不跳步骤，最终完成“Outbox → RabbitMQ → Redis 实时聚合 → MySQL 批量刷库”的 V2 链路。  
> 项目环境：Java 17、Spring Boot 3.5.15、MyBatis、MySQL 8、Redis、RabbitMQ。  
> 重要说明：这是偏生产可靠性的实现，不是最小实习项目。完成第 10 阶段可以得到可演示的核心链路；第 11 阶段以后是生产闭环。

---

## 1. 如何使用本文档

每个阶段都固定包含：

1. 目标；
2. 前置条件；
3. 修改文件；
4. 按顺序执行的代码步骤；
5. 本阶段删除的旧代码；
6. 验证命令和预期结果；
7. 禁止继续的条件。

执行规则：

- 一次只做一个阶段。
- 每个阶段验证通过后再进入下一阶段。
- 中间阶段禁止启动半完成的异步消费者。
- 不要一边保留旧协议，一边让新旧消费者竞争同一个队列。
- 文中出现的状态值统一使用枚举或常量，不在业务代码中散落数字 `0/1/2/3`。
- 所有 `FOR UPDATE`、`FOR UPDATE SKIP LOCKED` 查询都必须在 Spring 事务内调用。
- 本文默认使用本地学习环境，可以重建开发数据库。需要保留线上数据时，使用附录 E 的迁移与切换流程，不要直接运行全量建库脚本。

每阶段使用以下命令验证：

```powershell
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd test
```

当前 Windows Maven Wrapper 无法启动时，阶段 0 会先修复 Wrapper。系统 Maven 只能用于诊断，不作为最终验收命令。

---

## 2. 当前仓库真实状态

本文不是从一个干净的 V1 分支开始，而是从当前半改造状态继续。

### 2.1 已完成或已有骨架

- Outbox 表、Mapper、Claim Service、Relay、Publisher Confirm 已有实现。
- RabbitMQ Main/Retry/DLQ 基础配置和手动 ACK 已存在。
- `VideoStatusEventType` 已有 current field、delta field 和热门权重映射。
- Redis V2 Key 常量已经部分加入。
- Redis 聚合 Lua 已经写入 eventId 幂等、负数保护、current/delta/dirty/hot 原子更新逻辑。
- `VideoStatusConsumedEventMapper.java` 和 XML 已有大部分 0～3 状态 SQL。
- `VideoStatusConsumptionService` 接口骨架已经创建。

### 2.2 当前明确缺失

- `RegistrationResult` 不存在。
- `VideoStatusConsumptionServiceImpl` 不存在。
- `video_status_consumed_event.payload` 在 SQL、实体和 XML 中都不存在。
- `VideoStatusFlushBatch` 实体、Mapper、Service、Scheduler 不存在。
- Redis 初始化 Lua、delta 清理 Service 和恢复 Scheduler 不存在。
- 状态 0 超时恢复、状态 3 管理入口和修复审计不存在。
- 视频统计链路没有专用测试。

### 2.3 当前已知错误

- 当前第一个编译错误是 `VideoStatusConsumptionService` 找不到 `RegistrationResult`。
- `VideoStatusServiceImpl.persist()` 仍调用已经从 Mapper 删除的 `selectByEventId()`、`markCommitted()`。
- 消费者仍引用已删除的 `SEQUENCE_GAP`、`OLD_SEQUENCE`。
- `VideoStatusMapper.xml#applyDelta` 的最后一个 `SET` 字段后有多余逗号。
- Java 调用 Redis Lua 时，KEY 顺序与 Lua 契约不一致，并且缺少 `deltaField` 参数。
- 当前 `rebuild()` 只初始化 current Hash，没有初始化 delta Hash，会反复得到 `NEEDS_REBUILD`。
- `VideoStatusChangedEvent` 当前仍校验 `schemaVersion == 1`。
- Outbox Relay 使用了错误的配置名 `app.video-Status`。

在完成阶段 0 前，不要启动当前异步统计链路。

---

## 3. 最终不可破坏的约束

最终数据流固定为：

```text
业务事务
  -> 写业务事实
  -> 插入 video_status_outbox

Outbox Relay
  -> RabbitMQ

消费者
  -> MySQL 登记 consumed_event，状态 0 RECEIVED
  -> Redis Lua 更新实时值、delta、dirty、热门分数和 eventId 幂等 Key
  -> MySQL 状态 0 -> 1 REDIS_APPLIED_PENDING_FLUSH
  -> ACK

批量刷库
  -> 按 vid 锁定状态 1 事件
  -> 聚合八项 delta
  -> 一次 UPDATE video_status
  -> 同事务状态 1 -> 2 FLUSHED
  -> 同事务插入 flush_batch

事务提交后
  -> Redis delta 清理 Lua
```

ACK 规则：

```text
MySQL 未登记：不能 ACK
Redis 未成功或未确认 DUPLICATE：不能 ACK
状态 1 未成功提交：不能 ACK
Retry/DLQ 未得到 Confirm：不能 ACK 原消息
```

消费状态：

```text
0 RECEIVED
1 REDIS_APPLIED_PENDING_FLUSH
2 FLUSHED
3 REPAIR_REQUIRED
```

刷库清理状态：

```text
0 PENDING
1 CLEANED
2 SKIPPED_GENERATION_CHANGED
3 REPAIR_REQUIRED
```

---

## 4. 总体阶段顺序

| 阶段 | 产物 | 完成后是否允许启动 V2 消费者 |
|---|---|---|
| 0 | 恢复可编译基线 | 否 |
| 1 | 最终数据库和实体 | 否 |
| 2 | V2 事件与 Outbox | 否 |
| 3 | Redis Key、四个 Lua、脚本 Bean | 否 |
| 4 | Redis 实时服务和安全初始化 | 否 |
| 5 | 消费事件状态服务核心 | 否 |
| 6 | 可靠转发与最终消费者代码 | 仍保持监听器关闭 |
| 7 | 批量刷库模型和 Mapper | 否 |
| 8 | 批量刷库与 delta 清理 Service | 否 |
| 9 | flush、dirty、cleanup Scheduler | 否 |
| 10 | 本地核心链路联调 | 是，单消费者 |
| 11 | 状态 0 恢复和状态 3 管理 | 生产闭环 |
| 12 | Outbox、配置、安全和监控收尾 | 生产闭环 |
| 13 | V1 → V2 切换与回滚演练 | 正式切换 |

---

# 第一部分：恢复可工作的离线基线

## 5. 阶段 0：修复当前半改造基线

### 5.1 目标

让项目重新编译，并明确中间阶段不运行异步消费者。此阶段不追求 V2 功能正确，只为后续改造建立稳定起点。

### 5.2 前置条件

1. 停止正在运行的 Spring Boot 应用。
2. 不启动 RabbitMQ 消费者。
3. 暂时设置：

```yaml
app:
  video-status:
    async-enabled: false
    outbox-relay-enabled: false
    scheduling-enabled: false

spring:
  rabbitmq:
    listener:
      simple:
        auto-startup: false
```

如果现有 RabbitMQ 队列中已经有旧消息，先不要清空；在阶段 13 决定排空还是隔离。

### 5.3 修复 Maven Wrapper

先运行：

```powershell
.\mvnw.cmd -v
```

如果仍然出现 `Cannot start maven from wrapper`，使用系统 Maven 只执行一次 Wrapper 重建：

```powershell
mvn org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16
.\mvnw.cmd -v
```

完成标志：`mvnw.cmd` 能输出 Maven 版本，并且后续所有阶段使用 Wrapper。

### 5.4 创建当前缺失的枚举

新增：

```text
src/main/java/com/feibijiubi/backend/service/video/RegistrationResult.java
```

```java
package com.feibijiubi.backend.service.video;

public enum RegistrationResult {
    NEEDS_REDIS_APPLY,
    REDIS_ALREADY_APPLIED,
    ALREADY_FLUSHED
}
```

新增：

```text
src/main/java/com/feibijiubi/backend/common/RepairRequiredMessageException.java
```

```java
package com.feibijiubi.backend.common;

public class RepairRequiredMessageException extends RuntimeException {
    private final boolean alertRequired;

    public RepairRequiredMessageException(String message) {
        this(message, false);
    }

    public RepairRequiredMessageException(
            String message,
            boolean alertRequired
    ) {
        super(message);
        this.alertRequired = alertRequired;
    }

    public boolean isAlertRequired() {
        return alertRequired;
    }
}
```

### 5.5 暂时缩小消费状态接口

当前接口提前声明了尚未实现的恢复和管理方法。阶段 0 先保留核心方法：

```java
public interface VideoStatusConsumptionService {
    RegistrationResult register(
            VideoStatusChangedEvent event,
            String semanticPayloadHash
    );

    void markRedisApplied(String eventId);

    void recordConsumerFailure(
            String eventId,
            int attempt,
            String lastError
    );
}
```

阶段 11 再增加恢复和人工处置方法。不要用空实现或 `UnsupportedOperationException` 假装完成。

### 5.6 给旧 `persist()` 增加临时兼容桥

当前 Mapper 已删除旧方法，但 `persist()` 仍依赖它们。为了让每个阶段都能编译，临时增加：

```java
@Deprecated
VideoStatusConsumedEvent selectByEventIdLegacy(String eventId);

@Deprecated
int markLegacyFlushed(String eventId);
```

XML：

```xml
<select id="selectByEventIdLegacy"
        resultMap="VideoStatusConsumedEventResultMap">
    SELECT
    <include refid="ConsumedEventColumns"/>
    FROM video_status_consumed_event
    WHERE event_id = #{eventId}
</select>

<update id="markLegacyFlushed">
    UPDATE video_status_consumed_event
    SET process_status = 2,
        flushed_at = NOW(3),
        last_error = NULL
    WHERE event_id = #{eventId}
      AND process_status = 0
</update>
```

把旧 `persist()` 中的调用暂时改成：

```text
selectByEventId() -> selectByEventIdLegacy()
markCommitted()   -> markLegacyFlushed()
```

这是临时代码，只用于维持编译；阶段 6 切换消费者时必须与 `persist()` 一起删除。

### 5.7 修复当前消费者枚举分支

当前 `ApplyResult` 已经没有：

```text
SEQUENCE_GAP
OLD_SEQUENCE
```

从消费者 `switch` 中删除这两个 case，并补齐：

```java
case INVALID_REDIS_TYPE ->
        throw new NonRetryableMessageException("Redis Key 类型错误");
```

此处仍是临时旧消费者；阶段 6 会整体替换。

### 5.8 修复当前 SQL 语法错误

文件：

```text
src/main/resources/com/feibijiubi/backend/mapper/VideoStatusMapper.xml
```

删除：

```sql
danmu_times = danmu_times + #{delta.danmuDelta},
```

末尾多余的逗号，改为：

```sql
danmu_times = danmu_times + #{delta.danmuDelta}
```

### 5.9 阶段验证

```powershell
.\mvnw.cmd -DskipTests compile
```

预期：主代码编译通过。

如果上下文测试会自动连接 RabbitMQ，在 `src/test/resources/application.yml` 中保持：

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        auto-startup: false

app:
  video-status:
    async-enabled: false
    scheduling-enabled: false
```

所有新建 Scheduler 都增加：

```java
@ConditionalOnProperty(
        prefix = "app.video-status",
        name = "scheduling-enabled",
        havingValue = "true"
)
```

这样测试不依赖一个不存在或不确定的全局 Scheduling 开关。

然后运行：

```powershell
.\mvnw.cmd test
```

禁止进入下一阶段的情况：

- Wrapper 仍不可用；
- 主代码仍有编译错误；
- 仍然启动了半完成的异步消费者；
- 为了通过编译而删除用户已有业务功能。

---

# 第二部分：一次完成最终数据契约

## 6. 阶段 1：完成数据库结构、实体和状态枚举

### 6.1 目标

一次确定最终表结构与 Java 类型。后续阶段只增加 Mapper 和 Service，不再反复修改列名。

### 6.2 数据库策略

主流程默认本地学习环境允许重建数据库：

1. 备份需要保留的数据；
2. 修正 `database/feibijiubi.sql`；
3. 从该脚本重建本地数据库；
4. 不创建临时迁移 SQL。

如果数据不能丢失，停止主流程，改用附录 E。

### 6.3 修正全量脚本的删除顺序

在删除 `video` 和 `video_status` 前先删除新增子表：

```sql
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS video_status_consumption_repair_log;
DROP TABLE IF EXISTS video_status_flush_batch;
DROP TABLE IF EXISTS video_status_consumed_event;
DROP TABLE IF EXISTS video_status_outbox;

-- 原有 DROP TABLE 继续放在这里

SET FOREIGN_KEY_CHECKS = 1;
```

### 6.4 最终 `video_status`

八个统计字段全部使用 `BIGINT UNSIGNED`，删除 `applied_sequence`。

同步修改：

```text
src/main/java/com/feibijiubi/backend/entity/VideoStatus.java
```

八个统计属性从 `Integer` 改为 `Long`。`vid` 仍然使用 `Integer`。

同步修改所有对外统计字段，避免把 `BIGINT` 又截断成 `Integer`：

```text
vo/AdminVideoDetailVO.java
vo/VideoDetailVO.java
vo/VideoListItemVO.java
vo/UserCountVO.java 中的 loveCount
mapper/VideoStatusMapper#countLikeByUid 返回 Long
converter/VideoConverter.java
converter/UserConverter.java 的对应参数
service/impl/user/UserServiceImpl.java 的调用链
test/converter/ResponseConverterTests.java
```

Redis Hash 转实体时使用 `Long.parseLong(...)`，不要继续使用当前 `parseInt(...)`。

### 6.5 最终 `video_status_consumed_event`

建表关键字段：

```sql
CREATE TABLE video_status_consumed_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    vid INT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    delta BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    payload TEXT NOT NULL,
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
);
```

把实体整理为最终字段集合，不要只增加 `payload` 后遗漏时间和重试字段：

```java
private Long id;
private String eventId;
private Integer vid;
private String eventType;
private Long delta;
private String payloadHash;
private String payload;
private Integer processStatus;
private Integer consumerRetryCount;
private LocalDateTime lastAttemptAt;
private String lastError;
private LocalDateTime consumedAt;
private LocalDateTime redisAppliedAt;
private LocalDateTime flushedAt;
```

确认实体不再包含：

```text
aggregateSequence
committedAt
```

### 6.6 最终 `video_status_flush_batch`

保留现有建表设计，并补充 cleanup 恢复计数：

```sql
cleanup_retry_count INT NOT NULL DEFAULT 0,
last_attempt_at DATETIME(3) NULL,
```

新增实体：

```text
src/main/java/com/feibijiubi/backend/entity/VideoStatusFlushBatch.java
```

实体字段：

```java
private Long id;
private String batchId;
private Integer vid;
private String redisGeneration;
private Long playDelta;
private Long likeDelta;
private Long unlikeDelta;
private Long commentDelta;
private Long coinDelta;
private Long shareDelta;
private Long collectDelta;
private Long danmuDelta;
private Integer cleanupStatus;
private Integer cleanupRetryCount;
private LocalDateTime lastAttemptAt;
private String lastError;
private LocalDateTime createdAt;
private LocalDateTime cleanedAt;
```

### 6.7 创建人工修复审计表

```sql
CREATE TABLE video_status_consumption_repair_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operation_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    operation_status TINYINT NOT NULL DEFAULT 0
        COMMENT '0=STARTED,1=COMPLETED,2=FAILED',
    reason VARCHAR(1000) NOT NULL,
    operator VARCHAR(64) NOT NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    active_event_id VARCHAR(64)
        GENERATED ALWAYS AS (
            CASE WHEN operation_status = 0 THEN event_id ELSE NULL END
        ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_status_repair_operation (operation_id),
    UNIQUE KEY uk_video_status_repair_active_event (active_event_id),
    KEY idx_video_status_repair_event (event_id, id),
    KEY idx_video_status_repair_pending (operation_status, id)
);
```

新增实体：

```text
src/main/java/com/feibijiubi/backend/entity/VideoStatusConsumptionRepairLog.java
```

实体字段与表一一对应：

```java
private Long id;
private String operationId;
private String eventId;
private String action;
private Integer operationStatus;
private String reason;
private String operator;
private String lastError;
private LocalDateTime createdAt;
private LocalDateTime completedAt;
```

这张表既是审计日志，也是人工 Redis 重建的持久恢复任务。不能只有一条“开始修复”的普通日志，否则进程在 Redis 已重建、MySQL 状态尚未更新时崩溃，重启后无法判断该继续还是回退。

`active_event_id` 是数据库生成列，不放进 Java 实体。MySQL 唯一键允许多个 `NULL`，因此它只限制“同一个 eventId 同时最多一个 STARTED 任务”；任务进入 COMPLETED/FAILED 后，生成列自动变成 `NULL`。

### 6.8 创建状态枚举

新增：

```text
src/main/java/com/feibijiubi/backend/enums/VideoStatusConsumeProcessStatus.java
src/main/java/com/feibijiubi/backend/enums/VideoStatusFlushCleanupStatus.java
```

枚举分别映射：

```text
RECEIVED(0)
REDIS_APPLIED_PENDING_FLUSH(1)
FLUSHED(2)
REPAIR_REQUIRED(3)
```

```text
PENDING(0)
CLEANED(1)
SKIPPED_GENERATION_CHANGED(2)
REPAIR_REQUIRED(3)
```

每个枚举提供 `code()`；SQL 仍保存数字，Service 只引用枚举值。

### 6.9 更新 consumed-event XML 的 payload

依次修改 `VideoStatusConsumedEventMapper.xml` 的三个位置。

第一处，在 `resultMap` 中增加：

```xml
<result property="payload" column="payload"/>
```

第二处，在 `ConsumedEventColumns` 的 `payload_hash` 后增加：

```sql
payload_hash,
payload,
process_status,
```

第三处，在 `insertReceived` 的列和值中分别增加：

```sql
payload_hash,
payload,
process_status,
```

```xml
#{payloadHash},
#{payload},
0,
```

插入列和值的顺序必须对应。完成后确认 `consumer_retry_count`、`last_attempt_at`、`last_error`、`redis_applied_at`、`flushed_at` 仍在 `resultMap` 和列清单中，不能因为增加 `payload` 把原字段删掉。

### 6.10 阶段验证

重建本地数据库后执行：

```sql
SHOW COLUMNS FROM video_status_consumed_event;
SHOW INDEX FROM video_status_consumed_event;
SHOW COLUMNS FROM video_status_flush_batch;
SHOW COLUMNS FROM video_status_consumption_repair_log;
```

必须确认：

- `payload` 存在；
- `aggregate_sequence`、`committed_at`、`applied_sequence` 不存在；
- eventId 唯一键存在；
- 状态恢复索引存在；
- Java 实体字段类型与 SQL 一致。

运行：

```powershell
.\mvnw.cmd -DskipTests compile
```

---

## 7. 阶段 2：完成 V2 事件与 Outbox

### 7.1 目标

业务事务只创建 V2 事件，不再生成严格递增序号。

### 7.2 修改事件模型

文件：

```text
src/main/java/com/feibijiubi/backend/event/VideoStatusChangedEvent.java
```

最终组件：

```java
public record VideoStatusChangedEvent(
        String eventId,
        Integer vid,
        VideoStatusEventType type,
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
        if (type == null || delta == null || delta == 0) {
            throw new IllegalArgumentException("统计类型或增量不合法");
        }
        double expected = type.calculateHotScore(delta);
        if (hotScoreDelta == null
                || !Double.isFinite(hotScoreDelta)
                || Double.compare(hotScoreDelta, expected) != 0) {
            throw new IllegalArgumentException("热门分数增量不合法");
        }
        if (occurredAt == null || !Integer.valueOf(2).equals(schemaVersion)) {
            throw new IllegalArgumentException("消息版本或时间不合法");
        }
    }
}
```

事件模型使用 `IllegalArgumentException` 表达不变量失败，不依赖 HTTP 或 MQ 异常。消费者的 `parseAndValidate()` 再把它包装为 `NonRetryableMessageException`。

### 7.3 修改事件创建

`VideoStatusServiceImpl#createEvent(...)` 中：

1. 删除 `VideoStatusSequenceMapper` 依赖；
2. 删除 ensure/increase/select sequence；
3. 创建 `schemaVersion=2` 事件；
4. 事件 JSON 写入 Outbox；
5. 业务事实与 Outbox 仍在同一个事务中提交。

```java
VideoStatusChangedEvent event = new VideoStatusChangedEvent(
        UUID.randomUUID().toString(),
        vid,
        type,
        delta,
        type.calculateHotScore(delta),
        LocalDateTime.now(),
        2,
        null
);
```

### 7.4 删除 sequence

确认删除：

```text
VideoStatusSequenceMapper.java
VideoStatusSequenceMapper.xml
VideoStatusChangedEvent.aggregateSequence
VideoStatusOutbox.aggregateSequence
video_status_outbox.aggregate_sequence
video_status_sequence 表
```

### 7.5 对齐 Outbox Mapper

检查以下方法不再读写 `aggregate_sequence`：

```text
insert
claimBatch
selectById
```

可复用当前仓库的可靠模式：

- `VideoStatusOutboxClaimService#claimBatch()` 的独立事务；
- `VideoStatusOutboxMapper.xml#claimBatch` 的 `FOR UPDATE SKIP LOCKED`；
- `VideoStatusEventPublisher#publish` 的 Confirm/Return 判断。

### 7.6 新增事件测试

新增：

```text
src/test/java/com/feibijiubi/backend/event/VideoStatusChangedEventTests.java
```

至少测试：

- schemaVersion 不是 2 时失败；
- 热门分数与 type/delta 不一致时失败；
- eventId、vid、type、delta 为空或非法时失败；
- 合法事件通过。

验证：

```powershell
.\mvnw.cmd '-Dtest=VideoStatusChangedEventTests' test
.\mvnw.cmd -DskipTests compile
```

---

# 第三部分：先完成 Redis，再接消费者

## 8. 阶段 3：固定 Redis Key、Lua 契约和脚本 Bean

### 8.1 目标

先让 Redis V2 的输入输出契约完全确定。消费者只能调用已经验证的 Redis Service，不能直接拼装 Lua 参数。

### 8.2 最终 Redis Key

| 用途 | Key |
|---|---|
| 实时统计 | `video:status:v2:{vid}` |
| 待刷增量 | `video:status:delta:v1:{vid}` |
| 脏视频集合 | `video:status:dirty:v1` |
| 消费幂等 | `video:status:processed:v2:{eventId}` |
| 初始化锁 | `video:status:init-lock:v1:{vid}` |
| 热门视频 | `feed:hot:videos:v2` |
| 清理幂等 | `video:status:flush-cleaned:v1:{batchId}` |

在 `RedisConstants` 和 `RedisKeyUtils` 中一次补齐以上 Key。旧 V1 Key 暂时不删除，阶段 13 再处理。

### 8.3 创建四个正式脚本

不要继续把新协议覆盖在旧文件上。创建：

```text
src/main/resources/lua/video-status-aggregate-v2.lua
src/main/resources/lua/video-status-init-v2.lua
src/main/resources/lua/video-status-delta-subtract-v1.lua
src/main/resources/lua/compare-and-delete.lua
```

完整脚本契约见附录 B。

聚合脚本固定参数：

```text
KEYS[1] current Hash
KEYS[2] delta Hash
KEYS[3] dirty Set
KEYS[4] processed event Key
KEYS[5] hot ZSet

ARGV[1] currentField
ARGV[2] deltaField
ARGV[3] delta
ARGV[4] vid
ARGV[5] hotScoreDelta
ARGV[6] processedTtlSeconds
```

禁止改变顺序。

### 8.4 注册脚本 Bean

修改：

```text
src/main/java/com/feibijiubi/backend/config/RedisScriptConfig.java
```

创建四个有明确 Bean 名称的脚本 Bean：

```text
DefaultRedisScript<String> videoStatusAggregateV2Script
DefaultRedisScript<String> videoStatusInitV2Script
DefaultRedisScript<String> videoStatusDeltaSubtractV1Script
DefaultRedisScript<Long>   compareAndDeleteScript
```

当前旧 `VideoStatusServiceImpl` 仍按类型注入一个 `DefaultRedisScript<String>`。如果直接增加四个 Bean，阶段 3 启动 Spring 上下文会出现多 Bean 歧义。因此本阶段还要：

1. 给现有 `videoStatusIncrementScript` 临时增加 `@Primary`；
2. 新的 Service 注入脚本时全部使用 `@Qualifier("具体Bean名")`；
3. 阶段 6 删除旧 `VideoStatusService.apply/rebuild/persist` 时，一并删除旧 `videoStatusIncrementScript` 和它的 `@Primary`。

不要依赖字段名猜 Bean，也不要复用含义不清楚的单个 `redisScript` Bean。

### 8.5 增加 Lua 结果枚举

新增或移动到 Redis 实时服务附近：

```java
public enum ApplyResult {
    APPLIED,
    DUPLICATE,
    NEEDS_REBUILD,
    NEGATIVE_RESULT,
    INVALID_FIELD,
    INVALID_REDIS_TYPE
}
```

初始化结果：

```java
public enum InitializeResult {
    INITIALIZED,
    ALREADY_INITIALIZED,
    INVALID_ARGUMENT,
    INVALID_REDIS_TYPE
}
```

清理结果：

```java
public enum DeltaCleanupResult {
    EMPTY,
    REMAINING,
    DUPLICATE_CLEANUP,
    GENERATION_CHANGED,
    NEEDS_REBUILD,
    INVALID_ARGUMENT
}
```

### 8.6 Lua 集成测试

新增：

```text
src/test/java/com/feibijiubi/backend/service/video/VideoStatusLuaIntegrationTests.java
```

使用专用测试 Redis database，测试前只清理由测试前缀创建的 Key，禁止对共享 Redis 执行无范围 `FLUSHDB`。

至少验证：

- APPLIED 同时更新 current、delta、dirty、hot、processed；
- 同 eventId 第二次返回 DUPLICATE，数值不变；
- current 或 delta 缺失返回 NEEDS_REBUILD；
- processed Key 仍存在但 current/delta 缺失时，也先返回 NEEDS_REBUILD；
- 负数结果返回 NEGATIVE_RESULT，所有 Key 均未部分写入；
- 字段非法和 Redis 类型错误不会部分写入。

验证：

```powershell
docker compose up -d redis
docker compose exec -T redis redis-cli ping
.\mvnw.cmd '-Dtest=VideoStatusLuaIntegrationTests' test
```

本阶段测试直接调用 Lua，验证的是“脚本契约和原子性”。Java Service 还没有创建，因此 Java Key/ARGV 组装顺序放到阶段 4 的 `VideoStatusRealtimeServiceIntegrationTests` 验证。禁止把尚不存在的 Java 调用当作本阶段退出门槛。

---

## 9. 阶段 4：完成 Redis 实时服务、安全初始化和查询降级

### 9.1 目标

在消费者切换前，先保证任何视频都能安全初始化 current/delta Hash，并且查询线程不会参与重建。

### 9.2 新增职责类

```text
src/main/java/com/feibijiubi/backend/service/video/VideoStatusRealtimeService.java
src/main/java/com/feibijiubi/backend/service/impl/video/VideoStatusRealtimeServiceImpl.java
src/main/java/com/feibijiubi/backend/service/video/VideoStatusRebuildService.java
src/main/java/com/feibijiubi/backend/service/impl/video/VideoStatusRebuildServiceImpl.java
src/main/java/com/feibijiubi/backend/service/video/VideoStatusRebuildSnapshotService.java
src/main/java/com/feibijiubi/backend/service/impl/video/VideoStatusRebuildSnapshotServiceImpl.java
src/main/java/com/feibijiubi/backend/service/video/VideoStatusRebuildSnapshot.java
src/main/java/com/feibijiubi/backend/service/video/VideoStatusVidMutex.java
```

接口：

```java
public interface VideoStatusRealtimeService {
    ApplyResult apply(VideoStatusChangedEvent event);
}

public interface VideoStatusRebuildService {
    void ensureInitialized(Integer vid);
}

public interface VideoStatusRebuildSnapshotService {
    VideoStatusRebuildSnapshot load(Integer vid);
}
```

`VideoStatusVidMutex` 用固定数量的 `ReentrantLock` 条带实现，例如 256 把锁，通过 `Math.floorMod(vid, locks.length)` 取锁。不要使用“解锁后从 Map 删除”的写法，否则等待线程与新线程可能拿到两把不同的锁。

它提供：

```java
<T> T withLock(Integer vid, Supplier<T> action);

void withLock(Integer vid, Runnable action);
```

本项目当前按单应用实例实现：同 vid 的消费者处理、flush 和人工 Redis 重建都必须经过这一个互斥器。它允许同 JVM 增加消费者线程，但不支持直接横向启动第二个应用实例；多实例前必须替换为带续租和 token 解锁的分布式锁。

### 9.3 增加初始化查询

在 `VideoStatusConsumedEventMapper` 增加：

```java
List<VideoStatusConsumedEvent> selectRebuildCandidates(
        @Param("vid") Integer vid
);
```

查询状态 0 和 1，并带上 `payload`：

```sql
SELECT id, event_id, vid, event_type, delta,
       payload, process_status
FROM video_status_consumed_event
WHERE vid = #{vid}
  AND process_status IN (0, 1)
ORDER BY id;
```

`VideoStatusRebuildSnapshotServiceImpl#load()` 在同一个 MySQL 只读事务中执行：

```text
videoStatusMapper.selectByVid(vid)
consumedEventMapper.selectRebuildCandidates(vid)
```

事务要求：

```java
@Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ
)
```

先把基线和待刷事件聚合成普通 Java DTO，事务结束后再写 Redis，不要在只读事务内长时间等待 Redis。

必须把事务方法放在独立的 `VideoStatusRebuildSnapshotServiceImpl` Bean 中，再由 `VideoStatusRebuildServiceImpl` 注入调用。不要在同一个类里 `this.loadSnapshot()` 自调用 `@Transactional` 方法；Spring 代理不会拦截这种调用，事务不会生效。

快照事务结束后，对候选事件分类：

```text
状态1 -> 一定计入 pending delta
状态0 且 processed event Key 存在 -> Redis 曾经应用，计入 pending delta
状态0 且 processed event Key 不存在 -> 尚不能证明已应用，不计入；以后由消息重投应用
```

状态 0 的 `hotScoreDelta` 从规范化 `payload` 解析，不能只靠 `event_type/delta` 猜。该规则用于修复“Redis 已成功、状态 1 落库前崩溃，随后 current/delta 丢失”的窗口。

### 9.4 实现安全初始化

`ensureInitialized(vid)` 严格执行：

1. 如果 current 和 delta 都存在，直接返回；
2. 如果只有一个存在，判定为 Redis 结构不一致，抛可重试异常并告警，不在普通消费线程强行覆盖；
3. 只有 current 和 delta 都不存在时才执行首次初始化；
4. `SET init-lock token NX EX 10s`；
5. 未获得锁时短暂重查 current/delta，仍不存在则抛可重试异常；
6. 通过独立 Snapshot Service 读取 MySQL 基线和状态 0/1 候选事件；
7. 状态 1 全部计入；状态 0 只计入仍存在 processed event Key 的事件；
8. `current = MySQL 基线 + 已确认应用的 pending delta`；
9. `delta = 已确认应用的 pending delta`，八个字段即使为 0 也完整写入；
10. 生成 UUID `generation`；
11. 调用初始化 Lua，仅当 current 不存在时写入；
12. 使用 `compare-and-delete.lua` 按 token 释放锁。

初始化锁使用现有 Spring Data Redis API：

```java
Boolean locked = stringRedisTemplate.opsForValue()
        .setIfAbsent(lockKey, token, Duration.ofSeconds(10));
```

禁止直接 `DELETE lockKey`，否则可能删除其他线程已经重新获得的锁。

### 9.5 实现正确的聚合 Lua 调用

`VideoStatusRealtimeServiceImpl#apply` 的 Key 顺序必须是：

```java
List<String> keys = List.of(
        RedisKeyUtils.videoStatusV2(event.vid()),
        RedisKeyUtils.videoStatusDelta(event.vid()),
        RedisKeyUtils.videoStatusDirty(),
        RedisKeyUtils.processedV2(event.eventId()),
        RedisConstants.FEED_HOT_VIDEOS_V2
);
```

参数顺序：

```java
String result = redisTemplate.execute(
        videoStatusAggregateV2Script,
        keys,
        event.type().redisField(),
        event.type().deltaField(),
        String.valueOf(event.delta()),
        String.valueOf(event.vid()),
        String.valueOf(event.hotScoreDelta()),
        String.valueOf(Duration.ofDays(
                properties.getRedisEventTtlDays()
        ).toSeconds())
);
```

如果第一次返回 `NEEDS_REBUILD`：

```text
ensureInitialized(vid)
-> 只重试一次 apply
-> 仍失败则抛 RetryableMessageException
```

### 9.6 修改查询路径

`getByVid()`：

```text
Redis current Hash 存在 -> 转换返回
Redis current Hash 不存在 -> 只查询 MySQL 返回
```

删除请求线程中的 `rebuild(vid)`。查询未命中时记录指标，但不写 Redis。

### 9.7 阶段测试

新增：

```text
VideoStatusRealtimeServiceIntegrationTests
VideoStatusRebuildServiceIntegrationTests
VideoStatusQueryServiceTests
```

至少验证：

- 初始化值等于 MySQL 基线加状态 1 增量；
- 状态 0 没有 processed Key 时不进入初始化值；
- 状态 0 有 processed Key 时进入初始化值，重投得到 DUPLICATE 后可安全标状态 1；
- current 和 delta 都被完整创建；
- current/delta 只缺一个时不会进入 `ALREADY_INITIALIZED` 死循环；
- 两个并发初始化只有一个覆盖正式 Key；
- 查询未命中只回源 MySQL；
- 聚合服务传入的 Key/ARGV 与 Lua 一致。
- Snapshot Service 的事务通过跨 Bean 调用真正生效。

验证：

```powershell
.\mvnw.cmd '-Dtest=VideoStatusRealtimeServiceIntegrationTests,VideoStatusRebuildServiceIntegrationTests,VideoStatusQueryServiceTests' test
```

---

# 第四部分：消费登记与消费者切换

## 10. 阶段 5：完成消费事件状态服务核心

### 10.1 目标

实现 ACK 前的 MySQL 耐久登记，并把旧 `persist()` 的幂等职责迁移到独立服务。

### 10.2 最终核心接口

先在 `VideoStatusProperties` 增加普通消费者也会使用的安全年龄：

```java
private int consumerRecoveryAutoReplayMaxAgeSeconds = 604_800;
```

并在配置校验中保证它小于 `redisEventTtlDays * 86400`。这项保护不能只放在阶段 11 的扫描任务里，因为旧 Main/Retry 消息可能先被普通消费者拿到。

```java
public interface VideoStatusConsumptionService {
    RegistrationResult register(
            VideoStatusChangedEvent event,
            String semanticPayloadHash
    );

    void markRedisApplied(String eventId);

    void recordConsumerFailure(
            String eventId,
            int attempt,
            String lastError
    );
}
```

### 10.3 创建语义摘要组件

新增：

```text
src/main/java/com/feibijiubi/backend/service/video/VideoStatusEventFingerprintService.java
src/main/java/com/feibijiubi/backend/service/impl/video/VideoStatusEventFingerprintServiceImpl.java
```

不要对 RabbitMQ 原始 JSON 字节直接计算 hash。

为避免分隔符歧义，使用长度前缀：

```java
private void appendField(StringBuilder builder, Object value) {
    String text = String.valueOf(value);
    builder.append(text.length()).append(':').append(text);
}
```

按固定顺序写入：

```text
eventId
vid
type
delta
hotScoreDelta
occurredAt
schemaVersion
traceId
```

最后对 UTF-8 字节计算 SHA-256。

### 10.4 实现 `register()`

新增：

```text
src/main/java/com/feibijiubi/backend/service/impl/video/VideoStatusConsumptionServiceImpl.java
```

事务：

```java
@Transactional(
        propagation = Propagation.REQUIRES_NEW,
        rollbackFor = Exception.class,
        noRollbackFor = RepairRequiredMessageException.class
)
```

步骤：

1. 使用统一 `ObjectMapper` 把已校验事件序列化为 `payload`；
2. 构造 `VideoStatusConsumedEvent`；
3. 插入状态 0，retry count 为 0，last attempt 为当前时间；
4. 插入成功返回 `NEEDS_REDIS_APPLY`；
5. 唯一键冲突后执行 `selectByEventIdForUpdate`；
6. 对比 `vid/eventType/delta/payloadHash`；
7. 内容不同抛 `NonRetryableMessageException`；
8. 状态 0 先比较 `consumed_at` 与 `now - consumerRecoveryAutoReplayMaxAgeSeconds`；
9. 状态 0 已超龄时，条件更新 `0 -> 3`、记录 `last_error`，然后抛 `RepairRequiredMessageException(message, true)`；因为配置了 `noRollbackFor`，状态 3 必须提交；
10. 状态 0 未超龄时刷新 `last_attempt_at`，返回 `NEEDS_REDIS_APPLY`；
11. 状态 1 返回 `REDIS_ALREADY_APPLIED`；
12. 状态 2 返回 `ALREADY_FLUSHED`；
13. 状态 3 也抛 `RepairRequiredMessageException(message, true)`，确保上一次 DLQ Confirm 失败并重新入队时不会静默 ACK。DLQ 采用至少一次告警语义，管理端按 eventId 去重展示。

### 10.5 实现 `markRedisApplied()`

使用独立事务执行：

```sql
UPDATE video_status_consumed_event
SET process_status = 1,
    redis_applied_at = NOW(3),
    last_error = NULL
WHERE event_id = #{eventId}
  AND process_status = 0;
```

影响行数为 0 时重新查询：

```text
状态 1/2 -> 幂等成功
状态 0   -> RetryableMessageException
状态 3   -> RepairRequiredMessageException(message, true)
不存在   -> RetryableMessageException
未知状态 -> NonRetryableMessageException
```

### 10.6 实现 `recordConsumerFailure()`

使用 `REQUIRES_NEW`：

```sql
UPDATE video_status_consumed_event
SET consumer_retry_count = GREATEST(consumer_retry_count, #{attempt}),
    last_attempt_at = NOW(3),
    last_error = #{lastError}
WHERE event_id = #{eventId}
  AND process_status = 0;
```

同步修改 Mapper 方法，增加 `@Param("attempt") int attempt`。错误文本统一截断到 1000 字符。记录失败失败时，只记录日志，不能覆盖原始业务异常。

### 10.7 Mapper 集成测试

新增：

```text
src/test/java/com/feibijiubi/backend/mapper/VideoStatusConsumedEventMapperIntegrationTests.java
src/test/java/com/feibijiubi/backend/service/video/VideoStatusConsumptionServiceIntegrationTests.java
```

测试必须连接 MySQL 8，不能用 H2 代替 `SKIP LOCKED` 和唯一键并发语义。

至少验证：

- 首次登记保存完整 payload；
- 相同 eventId、相同内容的状态 0/1/2 返回正确结果；
- 相同 eventId、不同内容不可重试；
- 状态 3 进入专用修复分支；
- `markRedisApplied` 的并发影响行数处理正确；
- 两个事务竞争同 eventId 最终只有一行。

验证：

```powershell
.\mvnw.cmd '-Dtest=VideoStatusConsumedEventMapperIntegrationTests,VideoStatusConsumptionServiceIntegrationTests' test
```

禁止进入下一阶段的情况：

- payload 仍为 null；
- register 事务尚未提交就调用 Redis；
- 状态服务仍更新 `video_status`；
- 重复 eventId 只比较 hash，不比较业务字段。

---

## 11. 阶段 6：完成可靠转发和最终消费者，但保持监听器关闭

### 11.1 目标

一次完成最终消费者代码，并在同一个阶段删除旧 `persist()`。代码完成后仍保持 RabbitMQ Listener `auto-startup=false`，直到批量刷库链路完成。

### 11.2 统一重试次数

使用应用 Header 作为权威重试次数：

```text
x-video-status-attempt
```

规则：

```text
首次消费：0
每次可靠转发到 Retry Exchange：原值 + 1
数据库 consumer_retry_count：保存已观察到的最大 attempt
状态0恢复消息：从数据库 retry count 恢复 Header
是否进入 DLQ：attempt >= consumerMaxRetries
```

RabbitMQ `x-death` 只用于诊断，不再同时充当业务重试权威值，避免数据库恢复消息重新创建后两套次数分叉。

### 11.3 扩展可靠 Forwarder

修改：

```text
src/main/java/com/feibijiubi/backend/mq/VideoStatusMessageForwarder.java
```

提供四个入口：

```java
void toRetry(Message original, int nextAttempt);

void toDead(Message original, String reason);

void toRetry(String eventId, String payload, int nextAttempt);

void toDead(String eventId, String payload, String reason);
```

要求：

1. 消息持久化；
2. 设置 `contentType=application/json`；
3. 设置 `x-video-status-attempt`；
4. 数据库恢复消息额外设置 `x-video-status-recovery=true` 和 eventId Header；
5. 使用 `CorrelationData` 等待 Confirm；
6. NACK、Return、超时、发送异常全部向外抛出。

复制原消息时使用 Spring AMQP 的克隆构建方式，不直接修改传入的原 `Message`。

可复用当前仓库：

- `VideoStatusEventPublisher#publish()` 的 Confirm/Return 判断；
- `VideoStatusMessageForwarder#publishConfirmed()` 的超时处理；
- `VideoStatusEventConsumer` 当前的“转发失败则 basicNack(requeue=true)”模式。

### 11.4 最终消费者依赖

最终 `VideoStatusEventConsumer` 注入：

```text
ObjectMapper
VideoStatusEventFingerprintService
VideoStatusConsumptionService
VideoStatusRealtimeService
VideoStatusMessageForwarder
VideoStatusProperties
VideoStatusVidMutex
```

不再注入 `VideoStatusService`。

### 11.5 最终处理顺序

主流程：

```java
private void process(Message message) {
    VideoStatusChangedEvent event = parseAndValidate(message);
    vidMutex.withLock(event.vid(), () -> processLocked(event));
}

private void processLocked(VideoStatusChangedEvent event) {
    String hash = fingerprintService.hash(event);

    RegistrationResult registration =
            consumptionService.register(event, hash);

    if (registration == RegistrationResult.REDIS_ALREADY_APPLIED
            || registration == RegistrationResult.ALREADY_FLUSHED) {
        return;
    }

    ApplyResult result = realtimeService.apply(event);
    switch (result) {
        case APPLIED, DUPLICATE ->
                consumptionService.markRedisApplied(event.eventId());
        case NEEDS_REBUILD ->
                throw new RetryableMessageException("Redis 初始化仍失败");
        case NEGATIVE_RESULT ->
                throw new RetryableMessageException("负增量暂时无法应用");
        case INVALID_FIELD, INVALID_REDIS_TYPE ->
                throw new NonRetryableMessageException("Redis 数据结构非法");
    }
}
```

锁的范围必须覆盖 `register -> Redis apply -> markRedisApplied`，不能只包 Lua。这样阶段 11 的受控重建才能与普通消费互斥。

### 11.6 完整异常分支

消费者外层必须区分消息是否已经成功解析并登记。

| 异常 | 数据库中有登记记录 | 动作 |
|---|---:|---|
| `RepairRequiredMessageException(alertRequired=true)` | 是 | 原消息可靠发 DLQ，Confirm 后 ACK；禁止调用 Redis |
| `RetryableMessageException` / Redis /数据库暂时失败 | 可能 | 记录失败；未达上限可靠转 Retry，达到上限执行转修复 + DLQ |
| `NonRetryableMessageException`，且 eventId 冲突 | 是 | 原始冲突消息发 DLQ，不覆盖已有合法事件 |
| JSON 根本无法解析 | 否 | 原始消息可靠发 DLQ，不能调用数据库状态服务 |
| Forwarder 失败 | 不确定 | `basicNack(deliveryTag, false, true)` |
| 处理成功 | 是 | `basicAck(deliveryTag, false)` |

消费者本轮已经得到 eventId 后，失败处理顺序：

```text
attempt = headerAttempt(message)
nextAttempt = attempt + 1
尽力 recordConsumerFailure(eventId, nextAttempt, error)

nextAttempt <= consumerMaxRetries
    -> forwarder.toRetry(original, nextAttempt)
    -> Confirm 成功后 ACK 原消息

nextAttempt > consumerMaxRetries
    -> 阶段11完成前：可靠发送原消息到 DLQ，ACK 原消息
    -> 阶段11完成后：调用 forwardDeadAndMarkRepairRequired(...)
```

阶段 11 之前发生的 DLQ 事件必须作为人工待办保留，不能删除。

### 11.7 同时删除旧消费路径

在消费者切换代码提交中删除：

```text
VideoStatusService.persist(...)
VideoStatusServiceImpl.persist(...)
VideoStatusServiceImpl.handleDuplicate(...)
selectByEventIdLegacy(...)
markLegacyFlushed(...)
消费者中的 videoStatusService.apply(...)
消费者逐条调用 videoStatusMapper.applyDelta(...)
RedisScriptConfig 中旧 videoStatusIncrementScript Bean 及临时 @Primary
```

Redis `apply()` 移到 `VideoStatusRealtimeService` 后，也从 `VideoStatusService` 删除旧声明。

旧 `rebuild()` 移到 `VideoStatusRebuildService` 后删除旧声明。

### 11.8 消费者测试

新增：

```text
src/test/java/com/feibijiubi/backend/mq/VideoStatusEventConsumerTests.java
src/test/java/com/feibijiubi/backend/mq/VideoStatusEventConsumerIntegrationTests.java
```

单元测试验证分支；集成测试使用真实 RabbitMQ 验证 Confirm、Return、ACK 和 NACK。

至少覆盖：

- 首次事件执行 register → Redis → mark；
- 状态 1/2 不再执行 Redis；
- Redis APPLIED 后 mark 失败时原消息不 ACK；
- Redis DUPLICATE 后仍能标记状态 1；
- Forwarder 失败时 NACK 并重新入队；
- 无法解析的毒消息直接 DLQ；
- 状态 3 重复消息可靠发 DLQ 后 ACK，不进入 Retry 循环；管理查询按 eventId 去重；
- 已登记状态 0 超过自动重投年龄时直接转状态 3 + DLQ，普通消费者不会调用 Redis；

验证：

```powershell
docker compose up -d rabbitmq
docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping
.\mvnw.cmd '-Dtest=VideoStatusEventConsumerTests,VideoStatusEventConsumerIntegrationTests' test
.\mvnw.cmd -DskipTests compile
```

完成后仍保持：

```yaml
spring.rabbitmq.listener.simple.auto-startup: false
```

---

# 第五部分：完成批量刷库

## 12. 阶段 7：完成批量刷库模型和 Mapper

### 12.1 目标

把“状态 1 事件 → 一次 MySQL UPDATE → 状态 2”所需的所有数据库接口一次补齐。

### 12.2 扩展 `VideoStatusDelta`

当前 record 保留八个 long 字段，增加：

```java
public static VideoStatusDelta zero() {
    return new VideoStatusDelta(0, 0, 0, 0, 0, 0, 0, 0);
}

public static VideoStatusDelta from(
        VideoStatusConsumedEvent event
) {
    long value = event.getDelta();
    return switch (VideoStatusEventType.valueOf(event.getEventType())) {
        case PLAY -> new VideoStatusDelta(value, 0, 0, 0, 0, 0, 0, 0);
        case LIKE -> new VideoStatusDelta(0, value, 0, 0, 0, 0, 0, 0);
        case UNLIKE -> new VideoStatusDelta(0, 0, value, 0, 0, 0, 0, 0);
        case COMMENT -> new VideoStatusDelta(0, 0, 0, value, 0, 0, 0, 0);
        case COIN -> new VideoStatusDelta(0, 0, 0, 0, value, 0, 0, 0);
        case SHARE -> new VideoStatusDelta(0, 0, 0, 0, 0, value, 0, 0);
        case COLLECT -> new VideoStatusDelta(0, 0, 0, 0, 0, 0, value, 0);
        case DANMU -> new VideoStatusDelta(0, 0, 0, 0, 0, 0, 0, value);
    };
}

public VideoStatusDelta plus(VideoStatusDelta other) {
    return new VideoStatusDelta(
            playDelta + other.playDelta,
            likeDelta + other.likeDelta,
            unlikeDelta + other.unlikeDelta,
            commentDelta + other.commentDelta,
            coinDelta + other.coinDelta,
            shareDelta + other.shareDelta,
            collectDelta + other.collectDelta,
            danmuDelta + other.danmuDelta
    );
}

public boolean isZero() {
    return playDelta == 0
            && likeDelta == 0
            && unlikeDelta == 0
            && commentDelta == 0
            && coinDelta == 0
            && shareDelta == 0
            && collectDelta == 0
            && danmuDelta == 0;
}
```

聚合事件时只使用：

```java
events.stream()
        .map(VideoStatusDelta::from)
        .reduce(VideoStatusDelta.zero(), VideoStatusDelta::plus);
```

禁止根据消息字段动态拼 SQL 列名。

### 12.3 扩展 consumed-event Mapper

增加：

```java
List<VideoStatusConsumedEvent> selectPendingForUpdate(
        @Param("vid") Integer vid,
        @Param("limit") int limit
);

int markFlushed(@Param("ids") List<Long> ids);

int markFlushRepairRequired(
        @Param("ids") List<Long> ids,
        @Param("lastError") String lastError
);

int countPendingByVid(@Param("vid") Integer vid);

List<Integer> selectPendingVids(@Param("limit") int limit);
```

`selectPendingForUpdate`：

```sql
SELECT id,
       event_id,
       vid,
       event_type,
       delta,
       process_status,
       redis_applied_at
FROM video_status_consumed_event
WHERE vid = #{vid}
  AND process_status = 1
ORDER BY CASE WHEN delta > 0 THEN 0 ELSE 1 END,
         id
LIMIT #{limit}
FOR UPDATE SKIP LOCKED;
```

XML 中 `>` 写为 `&gt;`。

`markFlushed`：

```xml
<update id="markFlushed">
    UPDATE video_status_consumed_event
    SET process_status = 2,
        flushed_at = NOW(3),
        last_error = NULL
    WHERE process_status = 1
      AND id IN
      <foreach collection="ids" item="id"
               open="(" separator="," close=")">
          #{id}
      </foreach>
</update>
```

`selectPendingVids`：

```sql
SELECT DISTINCT vid
FROM video_status_consumed_event
WHERE process_status = 1
ORDER BY vid
LIMIT #{limit};
```

`markFlushRepairRequired` 只处理已经应用 Redis、但无法自动刷入 MySQL 的事件：

```xml
<update id="markFlushRepairRequired">
    UPDATE video_status_consumed_event
    SET process_status = 3,
        last_attempt_at = NOW(3),
        last_error = #{lastError}
    WHERE process_status = 1
      AND id IN
      <foreach collection="ids" item="id"
               open="(" separator="," close=")">
          #{id}
      </foreach>
</update>
```

`countPendingByVid` 使用 `COUNT(*)` 查询该 vid 是否还存在状态 1，用来决定异常后是否重新加入 dirty Set。

### 12.4 修改 `VideoStatusMapper`

把消费专用方法命名为：

```java
int applyBatchDelta(
        @Param("vid") Integer vid,
        @Param("delta") VideoStatusDelta delta
);
```

SQL 同时更新八个字段，并保留所有非负条件。不要使用 `GREATEST(..., 0)` 吞掉负增量。

原有请求线程同步点赞、收藏等 Mapper 方法暂时保留，因为 `async-enabled=false` 时仍需要它们。

### 12.5 创建 flush batch Mapper

新增：

```text
src/main/java/com/feibijiubi/backend/mapper/VideoStatusFlushBatchMapper.java
src/main/resources/com/feibijiubi/backend/mapper/VideoStatusFlushBatchMapper.xml
```

接口：

```java
int insert(VideoStatusFlushBatch batch);

VideoStatusFlushBatch selectByBatchIdForUpdate(String batchId);

List<VideoStatusFlushBatch> selectCleanupPending(
        @Param("limit") int limit
);

int markCleaned(String batchId);

int markGenerationSkipped(String batchId);

int recordCleanupFailure(
        @Param("batchId") String batchId,
        @Param("lastError") String lastError
);

int markCleanupRepairRequired(
        @Param("batchId") String batchId,
        @Param("lastError") String lastError
);
```

所有 cleanup 状态更新都带：

```sql
WHERE batch_id = #{batchId}
  AND cleanup_status = 0
```

`recordCleanupFailure` 保持状态 0，只增加次数：

```sql
UPDATE video_status_flush_batch
SET cleanup_retry_count = cleanup_retry_count + 1,
    last_attempt_at = NOW(3),
    last_error = #{lastError}
WHERE batch_id = #{batchId}
  AND cleanup_status = 0;
```

`selectByBatchIdForUpdate` 必须在事务内调用：

```sql
SELECT ...
FROM video_status_flush_batch
WHERE batch_id = #{batchId}
FOR UPDATE;
```

它用于串行化同一 `batchId` 的 cleanup。即使以后误启动两个 cleanup 实例，也不能让两个线程同时通过 PENDING 检查后重复扣减。

### 12.6 Mapper 测试

新增：

```text
VideoStatusBatchMapperIntegrationTests
```

至少验证：

- 两个事务使用 `SKIP LOCKED` 不会锁到同一批事件；
- 正增量优先；
- 非负条件失败时 UPDATE 影响行数为 0；
- `markFlushed` 只能更新状态 1；
- cleanup 状态只能从 0 迁移一次。

验证：

```powershell
.\mvnw.cmd '-Dtest=VideoStatusBatchMapperIntegrationTests' test
```

---

## 13. 阶段 8：完成批量刷库 Service 和 delta 清理 Service

### 13.1 目标

先完成可被手动调用和测试的 Service，再编写定时任务。

### 13.2 创建结果对象

新增：

```text
src/main/java/com/feibijiubi/backend/service/video/FlushResult.java
```

至少包含：

```java
String batchId;
String redisGeneration;
Integer vid;
List<Long> consumedEventIds;
VideoStatusDelta delta;
boolean empty;
```

### 13.3 创建批量刷库 Service

```text
src/main/java/com/feibijiubi/backend/service/video/VideoStatusBatchFlushService.java
src/main/java/com/feibijiubi/backend/service/impl/video/VideoStatusBatchFlushServiceImpl.java
```

核心方法：

```java
FlushResult flushOneVideo(
        Integer vid,
        int limit,
        String redisGeneration
);

void markRepairRequired(
        List<Long> consumedEventIds,
        String lastError
);
```

新增：

```text
src/main/java/com/feibijiubi/backend/common/VideoStatusFlushDataException.java
```

异常中保存 `vid`、本次锁定的 `consumedEventIds` 和错误摘要。它只表示确定的数据错误，例如非负保护导致 `applyBatchDelta()` 影响 0 行；连接超时、死锁等数据库异常不要包装成它。

实现必须位于一个 MySQL 事务：

```text
selectPendingForUpdate
-> 聚合 delta
-> delta 非 0 时 applyBatchDelta
-> markFlushed，影响行数必须等于事件数
-> insert flush_batch
-> 返回 FlushResult
```

核心实现骨架：

```java
@Override
@Transactional(rollbackFor = Exception.class)
public FlushResult flushOneVideo(
        Integer vid,
        int limit,
        String redisGeneration
) {
    List<VideoStatusConsumedEvent> events =
            consumedEventMapper.selectPendingForUpdate(vid, limit);
    if (events.isEmpty()) {
        return FlushResult.empty(vid, redisGeneration);
    }

    VideoStatusDelta delta = events.stream()
            .map(VideoStatusDelta::from)
            .reduce(VideoStatusDelta.zero(), VideoStatusDelta::plus);

    List<Long> ids = events.stream()
            .map(VideoStatusConsumedEvent::getId)
            .toList();

    if (!delta.isZero()
            && videoStatusMapper.applyBatchDelta(vid, delta) != 1) {
        throw new VideoStatusFlushDataException(
                vid,
                ids,
                "视频统计批量更新违反数据约束"
        );
    }

    if (consumedEventMapper.markFlushed(ids) != ids.size()) {
        throw new RetryableMessageException("事件FLUSHED数量不匹配");
    }

    VideoStatusFlushBatch batch =
            VideoStatusFlushBatch.create(
                    UUID.randomUUID().toString(),
                    vid,
                    redisGeneration,
                    delta
            );
    if (flushBatchMapper.insert(batch) != 1) {
        throw new RetryableMessageException("刷库批次写入失败");
    }

    return FlushResult.completed(batch, ids, delta);
}
```

同步在 `VideoStatusFlushBatch` 和 `FlushResult` 中实现这里使用的两个静态工厂方法；不要在 Service 中写一长串 setter 后漏字段。

事务注解：

```java
@Transactional(rollbackFor = Exception.class)
```

净增量为 0 时跳过 `video_status UPDATE`，但仍然 markFlushed 和插入 flush batch。

如果 `applyBatchDelta()` 返回 0：

1. 抛 `VideoStatusFlushDataException`，让当前刷库事务回滚；
2. 不标记任何事件 FLUSHED；
3. Scheduler 捕获该异常后，调用代理对象上的 `markRepairRequired(ids, error)`；
4. `markRepairRequired` 使用 `REQUIRES_NEW`，执行状态 `1 -> 3`；
5. 再用 `countPendingByVid(vid)` 刷新 dirty Set：仍有状态 1 就 `SADD`，否则 `SREM`，避免同一坏数据无限热循环；
6. Redis 中这批事件的 current/delta 暂时保留，等待阶段 11 的修复流程处理，绝不能直接减掉或截断为 0。

数据库连接失败、死锁、事务超时等瞬时异常不进入状态 3：当前事务回滚后重新 `SADD dirty`，由下一轮重试。

### 13.4 创建 delta 清理 Service

新增：

```text
src/main/java/com/feibijiubi/backend/service/video/VideoStatusDeltaCleanupService.java
src/main/java/com/feibijiubi/backend/service/impl/video/VideoStatusDeltaCleanupServiceImpl.java
```

本阶段立即在 `VideoStatusProperties` 增加，因为下面的 Service 已经要读取它：

```java
private int cleanupMaxAttempts = 10;
```

接口：

```java
void cleanup(String batchId);

int retryPending(int limit);
```

`cleanup(batch)`：

`cleanup(batchId)` 整体使用 `REQUIRES_NEW`，严格按以下顺序：

1. `selectByBatchIdForUpdate(batchId)` 锁定并重新读取 batch；
2. 已经是 `CLEANED/SKIPPED_GENERATION_CHANGED` 时直接返回，不执行 Lua；
3. 不是 `PENDING` 时记录状态并返回，不擅自改状态；
4. 调用 `video-status-delta-subtract-v1.lua`；
5. Lua 在完成扣减后写入无 TTL 的 `flush-cleaned:{batchId}` 标记；
6. `EMPTY/REMAINING/DUPLICATE_CLEANUP` → 在当前独立事务中标 `CLEANED`；
7. 注册事务 `afterCommit` 回调，提交成功后再尽力删除 `flush-cleaned:{batchId}`；
8. `GENERATION_CHANGED` → 在当前事务中标 `SKIPPED_GENERATION_CHANGED`；
9. `NEEDS_REBUILD/INVALID_ARGUMENT` → `recordCleanupFailure`，保持 `PENDING`；
10. `cleanup_retry_count + 1 >= cleanupMaxAttempts` 时改标 cleanup `REPAIR_REQUIRED`。

第 7 步可用 Spring 的事务同步回调实现。删除失败只会留下一个无害的 Redis Key。不能在 MySQL 的 `CLEANED` 事务提交前删除该 Key；否则进程在两步之间崩溃时，同一批次会再次扣减。这里也不能给 Key 设置 TTL，因为 MySQL 故障时间可能超过 TTL。

MySQL 刷库事务已经提交后，cleanup 失败绝不能再次调用 `applyBatchDelta()`。

### 13.5 Service 测试

新增：

```text
VideoStatusBatchFlushServiceIntegrationTests
VideoStatusDeltaCleanupIntegrationTests
```

至少验证：

- 1000 条同 vid 事件只产生一次或少量 UPDATE；
- applyBatchDelta 失败时 markFlushed 和 batch insert 一起回滚；
- markFlushed 数量不匹配时整体回滚；
- batch insert 失败时整体回滚；
- 净增量 0 正常完成；
- cleanup 重复调用不会重复相减；
- generation 变化时跳过旧批次。
- 非负保护返回 0 时，刷库事务回滚，随后独立事务把所选状态 1 事件标为状态 3；
- 瞬时数据库异常只重试，不误标状态 3；
- 状态 3 的 Redis delta 不会被 cleanup 误删。

验证：

```powershell
.\mvnw.cmd '-Dtest=VideoStatusBatchFlushServiceIntegrationTests,VideoStatusDeltaCleanupIntegrationTests' test
```

---

## 14. 阶段 9：完成 flush、dirty 恢复和 cleanup Scheduler

### 14.1 目标

把阶段 8 的 Service 接入定时调度，并保证 dirty Set 丢失后可以从 MySQL 恢复。

### 14.2 配置项

在 `VideoStatusProperties` 增加：

```java
private long flushFixedDelayMs = 500;
private int flushDirtyBatchSize = 100;
private int flushEventBatchSize = 1000;
private long flushRecoveryFixedDelayMs = 5000;
private long cleanupFixedDelayMs = 1000;
private int cleanupBatchSize = 100;
// cleanupMaxAttempts 已在阶段 8 增加，此处直接复用
private boolean schedulingEnabled = false;
```

### 14.3 增加 Redis Set 批量弹出能力

修改：

```text
src/main/java/com/feibijiubi/backend/utils/redis/operation/RedisSetOperations.java
```

提供：

```java
Set<String> pop(String key, long count);
```

空 Set 返回空集合，不返回 null。

### 14.4 Flush Scheduler

新增：

```text
src/main/java/com/feibijiubi/backend/mq/VideoStatusBatchFlushScheduler.java
```

每轮：

```text
从 dirty Set 弹出最多 N 个 vid
-> 读取 current Hash 的 generation
-> 使用 VideoStatusVidMutex 锁定 vid
-> generation 缺失则 ensureInitialized，仍缺失则重新 SADD dirty
-> flushOneVideo
-> 事务提交后 cleanup(batch)
-> 如果 MySQL 仍有状态1事件，重新 SADD dirty
-> 释放 vid 锁
```

异常分支必须单独写，不要统一 `catch (Exception)` 后无限重试：

```text
VideoStatusFlushDataException
-> REQUIRES_NEW 把异常携带的状态1事件改为状态3
-> 根据 countPendingByVid 刷新 dirty 成员关系
-> 记录 repair-required 指标和告警

死锁/连接超时/其他瞬时数据库异常
-> 当前事务已经回滚
-> SADD dirty
-> 下轮重试
```

第一版 Scheduler 单线程，并且整个单 vid 流程使用 `VideoStatusVidMutex`。该互斥器只覆盖当前 JVM，所以不要启动第二个应用实例；多实例部署前必须换成真正的分布式锁。

### 14.5 Dirty 恢复 Scheduler

新增：

```text
src/main/java/com/feibijiubi/backend/mq/VideoStatusDirtyRecoveryScheduler.java
```

每隔 5 秒：

```text
selectPendingVids(limit)
-> SADD dirty Set
```

MySQL 状态 1 是真相源，dirty Set 只负责减少扫描。

### 14.6 Cleanup 恢复 Scheduler

新增：

```text
src/main/java/com/feibijiubi/backend/mq/VideoStatusDeltaCleanupScheduler.java
```

每轮：

```text
selectCleanupPending(limit)
-> 逐批 cleanup
```

某一批失败不能阻止其他批次继续；每个 batch 单独捕获并记录错误。

### 14.7 Scheduler 测试

新增：

```text
VideoStatusBatchFlushSchedulerTests
VideoStatusDirtyRecoveryIntegrationTests
VideoStatusDeltaCleanupSchedulerTests
```

至少验证：

- SPOP 后崩溃，MySQL 扫描能重新加入 dirty；
- cleanup 前崩溃，PENDING batch 能被恢复；
- generation 缺失不会创建空 generation 批次；
- 单个 vid 失败不会终止整轮调度。

验证：

```powershell
.\mvnw.cmd '-Dtest=VideoStatusBatchFlushSchedulerTests,VideoStatusDirtyRecoveryIntegrationTests,VideoStatusDeltaCleanupSchedulerTests' test
```

---

# 第六部分：核心链路联调与生产闭环

## 15. 阶段 10：本地核心链路联调

### 15.1 目标

完成可演示的 V2 核心链路。此阶段结束后可以开启单消费者，但还没有状态 0 主动扫描和状态 3 管理后台。

### 15.2 启动基础设施

```powershell
docker compose up -d redis rabbitmq
docker compose ps
docker compose exec -T redis redis-cli ping
docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping
```

MySQL 需要单独可用。确认版本：

```sql
SELECT VERSION();
```

要求 MySQL 8，支持 `SKIP LOCKED`。

如果默认 RabbitMQ vhost 中存在旧 V1 消息，不要清队列。为本次联调创建独立 vhost：

```powershell
docker compose exec -T rabbitmq rabbitmqctl add_vhost video_status_v2_test
docker compose exec -T rabbitmq rabbitmqctl set_permissions -p video_status_v2_test guest ".*" ".*" ".*"
```

本地联调配置增加：

```yaml
spring.rabbitmq.virtual-host: video_status_v2_test
```

应用启动后会在新 vhost 自动声明 Main/Retry/DLQ。阶段 0 保留的旧队列继续留在默认 vhost，二者不会竞争。若这个 vhost 已存在且不确定是否干净，换一个新的名称，不要直接 purge。

### 15.3 先启动应用但关闭消费者

保持：

```yaml
spring.rabbitmq.listener.simple.auto-startup: false
app.video-status.async-enabled: false
app.video-status.scheduling-enabled: false
```

启动：

```powershell
.\mvnw.cmd spring-boot:run
```

确认：

- Spring 上下文启动；
- 所有 Mapper XML 可以加载；
- Lua Bean 可以加载；
- Scheduler 不会处理真实流量；
- 没有 Rabbit Listener 消费消息。

### 15.4 启用 V2 单消费者

先按 `Ctrl+C` 停止阶段 15.3 启动的应用。配置文件不会热加载，不能只修改 YAML 后继续使用旧进程。

在刚创建的 V2 隔离 vhost 上设置：

```yaml
app.video-status.async-enabled: true
app.video-status.scheduling-enabled: true

spring.rabbitmq.listener.simple:
  auto-startup: true
  prefetch: 20
```

容器工厂第一版：

```java
factory.setConcurrentConsumers(1);
factory.setMaxConcurrentConsumers(1);
```

重新启动应用：

```powershell
.\mvnw.cmd spring-boot:run
```

从启动日志确认 Listener 和三个 Scheduler 已启动后，才进入下一节触发 PLAY。

### 15.5 端到端验证顺序

依次执行，不要直接压测：

1. 创建一个测试视频和 `video_status` 默认行；
2. 触发一次 PLAY；
3. 检查 Outbox 最终 SENT；
4. 检查 consumed event 状态从 0 到 1；
5. 检查 Redis current +1；
6. 检查 Redis delta +1；
7. 检查 dirty Set 包含 vid；
8. 等待 flush Scheduler；
9. 检查 `video_status` +1；
10. 检查 consumed event 状态 2；
11. 检查 flush batch CLEANED；
12. 检查 Redis delta 已减去本批次。

SQL：

```sql
SELECT *
FROM video_status_outbox
ORDER BY id DESC
LIMIT 10;

SELECT event_id, vid, process_status,
       consumer_retry_count, last_error,
       redis_applied_at, flushed_at
FROM video_status_consumed_event
ORDER BY id DESC
LIMIT 10;

SELECT *
FROM video_status_flush_batch
ORDER BY id DESC
LIMIT 10;
```

队列：

```powershell
docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```

### 15.6 核心完成门槛

- [ ] 相同 eventId 重放不会重复增加 Redis。
- [ ] Redis APPLIED 后、mark 前模拟失败，重投由 DUPLICATE 恢复。
- [ ] mark 后、ACK 前模拟失败，重投不再调用 Redis。
- [ ] 多条同 vid 事件能聚合成一次或少量 MySQL UPDATE。
- [ ] MySQL 刷库失败时事件不进入状态 2。
- [ ] cleanup 失败不重复增加 MySQL。
- [ ] dirty Set 丢失能由 MySQL 恢复。
- [ ] 全量测试通过。

先按 `Ctrl+C` 停止正在运行的联调应用，避免它的 Listener/Scheduler 与测试进程竞争同一 MySQL、Redis 和 RabbitMQ。集成测试最好使用另一个专用 vhost 和 Redis database。

```powershell
.\mvnw.cmd test
```

如果这是学习或面试项目，可以先在这里形成一个稳定里程碑。继续以下阶段可完成生产恢复闭环。

---

## 16. 阶段 11：实现状态 0 恢复和状态 3 管理

### 16.1 目标

解决“MySQL 已登记状态 0，但普通 MQ 重投链路长期没有推进”的极端情况，并为状态 3 提供真实处置入口。

### 16.2 扩展配置

```java
private long consumerRecoveryFixedDelayMs = 30_000;
private int consumerRecoveryExpireSeconds = 60;
private int consumerRecoveryBatchSize = 50;
private int consumerRecoveryMaxAttempts = 10;
// consumerRecoveryAutoReplayMaxAgeSeconds 已在阶段 5 增加
private long repairOperationFixedDelayMs = 5000;
private int repairOperationBatchSize = 20;
```

必须满足：

```text
consumerRecoveryExpireSeconds
    > 单次正常消费最坏耗时 + 安全余量

expireSeconds * maxAttempts
    < autoReplayMaxAgeSeconds

autoReplayMaxAgeSeconds
    << redisEventTtlDays * 86400
```

示例值表示状态 0 最多自动重投 7 天，而 Redis eventId Key 保留 30 天。超过自动重投年龄后，不再猜测 Redis 是否已经应用，直接进入状态 3 的受控修复。Redis 必须使用不会主动淘汰幂等 Key 的策略；如果发生过 Redis 数据丢失，也禁止走自动重投判断。

### 16.3 扩展状态服务接口

增加：

```java
void forwardDeadAndMarkRepairRequired(
        String eventId,
        String lastError
);

int recoverExpiredReceived(
        LocalDateTime expiredBefore,
        int limit
);

void resumeAfterRepair(
        String eventId,
        String reason,
        String operator
);

void confirmIgnored(
        String eventId,
        String reason,
        String operator
);
```

### 16.4 状态 0 恢复

新增：

```text
src/main/java/com/feibijiubi/backend/mq/VideoStatusConsumptionRecoveryScheduler.java
```

Service 使用 `REQUIRES_NEW`：

```text
selectExpiredReceivedForUpdate

consumedAt 已超过 autoReplayMaxAge
  -> 条件更新 0 -> 3
  -> 从 payload 构造 DLQ 告警并等待 Confirm
  -> 不自动发 Retry

retryCount < max
  -> 从 payload 构造 Retry 消息
  -> Header attempt = retryCount + 1
  -> 等待 Confirm
  -> markRecoveryRepublished

retryCount >= max
  -> 条件更新 0 -> 3
  -> 从 payload 构造 DLQ 消息
  -> 等待 Confirm
```

Retry/DLQ 失败时抛异常回滚，记录保持状态 0。

RabbitMQ Confirm 成功但 MySQL 提交失败时可能重复发布，这是允许的；eventId 幂等吸收重复。

即使 `retryCount` 尚未达到上限，只要超过 `consumerRecoveryAutoReplayMaxAgeSeconds` 也必须停止自动重投。这样不会让状态 0 沉睡到 eventId 幂等 Key 过期后再盲目应用 Redis。

### 16.5 原消费者 DLQ 闭环

消费者达到最大次数后改为调用：

```java
forwardDeadAndMarkRepairRequired(eventId, lastError);
```

该方法独立事务内：

```text
锁定 event
-> 只允许状态 0 或 1 转 3
-> 发送数据库 payload 到 DLQ
-> Confirm 失败回滚到调用前状态
```

状态 2/3 重复调用按幂等成功返回，不重复发 DLQ。

无法解析、没有 eventId 的毒消息仍然只能转发原始 Message，不能创建 consumed event。

### 16.6 状态 3 管理和审计

新增：

```text
src/main/java/com/feibijiubi/backend/mapper/VideoStatusConsumptionRepairLogMapper.java
src/main/resources/com/feibijiubi/backend/mapper/VideoStatusConsumptionRepairLogMapper.xml
src/main/java/com/feibijiubi/backend/dto/VideoStatusRepairActionDTO.java
src/main/java/com/feibijiubi/backend/vo/VideoStatusRepairEventVO.java
src/main/java/com/feibijiubi/backend/controller/VideoStatusConsumptionRepairController.java
src/main/java/com/feibijiubi/backend/mq/VideoStatusRepairOperationRecoveryScheduler.java
src/main/java/com/feibijiubi/backend/common/MaintenanceInProgressException.java
```

```java
public class MaintenanceInProgressException extends RuntimeException {
    public MaintenanceInProgressException(String message) {
        super(message);
    }
}
```

Repair Log Mapper 至少提供：

```java
int insertStarted(VideoStatusConsumptionRepairLog operation);

VideoStatusConsumptionRepairLog selectByOperationIdForUpdate(
        String operationId
);

List<VideoStatusConsumptionRepairLog> selectStarted(
        @Param("limit") int limit
);

int markCompleted(String operationId);

int recordFailure(
        @Param("operationId") String operationId,
        @Param("lastError") String lastError
);

int markFailed(
        @Param("operationId") String operationId,
        @Param("lastError") String lastError
);

int countStartedByVid(Integer vid);
```

`countStartedByVid` 通过 repair log 与 consumed event 的 `event_id` 关联查询。阶段 11 完成后，消费者和 Flush Scheduler 在取得 `VideoStatusVidMutex` 后都先检查它：该 vid 有 `STARTED` 修复任务时，消费者抛专用维护异常，flush 重新 `SADD dirty` 后返回。这样应用重启后也不会穿过未完成的修复窗口。

扩展 Snapshot Service：

```java
VideoStatusRebuildSnapshot loadForRepair(
        Integer vid,
        @Nullable String includedRepairEventId
);
```

它始终包含状态 1 和有 processed fence 的状态 0；RESUME 传入当前 eventId，额外包含该状态 3，IGNORE 传 `null`。最终 Redis Recovery Service 只接收快照，不自己重新写一套聚合规则。

这里消费者抛的是专用 `MaintenanceInProgressException`，不能复用普通 `RetryableMessageException`。扩展 Forwarder：

```java
void toMaintenanceRetry(Message original, int currentAttempt);
```

它把消息送入 Retry Exchange，但保持 `x-video-status-attempt` 不变，并增加 `x-video-status-maintenance=true`。Confirm 成功后 ACK 原消息；失败则 NACK requeue。维护避让不增加业务重试次数、不调用 `recordConsumerFailure`，也不会因为维护时间较长误进 DLQ。

插入 STARTED 任务遇到 `uk_video_status_repair_active_event` 冲突时，查询现有活动任务：同 action 的重复请求返回现有 operationId；RESUME 与 IGNORE 冲突返回 HTTP 409。恢复 Scheduler 发现任务与事件现状不兼容时必须 `markFailed`，不能永远保留 STARTED 阻塞整个 vid。

同步修改 `VideoStatusConsumedEventMapper`：删除当前会执行 `3 -> 0` 的 `resetRepairToReceived`，改为：

```java
int resumeRepairToPendingFlush(String eventId);
```

```sql
UPDATE video_status_consumed_event
SET process_status = 1,
    consumer_retry_count = 0,
    last_attempt_at = NOW(3),
    last_error = NULL
WHERE event_id = #{eventId}
  AND process_status = 3;
```

这里不能清空 `redis_applied_at`，也不能改回 0；Redis 是否曾经应用已经由覆盖式重建统一处理。

管理接口：

```text
GET  /admin/video-status/consumed-events/repair-required
POST /admin/video-status/consumed-events/{eventId}/resume-after-repair
POST /admin/video-status/consumed-events/{eventId}/ignore
```

状态 3 可能来自状态 0，也可能来自状态 1。状态 0 还包含“Redis 已成功、但状态 1 尚未落库”的崩溃窗口，所以状态 3 不能在几天后简单改回 0 并重新投递；幂等 Key 过期或 Redis 丢失时会重复加统计。

`resumeAfterRepair` 使用受控恢复，不依赖 eventId Key 是否仍存在：

```text
独立事务锁定状态3、校验 payload
-> 插入 operation_status=STARTED 的 RESUME_AFTER_REPAIR 任务
-> 提交事务
-> 使用 VideoStatusVidMutex 锁定 vid，与普通消费和 flush 互斥
-> 重新读取 STARTED 任务和状态3事件
-> 生成“MySQL 基线 + 全部状态1事件 + 有processed fence的状态0事件 + 当前选中的状态3事件”快照
-> 以新 generation 覆盖重建该 vid 的 current/delta/hot
-> Redis 重建成功后，独立事务再次锁定状态3
-> 同事务执行 3 -> 1、清零 consumer retry count，并把任务标 COMPLETED
-> SADD dirty
-> 释放 vid 锁
```

状态必须在 Redis 重建成功后才从 3 改为 1，不能先 `3 -> 1` 再指望失败时补偿。若进程在“Redis 重建成功、状态仍为 3”时崩溃，STARTED 任务仍在；正常消费者/flush 会避让，Repair Operation Recovery Scheduler 会用同一真相源重新覆盖 Redis，然后完成状态迁移。若在 `3 -> 1` 后、`SADD dirty` 前崩溃，Dirty Recovery Scheduler 会重新发现状态 1。这里不再向 RabbitMQ 重发事件，因此不会受 processed event TTL 影响。

`confirmIgnored`：

```text
独立事务锁定状态3并插入 STARTED 的 IGNORE 任务
-> 使用 VideoStatusVidMutex 锁定 vid
-> 重新读取 STARTED 任务和状态3事件
-> 按“MySQL 基线 + 状态1事件 + 有processed fence的状态0事件”受控重建 Redis，明确排除状态3事件
-> Redis 重建成功后，同一 MySQL 事务保持状态3、更新 last_error
-> 把 IGNORE 任务标 COMPLETED
-> 释放 vid 锁
```

Redis 重建失败时不能确认忽略。这样可以保证“保持状态 3”不等于“Redis 里仍偷偷保留该事件效果”。

`VideoStatusRepairOperationRecoveryScheduler` 周期扫描 `operation_status=STARTED`，按 action 重新执行上述幂等覆盖式重建。单次失败只更新 `last_error`，仍保持 STARTED；连续失败必须告警，不能自动假装 COMPLETED。

扫描时先校验事件状态：RESUME/IGNORE 只有事件仍为状态 3 才继续；事件不存在、已 FLUSHED，或 action 与当前状态不兼容时标记任务 FAILED 并告警。不能留下一个永远无法执行、却让消费者和 flush 永久避让的 STARTED 任务。

operator 从当前管理员登录上下文获取，不能信任请求体。

### 16.7 受控 Redis 重建

新增受控 Redis 重建入口：

```text
src/main/java/com/feibijiubi/backend/service/video/VideoStatusRedisRecoveryService.java
src/main/java/com/feibijiubi/backend/service/impl/video/VideoStatusRedisRecoveryServiceImpl.java
```

单个 vid 出现 current/delta 只缺一个时，不在普通消费者中覆盖。受控恢复顺序：

```text
使用 VideoStatusVidMutex 锁定该 vid
-> 在一致性快照中读取 MySQL 基线、状态1事件和状态0候选事件
-> 状态0仅在 processed Key 仍存在时计入
-> 删除该 vid 的 current/delta
-> 使用新的 generation 调用初始化 Lua
-> 恢复 dirty 和热门分数
-> 释放 vid 锁
```

Redis 整体丢失时：

```text
1. 暂停消费者
2. 暂停 flush 和 cleanup Scheduler
3. 查询 video_status 中全部 vid，以及状态1事件涉及的 vid
4. 对每个 vid 执行 MySQL 基线 + 状态1增量重建
5. 状态0不计入重建
6. 重建 dirty Set
7. 重建热门 ZSet
8. 启动 cleanup；旧 generation 批次会被标记为 SKIPPED
9. 启动 flush
10. 最后恢复消费者
```

“Redis 整体丢失”意味着 processed event Key 也已经丢失，因此状态 0 不计入全量重建，等消息重投后再应用；“仅 current/delta 丢失但 processed Key 仍在”则按上一段把有 fence 的状态 0 计入。两种故障不能混成一条规则。

所有单 vid 覆盖式重建必须复用阶段 4 的 Snapshot Service 和同一套“状态 1 + 有 processed fence 的状态 0”分类代码。RESUME 只是在这个基础上额外加入选中的状态 3；IGNORE 不加入状态 3。不要在管理 Service 中复制一份简化聚合逻辑。

### 16.8 恢复与管理测试

新增：

```text
VideoStatusReceivedRecoveryIntegrationTests
VideoStatusRepairAdminIntegrationTests
VideoStatusRedisRecoveryIntegrationTests
VideoStatusRepairOperationRecoveryIntegrationTests
```

至少验证：

- Retry Confirm 失败保持状态 0；
- 超过上限且 DLQ Confirm 失败保持原状态；
- 超过自动重投年龄后不再发布 Retry，而是进入状态 3；
- 状态 1 可进入 3；
- 状态 2/3 重复转修复不重复 DLQ；
- 恢复状态 3 时通过受控重建只计入一次，不依赖 Redis eventId Key；
- 受控重建失败时事件始终保持状态 3，STARTED 任务可继续恢复；
- IGNORE 保持 3、排除 Redis 效果并写审计；
- 管理接口需要管理员权限。
- Redis 全丢失后重建值等于 MySQL 基线加状态 1 增量；
- 没有 processed fence 的状态 0 不会提前计入；partial loss 时有 fence 的状态 0 会被恢复；
- 人工重建持有 vid 互斥锁时，同 vid 的消费者和 flush 不会穿过快照窗口；
- Redis 重建成功、状态 3 尚未改为 1 时模拟崩溃，STARTED 任务可恢复且仍只计入一次；
- 应用重启后存在 STARTED 任务时，普通消费者和 flush 会避让该 vid。
- 同一 event 并发 RESUME/IGNORE 时只有一个 STARTED，冲突请求返回 409；
- 维护避让消息经过 Retry Exchange 后 attempt 不增加，也不会误入 DLQ；
- 不兼容的遗留 STARTED 会进入 FAILED，不会永久阻塞该 vid。

验证：

```powershell
.\mvnw.cmd '-Dtest=VideoStatusReceivedRecoveryIntegrationTests,VideoStatusRepairAdminIntegrationTests,VideoStatusRedisRecoveryIntegrationTests,VideoStatusRepairOperationRecoveryIntegrationTests' test
```

---

## 17. 阶段 12：Outbox、配置、安全和监控收尾

### 17.1 修正 Outbox Relay

修正：

```text
${app.video-Status.outbox-fixed-delay-ms:1000}
```

为：

```text
${app.video-status.outbox-fixed-delay-ms:1000}
```

第一版把 `outboxBatchSize` 设置为 20，避免逐条等待 5 秒 Confirm 时超过 60 秒 lease。

`markSent/markPending/markFailed` 影响行数为 0 时记录 lease 失效指标。

当前 `asyncEnabled` 同时决定“请求是否写 Outbox”和“Relay 是否运行”，这会导致切换时无法停止新 Outbox、同时继续排空旧 Outbox。增加独立开关：

```java
private boolean outboxRelayEnabled = false;
```

职责固定为：

```text
asyncEnabled       -> 请求线程使用 Outbox 异步链路，还是保留旧同步更新
outboxRelayEnabled -> Outbox Relay 是否扫描和发布
schedulingEnabled  -> flush/cleanup/recovery Scheduler 是否运行
Listener auto-startup -> RabbitMQ 消费者是否启动
```

把 Relay 中的：

```java
if (!properties.isAsyncEnabled()) {
    return;
}
```

改为：

```java
if (!properties.isOutboxRelayEnabled()) {
    return;
}
```

这样切换排空阶段可以使用 `async-enabled=false`、`outbox-relay-enabled=true`。若旧同步路径也不能继续接收统计写入，则先进入短暂维护窗口。

### 17.2 完整配置总表

`application.yml.example` 必须补齐 Redis、RabbitMQ 和全部 `app.video-status` 配置。推荐默认值见附录 C。

在 `compose.yaml` 的 Redis `command` 中显式增加：

```yaml
- --maxmemory-policy
- noeviction
```

启动后验证：

```powershell
docker compose exec -T redis redis-cli CONFIG GET maxmemory-policy
```

必须返回 `noeviction`。processed event Key 和无 TTL 的 `flush-cleaned` Key 都属于正确性 fence，不能被 Redis 选择性淘汰；否则可能重复应用事件或重复扣减 cleanup。

正式 `application.yml` 中的密码、JWT secret 和云凭证改为环境变量。已经提交过的云凭证需要轮换，不要只从文件删除。

### 17.3 指标

如果引入 Micrometer/Actuator，至少记录：

```text
video_status_outbox_pending_total
video_status_outbox_oldest_pending_seconds
video_status_consumer_success_total
video_status_consumer_retry_total
video_status_consumer_dlq_total
video_status_consumer_duplicate_total
video_status_received_stale_total
video_status_repair_required_total
video_status_repair_operation_started_total
video_status_repair_operation_oldest_seconds
video_status_pending_flush_total
video_status_oldest_pending_flush_seconds
video_status_flush_batch_size
video_status_flush_duration_ms
video_status_flush_failure_total
video_status_cleanup_pending_total
video_status_cleanup_marker_delete_failure_total
video_status_cache_miss_total
video_status_rebuild_total
```

没有引入 Actuator 时，第一版至少使用结构化日志和 SQL 管理查询，不要在代码中假装已经有监控系统。

### 17.4 告警门槛

- 最老 Outbox PENDING 超过阈值；
- 状态 0 超过恢复窗口；
- 状态 1 持续增长；
- cleanup PENDING 持续增长；
- 状态 3 出现；
- STARTED 修复任务超过一次正常重建耗时；
- DLQ 出现未解释消息；
- 批量刷库连续失败；
- Redis delta 与 MySQL 状态 1 聚合不一致。

---

## 18. 阶段 13：V1 → V2 切换和回滚

### 18.1 切换前禁止事项

- 没有完成 `outbox-relay-enabled` 拆分前，不要先设置 `async-enabled=false` 再试图排空 Outbox；旧 Relay 会一起停止。
- 不要让 V1 和 V2 消费者竞争同一个未版本化队列。
- 不要在状态 1 有积压时关闭 flush Scheduler。
- 不要直接删除 V1 Redis Key 作为第一步。

### 18.2 推荐切换顺序

```text
1. 设置 async-enabled=false、outbox-relay-enabled=true；必要时进入维护窗口
2. 排空 V1 Outbox
3. 排空 V1 Main/Retry 队列
4. 设置 outbox-relay-enabled=false，并停止 V1 消费者
5. 备份数据库
6. 执行 V2 数据库迁移或切换到已重建的 V2 数据库
7. 部署 V2，Listener 保持关闭
8. 验证 Mapper、Lua、Scheduler Bean
9. 启动 V2 cleanup/dirty/flush/recovery Scheduler
10. 设置 async-enabled=true、outbox-relay-enabled=true，启动 V2 单消费者
11. 验证新 Outbox、Redis、状态 1、状态 2、DLQ
12. 退出维护窗口，观察稳定后逐步增加并发
```

生产环境推荐创建 V2 RabbitMQ exchange/queue/routing key，避免协议竞争；本地全新环境可以复用现有队列。

### 18.3 切换前查询

```sql
SELECT status, COUNT(*), MIN(created_at)
FROM video_status_outbox
GROUP BY status;

SELECT process_status, COUNT(*), MIN(last_attempt_at)
FROM video_status_consumed_event
GROUP BY process_status;

SELECT cleanup_status, COUNT(*), MIN(created_at)
FROM video_status_flush_batch
GROUP BY cleanup_status;
```

```powershell
docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```

### 18.4 增加消费者并发

初始：

```java
factory.setPrefetchCount(20);
factory.setConcurrentConsumers(1);
factory.setMaxConcurrentConsumers(1);
```

稳定后先调整为 `2/4`。不要未经压测直接设置 `4/8`。

这里增加的是同一应用实例内的消费者线程，不是应用实例数。当前 `VideoStatusVidMutex` 是 JVM 内锁，直接部署第二个实例会破坏人工重建与 flush 的互斥；横向扩容前必须先实现分布式 per-vid 锁并完成故障续租测试。

### 18.5 回滚原则

代码回滚不能让 V1 消费者读取 V2 消息。回滚前：

1. 停止 V2 消费者；
2. 保持 flush/cleanup 运行，处理完已进入状态 1 的事件；
3. 隔离剩余 V2 队列；
4. 确认状态 1 和 cleanup PENDING 已清空；
5. 再恢复 V1 生产与消费链路。

---

## 19. 最终验证清单

### 19.1 构建

- [ ] `mvnw.cmd` 可用。
- [ ] `mvnw.cmd -DskipTests compile` 通过。
- [ ] `mvnw.cmd test` 通过。
- [ ] 没有依赖全局 Maven 才能构建。

### 19.2 数据契约

- [ ] 事件使用 schemaVersion 2。
- [ ] sequence 表、字段、Mapper 和枚举分支已经删除。
- [ ] consumed event 保存 payload。
- [ ] Java Long 与 MySQL BIGINT 对齐。

### 19.3 消费

- [ ] ACK 前已登记状态 0。
- [ ] Redis 成功后标记状态 1。
- [ ] 同 eventId 不重复更新 Redis。
- [ ] 同 eventId 不同内容进入不可重试路径。
- [ ] 状态 3 不进入普通 Retry 循环。

### 19.4 批量刷库

- [ ] 消费者不再逐条更新 `video_status`。
- [ ] 同 vid 多事件可以聚合。
- [ ] UPDATE、markFlushed、batch insert 同事务。
- [ ] cleanup 使用 batchId 幂等和 generation 校验。
- [ ] dirty 和 cleanup 都有 MySQL 恢复来源。

### 19.5 查询与恢复

- [ ] 查询线程不重建 Redis。
- [ ] 初始化值等于 MySQL 基线加状态 1 增量。
- [ ] 状态 0 不提前计入重建。
- [ ] Redis 整体丢失可以受控恢复。
- [ ] 状态 0 和状态 3 有处置闭环。

---

# 附录 A：状态机和故障窗口

## A.1 消费状态机

```text
0 RECEIVED
  -> Redis APPLIED/DUPLICATE -> 1
  -> 超过恢复上限且 DLQ Confirm 成功 -> 3

1 REDIS_APPLIED_PENDING_FLUSH
  -> MySQL 批量刷库成功 -> 2
  -> 不可自动修复的数据错误 -> 3

2 FLUSHED
  -> 终态，重复消息幂等成功

3 REPAIR_REQUIRED
  -> 修复业务数据，3 -> 1，受控重建 Redis 后恢复 flush
  -> 人工确认忽略，受控重建排除该事件，保持 3
```

## A.2 三个消费者崩溃窗口

```text
登记状态0后、Redis前崩溃
-> 消息未ACK
-> 重投发现状态0
-> 继续执行Redis

Redis成功后、标记状态1前崩溃
-> 消息未ACK
-> 重投发现状态0
-> Redis返回DUPLICATE
-> 标记状态1

状态1后、ACK前崩溃
-> 重投发现状态1
-> 不执行Redis
-> ACK
```

## A.3 批量刷库崩溃窗口

```text
MySQL事务提交前崩溃
-> UPDATE、FLUSHED、batch全部回滚

MySQL事务提交后、Redis cleanup前崩溃
-> flush_batch保持PENDING
-> cleanup Scheduler恢复

Redis cleanup成功、标记CLEANED前崩溃
-> 无TTL的batchId cleaned Key吸收重复清理
-> 重试看到DUPLICATE_CLEANUP后提交MySQL CLEANED

MySQL标记CLEANED后、删除cleaned Key前崩溃
-> MySQL已经是最终依据，不再执行Lua
-> 遗留Key只占空间，不会重复扣减
```

---

# 附录 B：Lua 脚本契约

## B.1 聚合脚本完整内容

`video-status-aggregate-v2.lua`：

```lua
-- KEYS[1] current stats hash
-- KEYS[2] pending delta hash
-- KEYS[3] dirty set
-- KEYS[4] processed event key
-- KEYS[5] hot videos zset
-- ARGV[1] current field
-- ARGV[2] delta field
-- ARGV[3] delta
-- ARGV[4] vid
-- ARGV[5] hot score delta
-- ARGV[6] processed ttl seconds

local function redisType(key)
    local result = redis.call('TYPE', key)
    if type(result) == 'table' then
        return result['ok']
    end
    return result
end

if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('EXISTS', KEYS[2]) == 0 then
    return 'NEEDS_REBUILD'
end

local currentType = redisType(KEYS[1])
local deltaType = redisType(KEYS[2])
local dirtyType = redisType(KEYS[3])
local hotType = redisType(KEYS[5])

if currentType ~= 'hash' or deltaType ~= 'hash' then
    return 'INVALID_REDIS_TYPE'
end
if dirtyType ~= 'none' and dirtyType ~= 'set' then
    return 'INVALID_REDIS_TYPE'
end
if hotType ~= 'none' and hotType ~= 'zset' then
    return 'INVALID_REDIS_TYPE'
end

local allowedCurrent = {
    playTimes = true,
    likeTimes = true,
    unlikeTimes = true,
    commentTimes = true,
    coinTimes = true,
    shareTimes = true,
    collectTimes = true,
    danmuTimes = true
}

local allowedDelta = {
    playDelta = true,
    likeDelta = true,
    unlikeDelta = true,
    commentDelta = true,
    coinDelta = true,
    shareDelta = true,
    collectDelta = true,
    danmuDelta = true
}

local currentField = ARGV[1]
local deltaField = ARGV[2]
if allowedCurrent[currentField] ~= true
        or allowedDelta[deltaField] ~= true then
    return 'INVALID_FIELD'
end

local current = tonumber(redis.call('HGET', KEYS[1], currentField))
local pending = tonumber(redis.call('HGET', KEYS[2], deltaField))
local delta = tonumber(ARGV[3])
local hotScoreDelta = tonumber(ARGV[5])
local processedTtl = tonumber(ARGV[6])

if current == nil
        or pending == nil
        or delta == nil
        or hotScoreDelta == nil
        or processedTtl == nil
        or processedTtl <= 0
        or ARGV[4] == nil
        or ARGV[4] == '' then
    return 'NEEDS_REBUILD'
end

-- 先确认承载统计的 Hash 完整，再判断事件幂等。
-- 否则 current/delta 丢失但 processed Key 仍在时会错误返回 DUPLICATE。
if redis.call('EXISTS', KEYS[4]) == 1 then
    return 'DUPLICATE'
end

if current + delta < 0 then
    return 'NEGATIVE_RESULT'
end

-- 所有校验必须位于第一次写命令之前。
redis.call('HINCRBY', KEYS[1], currentField, delta)
redis.call('HINCRBY', KEYS[2], deltaField, delta)
redis.call('SADD', KEYS[3], ARGV[4])
redis.call('ZINCRBY', KEYS[5], hotScoreDelta, ARGV[4])
redis.call('SET', KEYS[4], '1', 'EX', processedTtl)
return 'APPLIED'
```

## B.2 初始化脚本完整契约

`video-status-init-v2.lua` 输入：

```text
KEYS[1] current Hash
KEYS[2] delta Hash
KEYS[3] dirty Set
KEYS[4] hot ZSet

ARGV[1] vid
ARGV[2] generation
ARGV[3] hotScore
ARGV[4..11] 八个 current 值
ARGV[12..19] 八个 delta 值
```

完整内容：

```lua
-- KEYS[1] current stats hash
-- KEYS[2] pending delta hash
-- KEYS[3] dirty set
-- KEYS[4] hot videos zset
-- ARGV[1] vid
-- ARGV[2] generation
-- ARGV[3] hot score
-- ARGV[4..11] current values
-- ARGV[12..19] delta values

local function redisType(key)
    local result = redis.call('TYPE', key)
    if type(result) == 'table' then
        return result['ok']
    end
    return result
end

if redis.call('EXISTS', KEYS[1]) == 1 then
    return 'ALREADY_INITIALIZED'
end

local currentType = redisType(KEYS[1])
local deltaType = redisType(KEYS[2])
local dirtyType = redisType(KEYS[3])
local hotType = redisType(KEYS[4])

if currentType ~= 'none' then
    return 'INVALID_REDIS_TYPE'
end
if deltaType ~= 'none' and deltaType ~= 'hash' then
    return 'INVALID_REDIS_TYPE'
end
if dirtyType ~= 'none' and dirtyType ~= 'set' then
    return 'INVALID_REDIS_TYPE'
end
if hotType ~= 'none' and hotType ~= 'zset' then
    return 'INVALID_REDIS_TYPE'
end

if ARGV[1] == nil or ARGV[1] == ''
        or ARGV[2] == nil or ARGV[2] == '' then
    return 'INVALID_ARGUMENT'
end

local hotScore = tonumber(ARGV[3])
if hotScore == nil or hotScore ~= hotScore then
    return 'INVALID_ARGUMENT'
end

local values = {}
for i = 4, 19 do
    values[i] = tonumber(ARGV[i])
    if values[i] == nil then
        return 'INVALID_ARGUMENT'
    end
end

for i = 4, 11 do
    if values[i] < 0 then
        return 'INVALID_ARGUMENT'
    end
end

local hasDelta = false
for i = 12, 19 do
    if values[i] ~= 0 then
        hasDelta = true
        break
    end
end

-- 所有校验完成后才开始写入。
redis.call('HSET', KEYS[1],
    'vid', ARGV[1],
    'generation', ARGV[2],
    'playTimes', ARGV[4],
    'likeTimes', ARGV[5],
    'unlikeTimes', ARGV[6],
    'commentTimes', ARGV[7],
    'coinTimes', ARGV[8],
    'shareTimes', ARGV[9],
    'collectTimes', ARGV[10],
    'danmuTimes', ARGV[11])

-- current 不存在时，旧 delta 只能是异常残留；在同一 Lua 中覆盖为快照值。
redis.call('DEL', KEYS[2])
redis.call('HSET', KEYS[2],
    'playDelta', ARGV[12],
    'likeDelta', ARGV[13],
    'unlikeDelta', ARGV[14],
    'commentDelta', ARGV[15],
    'coinDelta', ARGV[16],
    'shareDelta', ARGV[17],
    'collectDelta', ARGV[18],
    'danmuDelta', ARGV[19])

if hasDelta then
    redis.call('SADD', KEYS[3], ARGV[1])
else
    redis.call('SREM', KEYS[3], ARGV[1])
end

redis.call('ZADD', KEYS[4], ARGV[3], ARGV[1])
return 'INITIALIZED'
```

## B.3 安全解锁脚本

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
```

## B.4 delta 清理

`video-status-delta-subtract-v1.lua`：

```lua
-- KEYS[1] current stats hash
-- KEYS[2] pending delta hash
-- KEYS[3] dirty set
-- KEYS[4] flush-cleaned key
-- ARGV[1] vid
-- ARGV[2] expected generation
-- ARGV[3..10] flushed deltas

local fields = {
    'playDelta',
    'likeDelta',
    'unlikeDelta',
    'commentDelta',
    'coinDelta',
    'shareDelta',
    'collectDelta',
    'danmuDelta'
}

local function redisType(key)
    local result = redis.call('TYPE', key)
    if type(result) == 'table' then
        return result['ok']
    end
    return result
end

if redis.call('EXISTS', KEYS[4]) == 1 then
    return 'DUPLICATE_CLEANUP'
end

if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('EXISTS', KEYS[2]) == 0 then
    return 'NEEDS_REBUILD'
end

if redisType(KEYS[1]) ~= 'hash'
        or redisType(KEYS[2]) ~= 'hash' then
    return 'NEEDS_REBUILD'
end

local dirtyType = redisType(KEYS[3])
if dirtyType ~= 'none' and dirtyType ~= 'set' then
    return 'NEEDS_REBUILD'
end

local generation = redis.call('HGET', KEYS[1], 'generation')
if generation == false then
    return 'NEEDS_REBUILD'
end
if generation ~= ARGV[2] then
    return 'GENERATION_CHANGED'
end

if ARGV[1] == nil or ARGV[1] == ''
        or ARGV[2] == nil or ARGV[2] == '' then
    return 'INVALID_ARGUMENT'
end

local currentValues = {}
local flushedValues = {}
for i = 1, #fields do
    currentValues[i] = tonumber(
        redis.call('HGET', KEYS[2], fields[i])
    )
    flushedValues[i] = tonumber(ARGV[i + 2])
    if currentValues[i] == nil or flushedValues[i] == nil then
        return 'INVALID_ARGUMENT'
    end
end

-- 所有校验完成后才开始写入。
local hasRemaining = false
for i = 1, #fields do
    local value = redis.call(
        'HINCRBY',
        KEYS[2],
        fields[i],
        -flushedValues[i]
    )
    if value ~= 0 then
        hasRemaining = true
    end
end

if hasRemaining then
    redis.call('SADD', KEYS[3], ARGV[1])
    redis.call('SET', KEYS[4], '1')
    return 'REMAINING'
end

-- 不删除 delta Hash。保留八个零字段，下一条事件可以直接 HINCRBY。
redis.call('SREM', KEYS[3], ARGV[1])
redis.call('SET', KEYS[4], '1')
return 'EMPTY'
```

这里故意保留空 delta Hash。若删除它，而 current Hash 仍存在，聚合脚本会返回 `NEEDS_REBUILD`，初始化脚本又会因为 current 已存在返回 `ALREADY_INITIALIZED`，形成无法恢复的循环。

`flush-cleaned` Key 故意不设置 TTL。只有在 MySQL 已经持久化 `cleanup_status=CLEANED` 后，Java Service 才能尽力删除它；删除失败是可监控的空间泄漏，但不会造成重复扣减。

---

# 附录 C：推荐配置

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
    listener:
      simple:
        acknowledge-mode: manual
        auto-startup: false
        prefetch: 20

app:
  video-status:
    async-enabled: false
    scheduling-enabled: false
    redis-event-ttl-days: 30
    outbox-batch-size: 20
    outbox-fixed-delay-ms: 1000
    outbox-lease-seconds: 60
    publish-confirm-timeout-seconds: 5
    consumer-max-retries: 5
    consumer-recovery-fixed-delay-ms: 30000
    consumer-recovery-expire-seconds: 60
    consumer-recovery-batch-size: 50
    consumer-recovery-max-attempts: 10
    consumer-recovery-auto-replay-max-age-seconds: 604800
    repair-operation-fixed-delay-ms: 5000
    repair-operation-batch-size: 20
    flush-fixed-delay-ms: 500
    flush-dirty-batch-size: 100
    flush-event-batch-size: 1000
    flush-recovery-fixed-delay-ms: 5000
    cleanup-fixed-delay-ms: 1000
    cleanup-batch-size: 100
    cleanup-max-attempts: 10
```

开发期间保持 `async-enabled=false`、`outbox-relay-enabled=false` 和 Listener `auto-startup=false`。阶段 10 首次联调发生在拆分 Relay 开关之前，使用现有 `async-enabled`；完成阶段 12 后，后续启动必须显式同时设置 `async-enabled=true`、`outbox-relay-enabled=true`。

---

# 附录 D：最终新增文件清单

```text
common/RepairRequiredMessageException.java
common/VideoStatusFlushDataException.java
common/MaintenanceInProgressException.java
enums/VideoStatusConsumeProcessStatus.java
enums/VideoStatusFlushCleanupStatus.java
entity/VideoStatusFlushBatch.java
entity/VideoStatusConsumptionRepairLog.java
service/video/RegistrationResult.java
service/video/VideoStatusRealtimeService.java
service/video/VideoStatusRebuildService.java
service/video/VideoStatusRebuildSnapshotService.java
service/video/VideoStatusRebuildSnapshot.java
service/video/VideoStatusVidMutex.java
service/video/VideoStatusConsumptionService.java
service/video/VideoStatusEventFingerprintService.java
service/video/VideoStatusBatchFlushService.java
service/video/VideoStatusDeltaCleanupService.java
service/video/VideoStatusRedisRecoveryService.java
service/video/FlushResult.java
service/impl/video/对应实现类
mapper/VideoStatusFlushBatchMapper.java
mapper/VideoStatusConsumptionRepairLogMapper.java
resources/com/.../VideoStatusFlushBatchMapper.xml
resources/com/.../VideoStatusConsumptionRepairLogMapper.xml
mq/VideoStatusBatchFlushScheduler.java
mq/VideoStatusDirtyRecoveryScheduler.java
mq/VideoStatusDeltaCleanupScheduler.java
mq/VideoStatusConsumptionRecoveryScheduler.java
mq/VideoStatusRepairOperationRecoveryScheduler.java
controller/VideoStatusConsumptionRepairController.java
dto/VideoStatusRepairActionDTO.java
vo/VideoStatusRepairEventVO.java
resources/lua/video-status-aggregate-v2.lua
resources/lua/video-status-init-v2.lua
resources/lua/video-status-delta-subtract-v1.lua
resources/lua/compare-and-delete.lua
```

---

# 附录 E：需要保留数据时的迁移分支

主流程使用重建数据库。如果必须保留数据：

1. 不运行全量 `database/feibijiubi.sql`；
2. 新增版本化 migration SQL；
3. 先备份；
4. 先新增可空列和新表；
5. 部署兼容代码；
6. 回填数据；
7. 排空 V1 Outbox 和队列；
8. 停止 V1；
9. 修改非空约束和删除旧字段；
10. 启动 V2。

至少包含：

```text
video_status INT -> BIGINT UNSIGNED
consumed_event ADD payload/retry/time fields
consumed_event 状态语义迁移
CREATE flush_batch
CREATE repair_log
DROP sequence 相关唯一键、字段和表
outbox DROP aggregate_sequence
```

旧状态值不能原样复制。完整映射先写进 migration 注释和测试：

```text
旧 0 PROCESSING/RECEIVED（应用结果不明确） -> 新 3 REPAIR_REQUIRED
旧 1 MYSQL_COMMITTED                     -> 新 2 FLUSHED
旧 2 REPAIR_REQUIRED                     -> 新 3 REPAIR_REQUIRED
其他值                                   -> 终止迁移
```

旧状态 `1 MYSQL_COMMITTED` 说明 `video_status` 已经逐条更新完成，必须令：

```sql
flushed_at = COALESCE(committed_at, consumed_at)
```

绝不能把旧状态 1 直接保留为新状态 1，否则 V2 flush 会把已经落过 MySQL 的事件再加一次；也不能把旧状态 2 原样保留，否则旧 `REPAIR_REQUIRED` 会被新代码误读成 `FLUSHED`。

在删除旧字段前，使用阶段 2 的统一事件创建规则和阶段 5 的指纹组件，为需要保留的旧记录生成合法的 schemaVersion 2 `payload`，并重新计算 `payload_hash`。

如果缺少构造 V2 事件所需的业务字段，不能把空 payload 塞进最终表，因为最终约束是 `payload/payload_hash NOT NULL`。先创建隔离表：

```sql
CREATE TABLE video_status_consumed_event_quarantine (
    id BIGINT NOT NULL AUTO_INCREMENT,
    legacy_id BIGINT NOT NULL,
    event_id VARCHAR(64) NULL,
    legacy_snapshot JSON NOT NULL,
    quarantine_reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_status_quarantine_legacy_id (legacy_id)
);
```

把无法构造 V2 payload 的旧 0/2 记录连同原始字段快照迁入 quarantine，再从活动 consumed-event 表移除。隔离记录只能人工查询和裁决，不能 RESUME；确认业务事实后应创建一个新的合法 V2 修复事件，而不是把伪造 payload 填回旧行。

迁移后必须校验：

```sql
SELECT process_status, COUNT(*)
FROM video_status_consumed_event
GROUP BY process_status;

SELECT COUNT(*)
FROM video_status_consumed_event
WHERE payload IS NULL OR payload = '' OR payload_hash IS NULL;

SELECT COUNT(*)
FROM video_status_consumed_event_quarantine;
```

同时记录迁移前后各状态行数，并抽样 eventId 重新反序列化 payload、重算 hash。必须满足“迁移后活动表行数 + quarantine 行数 = 迁移前行数”；任何未知状态或数量不守恒都应终止切换。

迁移脚本必须先在临时数据库执行并验证，不能直接在唯一开发库上试错。

---

# 附录 F：本方案边界

本方案解决：

- `video_status` 热点行逐事件更新；
- 严格 sequence 对乱序和并发的阻塞；
- RabbitMQ 重复投递；
- Redis 实时统计与 MySQL 批量持久化；
- dirty、cleanup、状态 0 的恢复；
- 状态 3 的人工处置。

本方案仍然保留：

- 每条业务事件一条 Outbox；
- 每条消费事件一条 MySQL 耐久记录；
- 每条事件一次 Redis Lua；
- eventId Redis 幂等 Key 的内存成本。

如果未来播放事件量远高于互动事件，应把 PLAY 单独拆到 Kafka/Redis Stream 等专用聚合链路。不要在本次 V2 同时引入第三版架构。
