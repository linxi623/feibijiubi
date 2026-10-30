-- ============================================================================
-- M4-A1：本地视频身份的最小两表（只新建两张表，不改既有表、不写任何既有数据）
--
-- 依据：docs/tutorial/external-video-source-local-identity-contract.md 第 4 节（父级已批准的 A1 DDL）。
-- 本脚本新建：
--   * local_video_series   —— 本地作品，独立于任何上游来源
--   * local_video_episode  —— 本地剧集，导入后才持有 vid
--
-- 运行前置（不满足就由 MySQL 直接报错，本脚本不做任何自动补齐）：
--   * 已存在 users 表：created_by 引用 users.id（INT）；
--   * 已存在 video 表：vid 引用 video.vid（INT）；
--   * MySQL 8.0.16+：duration_seconds 的 CHECK 只有 8.0.16 及以上才真正强制，
--     更低版本会**解析后静默忽略**，非法值可被直连 SQL 写入而不报错。
--     因此部署前提是该版本，且应用层仍须校验，不得把 CHECK 当作唯一防线。
--
-- 明确不在本脚本范围（不建列、不建索引、不建表、不放宽既有表）：
--   * 来源与绑定：不建 source_id / upstream_series_id 列，也不建 external_capture_* /
--     external_episode_binding；来源绑定属 M4-A2，尚未批准实施。
--   * 业务状态：不建 import_state / block_reason / cover_* / approved_* / probe_* 列。
--     也没有软删 / 合并 / 迁移入口，A1 不提供任何写接口。
--   * 不改 video 表（不新增列、不放宽 NOT NULL）；video / video_status 由 M5 创建。
--
-- 幂等与边界：
--   * CREATE TABLE IF NOT EXISTS，重复执行安全；
--   * 但 IF NOT EXISTS **不等于结构校验**：若库中已存在同名但形状不同的旧表，本脚本会整段跳过，
--     既不报错也不修正，所以它不宣称"自动验证旧表形状"。执行后请用只读 information_schema 查询或
--     SHOW CREATE TABLE 核对列、索引、外键与 CHECK。
--   * 不 DROP / ALTER / RENAME 任何对象，不 INSERT / UPDATE / DELETE / TRUNCATE 任何行；
--     不设 FOREIGN_KEY_CHECKS=0（外键按默认方式真实建立），不触碰 video 原表。
--
-- 不得过度声称（安全责任跟着这两张表走）：
--   * local_identity 的不可变只是将来的服务层规则：数据库只保证它非空且唯一，没有触发器，
--     也没有"必须是 UUID"的约束，所以不要把它读成"数据库校验了 UUID 格式"。
--   * local_identity 显式 utf8mb4_bin：大小写敏感，但 pad 属性是 PAD SPACE（尾部空格视为相等）；
--     服务层必须生成规范小写 UUID 并拒绝首尾空白，不得 trim 后合并。
--   * duration_seconds 的 CHECK 只表达 NULL 或 > 0，不替代应用层 Double.isFinite 校验；
--     不据此声称 MySQL 能保存 NaN / Infinity（A1 没有任何时长写入路径）。
--   * UNIQUE KEY (vid) 只保证"一个 vid 不属两个 episode"，不阻止同一集并发各建一个 video；
--     后者需要 M5 的 episode 行锁 + vid IS NULL CAS，唯一键替代不了它。
--   * 本脚本不加密、不提供密钥管理，也不保存任何上游地址或凭据值。
-- ============================================================================

CREATE TABLE IF NOT EXISTS local_video_series (
    id BIGINT NOT NULL AUTO_INCREMENT,
    local_identity CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '服务端随机 UUIDv4，规范小写 8-4-4-4-12；写入后不得 UPDATE；不是 title/order/url',
    title VARCHAR(200) NOT NULL COMMENT '本地展示标题（管理员输入）；不是身份',
    created_by INT NOT NULL COMMENT '创建者（管理员 uid），不是来源目录的 import_uid',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_video_series_identity (local_identity),
    CONSTRAINT fk_local_video_series_created_by FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS local_video_episode (
    id BIGINT NOT NULL AUTO_INCREMENT,
    series_id BIGINT NOT NULL COMMENT '本地作品，不是 video_source_series',
    local_identity CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '服务端随机 UUIDv4；写入后不得 UPDATE；不得由上游 ID/序号/标题/URL 生成',
    display_title VARCHAR(255) NULL COMMENT '本地展示用；可变，不参与身份与唯一键',
    duration_seconds DOUBLE NULL COMMENT '管理员确认后的统一时长；NULL=未确认。A1 没有写入者',
    vid INT NULL COMMENT '导入后才写；NULL=未导入。类型必须与 video.vid 一致',
    created_by INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_video_episode_identity (local_identity),
    UNIQUE KEY uk_local_video_episode_vid (vid),
    KEY idx_local_video_episode_series (series_id, id),
    CONSTRAINT fk_local_video_episode_series FOREIGN KEY (series_id) REFERENCES local_video_series (id),
    CONSTRAINT fk_local_video_episode_vid FOREIGN KEY (vid) REFERENCES video (vid),
    CONSTRAINT fk_local_video_episode_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_local_video_episode_duration
        CHECK (duration_seconds IS NULL OR duration_seconds > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
