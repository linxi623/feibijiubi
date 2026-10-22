-- ============================================================================
-- T10 单视频只读事件对账：安全窗口索引（只加索引，不改列、不改数据、不改语义）
--
-- 为什么需要：
--   T10 reconciliation 的事件投影是：
--       SELECT id, process_status, event_type, delta
--       FROM video_status_consumed_event
--       WHERE vid = ?
--       ORDER BY id DESC
--       LIMIT ?            -- 最多 limit + 1，limit 上限 1000
--   现有索引：
--       idx_video_status_consumed_pending (process_status, vid, id)
--   以 process_status 开头，不能直接服务“仅按 vid 过滤 + id 倒序”的窗口；
--   外键自动索引只保证 vid 过滤，仍可能对该 vid 的全部事件做 filesort。
--   因此补充：
--       idx_video_status_consumed_vid_id (vid, id)
--
--   账本状态计数继续使用现有 (process_status, vid, id)：
--       WHERE vid = ? AND process_status = ? LIMIT cap
--   业务关系真值继续使用现有 user_video idx_user_video_vid：
--       WHERE vid = ? LIMIT cap（派生表内先截断，再聚合）
--
-- 行为：
--   * 幂等：索引已存在时执行 SELECT 1，重复执行安全；
--   * 在线 DDL：InnoDB 加二级索引默认 INPLACE + LOCK=NONE，不阻塞读写，
--     但大表仍会产生 IO 压力，建议在低峰执行并观察主从延迟；
--   * 本脚本不清空任何表、不改任何事件状态，也不触碰 Redis；
--   * 必须在启用/使用 T10 reconciliation 接口前执行本迁移；
--     未执行时接口仍只读且带 timeout/LIMIT，但事件窗口可能退化为 filesort，
--     不满足“有界高效”的运维前提。
--
-- 适用：MySQL 8.x（与 database/feibijiubi.sql 一致）。
-- ============================================================================

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE INDEX idx_video_status_consumed_vid_id ON video_status_consumed_event (vid, id)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'video_status_consumed_event'
      AND index_name = 'idx_video_status_consumed_vid_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 核对结果：应查到 (vid, id) 两列
SELECT table_name, index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'video_status_consumed_event'
  AND index_name = 'idx_video_status_consumed_vid_id'
ORDER BY seq_in_index;
