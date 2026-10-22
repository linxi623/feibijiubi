-- ============================================================================
-- T07 视频统计 Outbox：租约恢复独立预算 lease_recovery_count
--
-- 为什么需要：
--   T06 及以前 recoverExpiredSending 用 retry_count + 1 记录“租约超时恢复”，
--   与“发布失败/确认超时”挤在同一个计数里：
--     * 一条网络抖动导致租约过期的消息，会提前吃掉发布重试次数；
--     * 反过来，发布重试也会把租约恢复预算提前用光，故障原因无法区分。
--   T07 把两件事拆开：
--     retry_count           只统计发布失败次数（markPending / markFailed 各 +1）；
--     lease_recovery_count  只统计租约过期被恢复的次数，独立预算，第 10 次过期直接 FAILED。
--
-- 历史数据（重要）：
--   已有行的 retry_count 里可能混有旧 recoverExpiredSending 累加的次数，事后无法可靠拆分，
--   所以本迁移**不做任何回填**，也不允许把 retry_count 归零（那会抹掉真实发布失败证据）。
--   新列从 DEFAULT 0 开始，只对迁移之后的租约恢复计数。
--
-- 行为：
--   * 幂等：列已存在时执行 SELECT 1，重复执行安全（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
--     因此用 information_schema 判断后 PREPARE/EXECUTE）；
--   * 只加一列、只读元数据：不 UPDATE/DELETE 任何业务行，不清 event_id 幂等记录，
--     不动 video_status_consumed_event，不碰 Redis；
--   * ALTER TABLE ... ADD COLUMN 在 MySQL 8.0+ 对“末尾之外的 NULL/带默认值列”走 INSTANT/INPLACE，
--     大表上仍建议低峰执行并观察主从延迟。
--
-- 适用：MySQL 8.x（与 database/feibijiubi.sql 一致）。
-- ============================================================================

-- 1) 幂等新增列（放在 retry_count 之后，语义相邻）
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE video_status_outbox ADD COLUMN lease_recovery_count INT NOT NULL DEFAULT 0 COMMENT ''租约过期恢复次数，独立于 retry_count'' AFTER retry_count',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_outbox'
      AND column_name = 'lease_recovery_count'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 结果核对：应查到一行 lease_recovery_count（int / NO / 0）
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'video_status_outbox'
  AND column_name = 'lease_recovery_count'
ORDER BY ordinal_position;

-- 3) Rollback（默认整段注释，不随迁移执行）
--
-- 前置条件：先停掉承担投递的实例（app.video-status.outbox-relay-enabled=false），
-- 等旧版本代码统一回滚完成后再执行——旧代码不认识本列，但没有列也不会报错；
-- 之所以要求“先停旧再统一回滚”，是为了避免新代码还在写 lease_recovery_count 时列被删掉。
--
-- 只 DROP 本任务新增的列。禁止 DELETE / TRUNCATE video_status_outbox，禁止清
-- event_id 唯一键、SENT/FAILED 行、video_status_consumed_event：那些是消息幂等/去重证据，
-- 不是本迁移的产物，删掉就等于允许重复消费。
--
-- SET @ddl = (
--     SELECT IF(
--         COUNT(*) = 1,
--         'ALTER TABLE video_status_outbox DROP COLUMN lease_recovery_count',
--         'SELECT 1'
--     )
--     FROM information_schema.columns
--     WHERE table_schema = DATABASE()
--       AND table_name = 'video_status_outbox'
--       AND column_name = 'lease_recovery_count'
-- );
-- PREPARE stmt FROM @ddl;
-- EXECUTE stmt;
-- DEALLOCATE PREPARE stmt;
