-- ============================================================================
-- T05 视频统计可观测性：观测所需索引（只加索引，不改列、不改数据、不改语义）
--
-- 为什么需要：
--   积压采样要回答“每个状态有多少条、最老一条等了多久”，并且必须是
--   有界查询（封顶计数 + LIMIT 1 取最老），不能对无限增长的大表做全表扫描。
--   现有索引：
--     video_status_outbox         (status, next_retry_at, sending_at, id)
--     video_status_consumed_event (process_status, vid, id)
--                                 (process_status, last_attempt_at, id)
--   都不能同时满足 status 等值 + created_at/consumed_at 顺序，因此补齐：
--     idx_video_status_outbox_created   (status, created_at, id)
--     idx_video_status_consumed_created (process_status, consumed_at, id)
--
-- 行为：
--   * 幂等：索引已存在时执行 SELECT 1，重复执行安全；
--   * 在线 DDL：InnoDB 加二级索引默认 INPLACE + LOCK=NONE，不阻塞读写，
--     但大表仍会产生 IO 压力，建议在低峰执行并观察主从延迟；
--   * 本脚本不清空任何表、不改任何事件状态，也不触碰 Redis；
--   * 未执行本脚本时，Sampler 会返回 DEGRADED + 明确诊断（缺哪个索引），
--     并跳过计数查询，而不是退化成全表扫描。
--
-- 适用：MySQL 8.x（与 database/feibijiubi.sql 一致）。
-- ============================================================================

-- 1) Outbox：按状态取真实最老（created_at 顺序，id 只做并列时的稳定次序）
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_video_status_outbox_created ON video_status_outbox (status, created_at, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_outbox'
      AND index_name = 'idx_video_status_outbox_created'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 消费事件：按 process_status 取真实最老（consumed_at 顺序）
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_video_status_consumed_created ON video_status_consumed_event (process_status, consumed_at, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_consumed_event'
      AND index_name = 'idx_video_status_consumed_created'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 结果核对：两行都应查到
SELECT table_name, index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND (
        (table_name = 'video_status_outbox'
            AND index_name = 'idx_video_status_outbox_created')
     OR (table_name = 'video_status_consumed_event'
            AND index_name = 'idx_video_status_consumed_created')
      )
ORDER BY table_name, seq_in_index;
