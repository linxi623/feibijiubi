-- ============================================================================
-- T10 失败事件管理列表：有界 keyset 分页所需索引（只加索引，不改列、不改数据）
--
-- 为什么需要：
--   OUTBOX 失败列表：
--       SELECT ... FROM video_status_outbox
--       WHERE status = 3 AND id > ? ORDER BY id LIMIT ?
--   CONSUMED 失败/诊断列表把 status0 与 status3 分成两个单状态分支：
--       (SELECT ... WHERE process_status = 0 AND id > ? ORDER BY id LIMIT ?)
--       UNION ALL
--       (SELECT ... WHERE process_status = 3 AND id > ? ORDER BY id LIMIT ?)
--   外层再 ORDER BY id LIMIT ?。
--   现有 outbox 索引 (status, next_retry_at, sending_at, id)、
--   (status, created_at, id) 以及 consumed 的
--   (process_status, vid, id) 等都不能直接按 (status, id) 做有界范围扫描。
--   因此补充：
--       idx_video_status_outbox_status_id   (status, id)
--       idx_video_status_consumed_status_id (process_status, id)
--
-- 行为：
--   * 幂等：索引已存在时执行 SELECT 1，重复执行安全；
--   * 只读检查：应用在执行列表查询前会按 information_schema.statistics
--     校验索引存在且 key 顺序精确为 (status, id) / (process_status, id)；
--     缺失或顺序不符时返回 503，不执行扫描；
--   * 不修改任何事件状态、计数、审计或 Redis；
--   * 必须在使用 T10 failure 列表接口前执行本迁移。
--
-- 适用：MySQL 8.x（与 database/feibijiubi.sql 一致）。
-- ============================================================================

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_video_status_outbox_status_id ON video_status_outbox (status, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_outbox'
      AND index_name = 'idx_video_status_outbox_status_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_video_status_consumed_status_id ON video_status_consumed_event (process_status, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_consumed_event'
      AND index_name = 'idx_video_status_consumed_status_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 校验结果：应查到两组 (status, id) / (process_status, id)，且 seq_in_index 为 1,2。
SELECT table_name, index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND (
        (table_name = 'video_status_outbox'
            AND index_name = 'idx_video_status_outbox_status_id')
     OR (table_name = 'video_status_consumed_event'
            AND index_name = 'idx_video_status_consumed_status_id')
      )
ORDER BY table_name, index_name, seq_in_index;
