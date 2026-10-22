-- ============================================================================
-- T09 第一阶段：status0（RECEIVED）安全窗口原 eventId 补偿
--
-- 新增列只用于“补偿领取/冷却/诊断”，不改变消费状态语义：
--   * process_status 仍由消费者/落库推进；本迁移不 UPDATE/DELETE 业务行；
--   * compensation_lease_token / compensation_lease_until：短事务领取后的租约，
--     到期可被其它实例再次领取；发送后崩溃由同一 eventId 重投，幂等由消费端吸收；
--   * compensation_attempts：补偿次数上限（默认 5），耗尽后只保留 RECEIVED 供 preview，
--     不自动转 status3，因为 processed 缺失既可能未应用，也可能 current 已应用而
--     processed 单独丢失；
--   * compensation_next_attempt_at：失败/证据不足后的冷却；
--   * last_compensation_at：最近一次领取时间，便于诊断。
--
-- 索引服务候选扫描：process_status + last_attempt_at + id。
--
-- 幂等：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，这里用 information_schema 判断后
-- PREPARE/EXECUTE；重复执行安全。旧版本代码不认识新列也能继续运行。
-- 历史数据不回填：新列使用 NULL/0 默认值，不代表历史事件已被补偿过。
-- 适用：MySQL 8.x（与 database/feibijiubi.sql 一致）。
-- ============================================================================

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE video_status_consumed_event ADD COLUMN compensation_attempts INT NOT NULL DEFAULT 0 COMMENT ''T09 status0 补偿领取次数，与 consumer_retry_count 独立''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_consumed_event'
      AND column_name = 'compensation_attempts'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE video_status_consumed_event ADD COLUMN compensation_next_attempt_at DATETIME(3) NULL COMMENT ''T09 补偿冷却截止时间''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_consumed_event'
      AND column_name = 'compensation_next_attempt_at'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE video_status_consumed_event ADD COLUMN compensation_lease_token VARCHAR(64) NULL COMMENT ''T09 补偿领取租约 token''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_consumed_event'
      AND column_name = 'compensation_lease_token'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE video_status_consumed_event ADD COLUMN compensation_lease_until DATETIME(3) NULL COMMENT ''T09 补偿租约到期时间''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_consumed_event'
      AND column_name = 'compensation_lease_until'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE video_status_consumed_event ADD COLUMN last_compensation_at DATETIME(3) NULL COMMENT ''T09 最近一次补偿领取时间''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_consumed_event'
      AND column_name = 'last_compensation_at'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE video_status_consumed_event ADD KEY idx_video_status_consumed_compensation (process_status, last_attempt_at, id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_consumed_event'
      AND index_name = 'idx_video_status_consumed_compensation'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 核对结果
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'video_status_consumed_event'
  AND column_name IN (
      'compensation_attempts',
      'compensation_next_attempt_at',
      'compensation_lease_token',
      'compensation_lease_until',
      'last_compensation_at'
  )
ORDER BY ordinal_position;

-- Rollback（默认注释，不随迁移执行）：先关闭 status0-compensation-enabled，
-- 等新代码全部下线后再 DROP 本任务新增列/索引；不得删除 consumed 行或 processed 键。
