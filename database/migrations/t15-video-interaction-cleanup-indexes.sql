-- ============================================================================
-- T15 交互表 30 天有界清理：索引（只加索引，不改列、不改数据、不改语义）
--
-- 为什么需要：
--   清理每轮的候选查询是“按索引顺序取一批主键”：
--       SELECT id FROM video_interaction_request
--       FORCE INDEX (idx_video_interaction_request_state_completed)
--       WHERE state = ? AND completed_at IS NOT NULL
--         AND completed_at <= TIMESTAMPADD(DAY, -30, NOW(3))
--       ORDER BY completed_at, id LIMIT ?;
--
--       SELECT id FROM video_interaction_window
--       FORCE INDEX (idx_video_interaction_window_last_counted)
--       WHERE last_counted_at IS NOT NULL
--         AND last_counted_at <= TIMESTAMPADD(DAY, -30, NOW(3))
--       ORDER BY last_counted_at, id LIMIT ?;
--   两张表原本只有 PRIMARY KEY + 唯一键，completed_at / last_counted_at 都没有索引，
--   上面两条查询只能全表扫 + filesort。LIMIT 在排序前无法提前终止，于是每轮都在给整张表加压，
--   而语句 timeout=3 一旦触发，清理就永远推进不了。
--   因此补充：
--       (state, completed_at, id)      -- state 单值 + 时间范围 + id 次序，天然满足 WHERE 与 ORDER BY
--       (last_counted_at, id)          -- 同上，NULL 段（从未计数）不在任何时间范围里
--
--   注意：state 必须是**单值**查询。写成 state IN (1,2) 会让两个 range 需要全局排序，
--   优化器退化成对整个过期集合 filesort，LIMIT 提前终止失效。
--
-- 为什么是硬前置：
--   清理的两条 SELECT 带 FORCE INDEX。未执行本脚本时，MySQL 直接报 1176（key doesn't exist），
--   清理在候选查询这一步失败、下一轮重试，**不会**静默退化成全表扫描，也不会删除任何行。
--   也就是说：缺索引 = 清理跑不动（可见失败），不是清理变慢（静默隐患）。
--
-- 行为：
--   * 幂等：索引已存在时执行 SELECT 1，重复执行安全；
--   * 在线 DDL：InnoDB 加二级索引默认 INPLACE + LOCK=NONE，不阻塞读写，但大表仍会产生 IO 压力，
--     建议在低峰执行并观察主从延迟；
--   * 本脚本不清空任何表、不改任何行状态，也不触碰 Redis；
--   * 必须在把 app.video-interaction.cleanup-enabled 置为 true 之前执行。
--
-- Rollback（默认注释，不随迁移执行）：DROP INDEX 两条索引。清理会立刻退回“候选查询报 1176”，
-- 属于可见失败；不得用删除行/删表的方式“回滚”。
--
-- 适用：MySQL 8.x（与 database/feibijiubi.sql 一致）。
-- ============================================================================

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_video_interaction_request_state_completed '
            'ON video_interaction_request (state, completed_at, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'video_interaction_request'
      AND index_name = 'idx_video_interaction_request_state_completed'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_video_interaction_window_last_counted '
            'ON video_interaction_window (last_counted_at, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'video_interaction_window'
      AND index_name = 'idx_video_interaction_window_last_counted'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 核对结果：两张表各应查到一行，列序必须与下面列出的一致
SELECT table_name, index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND index_name IN ('idx_video_interaction_request_state_completed',
                     'idx_video_interaction_window_last_counted')
ORDER BY table_name, index_name, seq_in_index;
