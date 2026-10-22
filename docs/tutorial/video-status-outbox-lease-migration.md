# 视频统计 Outbox 租约恢复预算与迁移（T07）

本文记录 T07 的改动：把 Outbox 的“租约过期恢复”从发布失败重试里拆出来，
新增独立列 `lease_recovery_count`，并为“confirm 已成功但 `markSent` 回写失败”新增
`confirm_writeback_count` 预算；同时固定确认超时 / 非法消息的 SQL 语义。
实现包含 mapper/XML、entity、properties、schema/迁移、`VideoStatusOutboxRelay` 接线、
`VideoStatusEventPublisher` 类型化异常和管理员单事件查询/重放工具。

> 状态：实现提交 `eec1e01`，测试已完成，**等待父代理验收**；不宣称生产上线或真实进程崩溃演练。

## 1. 问题

T06 及以前 `recoverExpiredSending` 的 SQL 是：

```sql
UPDATE video_status_outbox
SET status = 0,
    retry_count = retry_count + 1,
    next_retry_at = NOW(3),
    sending_at = NULL,
    lease_token = NULL,
    last_error = '发送租约超时，已自动恢复'
WHERE status = 1 AND sending_at < #{expiredBefore}
```

同一列 `retry_count` 同时承担两件事：

- 发布失败 / 确认超时（`markPending`、`markFailed`）；
- 租约过期被其他实例恢复（`recoverExpiredSending`）。

后果是两类故障互相挤占预算：一次网络抖动造成的租约过期会提前消耗发布重试次数；
发布重试又会把租约恢复预算提前用光，事后无法区分“消息发不出去”和“实例卡死”。

另外，这条 UPDATE 没有行数上限，租约集中过期时会对整表做一次无界更新；
`next_retry_at = NOW(3)` 也让刚被恢复的消息在同一时刻立刻被再次领取（抖动放大）。

## 2. 协议（T07）

### 2.1 列语义

| 列 | 含义 | T07 之后 |
| --- | --- | --- |
| `retry_count` | 发布失败次数 | 只由 `markPending` / `markFailed` 各 `+1`；租约恢复与 confirm 回写失败**不再**碰它 |
| `lease_recovery_count` | 租约过期被恢复的次数 | **新增**，`INT NOT NULL DEFAULT 0`，独立预算 |
| `confirm_writeback_count` | confirm 已成功、`markSent` 回写失败的次数 | **新增**，`INT NOT NULL DEFAULT 0`，独立预算；第 10 次 FAILED |

**历史数据不做回填，也不允许归零。** 已有行的 `retry_count` 里可能混有旧
`recoverExpiredSending` 累加的次数，事后无法可靠拆分：把它归零会抹掉真实发布失败证据，
把它“拆分”到新列则是编造数据。因此迁移只加列（默认 0），新列只统计迁移之后的租约恢复；
历史行的 `retry_count` 保持原值，含义按“发布失败次数 + 无法区分的历史租约恢复”解读。

### 2.2 `recoverExpiredSending(expiredBefore)`（签名保持 T06 不变）

```sql
UPDATE video_status_outbox
SET status = CASE WHEN lease_recovery_count + 1 >= 10 THEN 3 ELSE 0 END,
    next_retry_at = CASE WHEN lease_recovery_count + 1 >= 10 THEN NULL
                         ELSE TIMESTAMPADD(SECOND, 1, NOW(3)) END,
    sending_at = NULL,
    lease_token = NULL,
    last_error = CASE WHEN lease_recovery_count + 1 >= 10
                      THEN 'lease recovery budget exhausted'
                      ELSE '发送租约超时，已自动恢复（不计入发布重试）' END,
    lease_recovery_count = lease_recovery_count + 1
WHERE status = 1
  AND sending_at < #{expiredBefore}
ORDER BY sending_at, id
LIMIT 1000
```

- **只加租约预算**：`lease_recovery_count + 1`，不写 `retry_count`；
- **固定第 10 次过期进 FAILED**：`+1 后 >= 10`（即第 10 次）直接 `status=3`，
  `last_error` 固定为 `lease recovery budget exhausted`，并清掉 `next_retry_at`；
- **未耗尽回 PENDING + `next_retry_at = NOW(3) + 1s`**：不再同一时刻立即重领，给下游一点缓冲；
- **CAS 幂等**：条件仍是 `status = 1 AND sending_at < expiredBefore`。已经恢复（0）、
  已经终态（2/3）或已重新领取（新 `sending_at`）的行不会被再次命中；
- **清空 `lease_token` / `sending_at`**：旧 owner 的所有回写语句都要求 `lease_token` 相等，
  token 被清空后旧 owner 的 CAS 必然 0 行，不会覆盖接管者的状态；
- **每轮最多 1000 行**：`ORDER BY sending_at, id LIMIT 1000`，先处理最老的租约，
  一条 SQL 不会无限 UPDATE 大表；剩下的事件下一轮继续，不会丢；
- **赋值顺序**：MySQL 单表 UPDATE 的后续表达式会读到前面已赋的新值，
  所以 `lease_recovery_count = lease_recovery_count + 1` 必须放在所有 `CASE` 之后，
  否则第 9/10 次判定会错位。这一点由测试逐次验证（9 次后仍 PENDING，第 10 次 FAILED）。

### 2.3 `markConfirmedPending` / `markConfirmWritebackExhausted`

confirm ACK 已成功、但 `markSent` 回写失败时，不把它当成发布失败，也不立即判 FAILED；
而是使用独立 `confirm_writeback_count` 预算：前 9 次回 PENDING 重投，第 10 次 FAILED。

```sql
UPDATE video_status_outbox
SET status = CASE WHEN confirm_writeback_count + 1 >= 10 THEN 3 ELSE 0 END,
    next_retry_at = CASE WHEN confirm_writeback_count + 1 >= 10 THEN NULL
                         ELSE #{nextRetryAt} END,
    sending_at = NULL, lease_token = NULL,
    last_error = CASE WHEN confirm_writeback_count + 1 >= 10
                      THEN 'confirm writeback budget exhausted' ELSE #{lastError} END,
    confirm_writeback_count = confirm_writeback_count + 1
WHERE event_id = #{eventId} AND status = 1 AND lease_token = #{leaseToken}
```

- **不增加 `retry_count` / `lease_recovery_count`**：confirm 成功后的回写失败不是发布失败，
  也不是租约恢复，不能挤占另外两份预算；
- 第 10 次也可由 `markConfirmWritebackExhausted` 显式落 FAILED，原因固定为
  `confirm writeback budget exhausted`，避免每秒永久重投；
- 与现有 `markPending` 共用相同的 `event_id + status = 1 + lease_token` CAS 形状；
- 旧 token（租约已被恢复/接管）返回 0 行，不覆盖别人的状态。
- **确认超时**（结果不确定）仍走 `handlePublishFailure` 的 `markPending/markFailed`，
  会正常消耗 `retry_count`；不要把它和 confirm 成功后的回写失败混为一谈。

### 2.4 `markInvalidFailed(eventId, leaseToken, lastError)`

消息本身非法（例如 payload 无法解析）→ 直接 FAILED，不再无限重投：

```sql
UPDATE video_status_outbox
SET status = 3, sending_at = NULL, lease_token = NULL, last_error = #{lastError}
WHERE event_id = #{eventId} AND status = 1 AND lease_token = #{leaseToken}
```

- **同样不增加 `retry_count`**：这是永久性投递拒绝，不是一次发布失败；
- CAS 条件与 `markFailed` 一致，旧 owner 不能覆盖被接管后的状态。

### 2.5 SQL 超时

本 mapper 的所有语句都加 `timeout="3"`（秒）。慢 SQL 由 MyBatis 打断，
不把调度线程或发布线程无限挂在数据库上；它与 `publish-confirm-timeout-seconds`、
`round-budget-ms` 是三层不同的预算，互不替代。

## 3. 启动期参数校验（`VideoStatusProperties`）

新增跨字段规则：

```
outbox-lease-seconds >= publish-confirm-timeout-seconds + 5
```

- confirm 只是“等 MQ 回执”的上限，拿到回执后还要做 `markSent` /
  `markConfirmedPending` / `markInvalidFailed` 的状态回写。租约紧贴 confirm 超时时，
  回写可能落在其他实例已经合法接管之后，旧 owner 的 CAS 会全部落空；
- 固定留 5 秒（`VideoStatusProperties.MIN_LEASE_CONFIRM_MARGIN_SECONDS`）给回写与时钟误差；
- 默认 `60 / 5` 自然满足；`application.yml.example` 不需要改；
- **不**要求 `round-budget-ms >= publish-confirm-timeout-seconds`。单轮预算只决定
  “还要不要拿下一条”，不负责强杀进行中的 publish；强行加这条约束会把已有的 5s 预算配置判成非法。

## 4. 迁移

文件：

- `database/migrations/t07-video-status-outbox-lease-recovery.sql`：新增 `lease_recovery_count`；
- `database/migrations/t07-video-status-outbox-confirm-writeback.sql`：新增 `confirm_writeback_count`；

`database/feibijiubi.sql` 的建表语句已同步包含两列。

### 4.1 执行

```bash
mysql -h 127.0.0.1 -P 13307 -uroot -p feibijiubi \
  < database/migrations/t07-video-status-outbox-lease-recovery.sql
```

脚本内容：

1. `information_schema` 判断列是否存在，不存在才 `ALTER TABLE ... ADD COLUMN
   lease_recovery_count INT NOT NULL DEFAULT 0 COMMENT '...' AFTER retry_count`
   （MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，因此用 `SET @ddl → PREPARE → EXECUTE →
   DEALLOCATE`；整段必须在同一个 JDBC/客户端会话执行）；
2. 回读 `information_schema` 核对列定义（`int / NO / 0`）；
3. rollback 段默认整段注释，不随迁移执行。

### 4.2 幂等

- 每份迁移都先用 `information_schema` 判列，列已存在时重复执行安全；测试连续执行多遍，
  列数始终 1、outbox 行数不变；
- 只加列、只读元数据：不 UPDATE/DELETE 任何业务行，不清 `event_id` 幂等记录，
  不动 `video_status_consumed_event`，不碰 Redis；
- 两份迁移相互独立：只执行租约迁移时 `confirm_writeback_count` 仍不存在，需按 T07 顺序部署
  完整代码和两份迁移，不能混跑旧 mapper。

### 4.3 Rollback（有条件）

只在**先停旧投递**（`app.video-status.outbox-relay-enabled=false`）、
新旧实例统一回滚完成之后执行；两份迁移分别回滚：

```sql
ALTER TABLE video_status_outbox DROP COLUMN confirm_writeback_count;
ALTER TABLE video_status_outbox DROP COLUMN lease_recovery_count;
```

- 只 DROP 本任务新增的列；
- **禁止** `DELETE` / `TRUNCATE video_status_outbox`，禁止清 `event_id` 唯一键、
  SENT/FAILED 行或 `video_status_consumed_event`：那些是消息幂等/去重证据，
  不是本迁移的产物，删掉等于允许重复消费；
- 回滚后旧代码仍能运行（旧 SQL 不引用新列），但“租约恢复独立预算”随代码一起回退。

## 5. 测试

新增：`src/test/java/com/feibijiubi/backend/service/video/VideoStatusOutboxLeaseTests.java`
（`@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")`，
复用 `VideoStatusCounterIsolatedTests.IsolationGuard` 与隔离 `TestApplication` 形态：
只有 DataSource + JdbcTemplate + MyBatis，排除 Redis/MQ/Mongo/Redisson，不跑调度器）。

运行：

```bash
./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusOutboxLeaseTests test
```

覆盖：

| 用例 | 验证 |
| --- | --- |
| 迁移幂等 | 连续 3 遍，`lease_recovery_count int NO 0`，行数不变，可执行语句不含 DROP/DELETE/TRUNCATE |
| 租约恢复独立预算 | 两次恢复后 `retry_count` 仍为 3，`lease_recovery_count` 1→2，token 清空，DB `NOW(3)+1s` |
| 实体映射 | `selectPendingForUpdate` 能把 `lease_recovery_count` 读回实体 |
| 9 次可再成功 | 9 次过期后仍 PENDING，新 owner 领取并 `markSent` 成 SENT，两个计数都不被改写 |
| 第 10 次终结 | 第 10 次过期 → FAILED，`last_error = lease recovery budget exhausted`，再恢复 0 行 |
| CAS 接管 | 恢复后旧 token 的 markSent/markPending/markFailed/markConfirmedPending/markInvalidFailed 全部 0 行；新 token 才能接管 |
| 确认超时不加 retry | `markConfirmedPending` → PENDING、`retry_count` 不变、token 失效后重复调用 0 行 |
| 非法消息不加 retry | `markInvalidFailed` → FAILED、`retry_count` 不变 |
| 启动期校验 | 默认 60/5 通过；14 < 10+5 被拒、15 通过；`round-budget-ms=1` 仍合法 |

T07 聚焦验收（父代理 2026-10-22 独立复跑）：

```bash
./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests' test
```

结果：**61 tests，0 failures / 0 errors / 0 skipped**，日志 `/tmp/improvement-t07-verify.log`。
该批次同时覆盖租约迁移、confirm 回写迁移、mapper/XML、relay 分类和重放边界；
仍属隔离测试环境，不是生产或真实进程崩溃演练。

回归（不连库的既有单测）：

```
VideoStatusRuntimePropertiesContextTests          6/6
VideoStatusSchedulingConfigTests                  5/5
config.VideoStatusSchedulingIsolationTests        3/3
VideoStatusOutboxRelayTests                       6/6
VideoStatusRuntimeRoleContractTests              13/13
service.video.VideoStatusSchedulingIsolationTests 11/11
合计 44/44 通过
```

## 6. 边界与未声明的东西

- 本任务**不**声称 exactly-once。Outbox 仍是 at-least-once：发布成功但 `markSent` 失败、
  确认超时回 PENDING、租约恢复后重投都可能重复投递，去重依赖消费端 `eventId` 幂等。
- 唯一 id / 租约 token 仍是幂等的唯一依据：每次领取生成新 token，所有回写都是
  `event_id + status = 1 + lease_token` 的 CAS；token 被清空或替换后旧 owner 写不进任何状态。
- `recoverExpiredSending` 是全局 SQL（按 `sending_at` 过期时间选行），没有按 vid 过滤；
  隔离库中若已存在其他过期 SENDING 行，测试会显式失败提示，而不是悄悄放过。
- T07 已修改投递调度器与发布异常类型，但未修改消费端、落库/重建调度和监控；
  `outbox-relay-enabled` 等既有开关语义不变。
- 未做真实 RabbitMQ confirm 超时/断连、真实进程崩溃/反复重启或两 JVM 接管演练；这些仍交 T16。
