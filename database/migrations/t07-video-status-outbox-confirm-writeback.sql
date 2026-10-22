-- ============================================================================
-- T07 视频统计 Outbox：confirm 成功但 markSent 回写失败的独立预算
--
-- 为什么需要：
--   confirm 已 ACK 说明消息到达 Broker，不能按“发布失败”处理，也不能因为一次
--   DB 抖动就判 FAILED。但原实现 markSent 抛异常 → markConfirmedPending 成功时，
--   既不增加 retry_count，也不增加 lease_recovery_count；只要 markSent 持续异常
--   而 markConfirmedPending 持续成功，就会每秒永久重投同一条事件。
--   本迁移新增 confirm_writeback_count：它只统计“已 confirm、但回写 SENT 失败”
--   的次数，与发布失败（retry_count）、租约恢复（lease_recovery_count）互不挤占。
--
-- 行为（由 mapper SQL 维护，本脚本只加列）：
--   * 每次 markConfirmedPending 成功 +1；
--   * 第 10 次仍无法回写时直接置 FAILED，last_error = 'confirm writeback budget exhausted'；
--   * markSent 成功后本列保留原值，作为历史回写抖动证据（不归零）；
--   * 管理员受审计重放（保留原 eventId）时才把三份预算一起归零，并把 before/after 写入审计。
--
-- 历史数据（重要）：
--   新列 DEFAULT 0，只统计迁移之后的“confirm 后回写失败”。不 UPDATE/DELETE 任何业务行，
--   不回填 retry_count / lease_recovery_count，不修改 eventId / payload / 消费账本 / Redis。
--
-- 幂等与兼容：
--   列已存在时执行 SELECT 1，重复执行安全（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
--   因此用 information_schema 判断后 PREPARE/EXECUTE）；旧版本代码不认识本列也能继续运行。
--   建议低峰执行并观察主从延迟。
--
-- 适用：MySQL 8.x（与 database/feibijiubi.sql 一致）。
-- ============================================================================

-- 1) 幂等新增列（不依赖另一迁移的列顺序；发布前仍须执行全部 T07 迁移）
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE video_status_outbox ADD COLUMN confirm_writeback_count INT NOT NULL DEFAULT 0 COMMENT ''confirm 成功后 markSent 回写失败次数，独立预算''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_outbox'
      AND column_name = 'confirm_writeback_count'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 结果核对：应查到一行 confirm_writeback_count（int / NO / 0）
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'video_status_outbox'
  AND column_name = 'confirm_writeback_count'
ORDER BY ordinal_position;

-- 3) Rollback（默认整段注释，不随迁移执行）
--
-- 前置条件：先停掉承担投递的实例（app.video-status.outbox-relay-enabled=false），
-- 等新版本代码统一回滚完成后再执行。只 DROP 本任务新增的列；禁止 DELETE / TRUNCATE
-- video_status_outbox，禁止清 event_id 唯一键、SENT/FAILED 行或 video_status_consumed_event：
-- 那些是消息幂等/去重证据，不是本迁移的产物。
--
-- SET @ddl = (
--     SELECT IF(
--         COUNT(*) = 1,
--         'ALTER TABLE video_status_outbox DROP COLUMN confirm_writeback_count',
--         'SELECT 1'
--     )
--     FROM information_schema.columns
--     WHERE table_schema = DATABASE()
--       AND table_name = 'video_status_outbox'
--       AND column_name = 'confirm_writeback_count'
-- );
-- PREPARE stmt FROM @ddl;
-- EXECUTE stmt;
-- DEALLOCATE PREPARE stmt;
