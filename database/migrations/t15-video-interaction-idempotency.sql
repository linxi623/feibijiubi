-- ============================================================================
-- T15 第一阶段（DB 核心）：播放/分享交互的“请求幂等 + 滚动窗口限计”
--
-- 本迁移只新增两张表，不改任何既有表结构、不 UPDATE/DELETE 任何既有行：
--   * video_interaction_request：一个业务请求（request_key）一行。
--       state 是请求自己的结果快照：0=PENDING（占位，未提交）、1=COUNTED、2=SUPPRESSED。
--       PENDING 只存在于未提交事务里——终态与计数在同一事务提交，所以已提交的行
--       只可能是 1 或 2；读到 PENDING 说明有人读到了未提交状态（不应发生）。
--       event_id 只在异步计数路径上有值（createEvent 返回的 eventId）；同步路径为 NULL，
--       因为同步直接更新 video_status，不产生 MQ 事件。
--   * video_interaction_window：actor_type + actor_hash + vid + action 唯一一行。
--       last_counted_at 记录“最近一次真正计数”的 DB 时间，NULL = 从未计数。
--       NULL 绝不能替换成 NOW(3) 或 0：否则第一条请求会被误判为“刚计过”而抑制。
--
-- 产品语义（已批准，本阶段只落 DB 核心）：同一 actor 对同一 vid 的同一动作，
-- 30 分钟滚动窗口内最多计一次；同一 request_key 永远只计一次（重放历史结果）。
--
-- ⚠️ 历史说明（后续 T15 清理切片，候选未验收）：上面“永远只计一次”已被取代——
--    同一 request_key 在 30 天保留期内（自 completed_at 起算）只计一次；记录被 30 天有界清理删掉后，
--    同键按新请求处理，仍受 30 分钟滚动窗口约束。清理默认关闭，且不是 30 天到点的 TTL。
--    本文件是**已执行的历史迁移记录**，因此这里只加注释说明、不修改下面任何可执行 DDL（含表 COMMENT）：
--    新库的表注释以 database/feibijiubi.sql 为准。详见 docs/tutorial/video-interaction-idempotency.md §12。
-- 抑制不推进 last_counted_at，所以窗口是“按实际计数时间”滚动的，不是固定周期。
--
-- request_key 必须大小写敏感：唯一索引依赖列的 utf8mb4_bin 排序规则。
-- 表默认排序规则仍是 utf8mb4_unicode_ci（与仓库其它表一致），只有该列显式 bin。
--
-- 幂等：CREATE TABLE IF NOT EXISTS；重复执行安全。旧版本代码不认识新表也能继续运行。
-- 历史数据不回填：两张表初始为空，不代表任何历史交互已被去重过。
-- 适用：MySQL 8.x（与 database/feibijiubi.sql 一致）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS video_interaction_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- 大小写敏感：'abc' 与 'ABC' 是两个不同请求
    request_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '业务请求幂等键，大小写敏感',
    actor_type VARCHAR(16) NOT NULL COMMENT 'USER / ANONYMOUS',
    actor_hash CHAR(64) NOT NULL COMMENT '规范化小写 64 位十六进制 actor 标识哈希',
    vid INT NOT NULL,
    action VARCHAR(16) NOT NULL COMMENT 'PLAY / SHARE',
    state TINYINT NOT NULL DEFAULT 0
        COMMENT '0=PENDING,1=COUNTED,2=SUPPRESSED',
    event_id VARCHAR(64) NULL COMMENT '异步计数路径的统计事件ID；同步路径为 NULL',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL COMMENT '写入终态的 DB 时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_interaction_request_key (request_key),
    CONSTRAINT fk_video_interaction_request_vid
        FOREIGN KEY (vid) REFERENCES video (vid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='T15 交互请求幂等表（一个 request_key 只计一次）';

CREATE TABLE IF NOT EXISTS video_interaction_window (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_type VARCHAR(16) NOT NULL COMMENT 'USER / ANONYMOUS',
    actor_hash CHAR(64) NOT NULL COMMENT '规范化小写 64 位十六进制 actor 标识哈希',
    vid INT NOT NULL,
    action VARCHAR(16) NOT NULL COMMENT 'PLAY / SHARE',
    -- NULL = 从未计数。只由“真正计数”的那次事务推进，抑制不推进。
    last_counted_at DATETIME(3) NULL COMMENT '最近一次真正计数的 DB 时间；NULL=从未计数',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_interaction_window_actor (actor_type, actor_hash, vid, action),
    CONSTRAINT fk_video_interaction_window_vid
        FOREIGN KEY (vid) REFERENCES video (vid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='T15 交互滚动窗口表（同一 actor+vid+action 30 分钟最多计一次）';

-- 核对结果
SELECT table_name, table_collation
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('video_interaction_request', 'video_interaction_window')
ORDER BY table_name;

SELECT table_name, column_name, column_type, is_nullable, column_default, collation_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('video_interaction_request', 'video_interaction_window')
ORDER BY table_name, ordinal_position;

-- Rollback（默认注释，不随迁移执行）：T15 第二阶段接线下线、确认无写入者后，
-- 再 DROP TABLE video_interaction_request / video_interaction_window；
-- 不得删除 video_status / outbox / consumed 行或 processed 键。
