-- ============================================================================
-- E1-A：外部视频源接入的最小基础表（只新建两张表，不改既有表、不改任何既有数据）
--
-- 本脚本新建：
--   * video_source         —— 一个上游站点的来源配置
--   * video_source_series  —— 该来源下的一部上游作品（目录分组）
--
-- 明确不在本脚本范围（因此不建列、不建索引、不建表）：
--   * 源级串行租约：active_job_id / active_job_expire_at 及其索引故意不建。
--     租约协议仍待后续契约收口；先落列会让人误以为已有串行保证，将来再加。
--   * 作者归属：两张表都**没有** uid / owner_uid / created_by / import_uid。
--     归属只能来自将来导入任务行上的登录上下文，不能由来源或作品目录携带。
--   * 凭据值：只有 credential_ref（部署配置里的凭据引用名，NULL=无需凭据）。
--     本脚本不建任何密码 / token / access key / 私钥 / 密钥派生值列。
--   * 剧集绑定、线路、地址、分类映射、同步任务、审计等其它 E1 表。
--   * 任何 video 表改动（不新增列、不放宽 NOT NULL），也不建 HTTP 接口。
--
-- 幂等：
--   * CREATE TABLE IF NOT EXISTS，重复执行安全；
--   * 但 IF NOT EXISTS **不等于结构校验**：如果库中已存在同名但形状不同的旧表，
--     本脚本会整段跳过、既不报错也不修正。因此本脚本不宣称“自动验证旧表形状”，
--     执行后请用文件末尾的只读 information_schema 查询核对**列**（列名 / 类型 / 可空 / 默认值）；
--     该查询**只查列**，不查索引与外键，索引与外键请另用 SHOW CREATE TABLE 或
--     information_schema.statistics / key_column_usage 核对。
--   * 不 UPDATE / DELETE / INSERT / ALTER / DROP 任何既有对象。
--
-- 身份列语义（别把 utf8mb4_bin 读成“字节严格身份”）：
--   * code 与 upstream_series_id 显式用 utf8mb4_bin，因此**大小写敏感**：
--     'abc' 与 'ABC' 是两个不同来源 / 两部不同作品。
--   * 但 utf8mb4_bin 的 pad 属性是 **PAD SPACE**（NO PAD 的只有 utf8mb4_0900_* 系列）：
--     比较时**忽略尾部空格**，'abc' 与 'abc ' 在两个唯一键上是同一行 —— 第二次写入得到
--     显式的 1062 唯一键冲突，而不是两个字节严格不同的身份。
--   * 因此后续导入必须**拒绝**首尾带空白的来源编码 / 上游作品 ID（显式报错、进人工处理），
--     **不得静默 trim 后合并** —— 静默 trim 会把上游两个真实不同的 ID 合并成一行。
--   * 本轮不改 collation（换 utf8mb4_0900_bin 会牵动表默认排序规则与兼容性决策，需单独决定），
--     也不实现任何校验代码。
--
-- 版本要求（不能静默降级）：
--   * 两张表的 enabled / sync_state 取值范围用 CHECK 约束表达，只有
--     MySQL **8.0.16 及以上**才真正强制 CHECK；更低版本会**解析后静默忽略**，
--     非法取值可以被直连 SQL 写入而没有任何报错。因此部署前提是 MySQL 8.0.16+，
--     并且不得把 CHECK 当作唯一防线（应用层仍需校验）。
--
-- 适用：MySQL 8.0.16+，库字符集 utf8mb4 与 database/feibijiubi.sql 保持一致。
-- ============================================================================

CREATE TABLE IF NOT EXISTS video_source (
    id INT NOT NULL AUTO_INCREMENT COMMENT '来源站ID',
    -- 大小写敏感：'abc' 与 'ABC' 是两个不同来源（identity 列只对自身显式 bin，表默认仍是 ci）
    -- 但 utf8mb4_bin 是 PAD SPACE：尾空格视为相等（'abc' 与 'abc ' 冲突），不是字节严格身份；
    -- 导入必须拒绝首尾带空白的来源编码，不得静默 trim 合并（详见文件头“身份列语义”）
    code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '站内稳定编码，人工指定；部署白名单的引用键，大小写敏感',
    name VARCHAR(100) NOT NULL COMMENT '展示名',
    adapter_type VARCHAR(32) NOT NULL COMMENT '适配器标识；具体取值由适配器注册表定义',
    base_url VARCHAR(500) NOT NULL COMMENT '上游根地址；仅服务端使用，不对外返回',
    enabled TINYINT NOT NULL DEFAULT 0 COMMENT '0=停用 1=启用；默认停用，需显式启用',
    credential_ref VARCHAR(128) NULL
        COMMENT '凭据引用名（部署配置中的名字）；NULL=无需凭据；禁止写入凭据值',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_source_code (code),
    CONSTRAINT ck_video_source_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='外部视频来源站：默认停用，无租约列，无作者归属，只存凭据引用名';

CREATE TABLE IF NOT EXISTS video_source_series (
    id INT NOT NULL AUTO_INCREMENT COMMENT '上游作品分组ID',
    source_id INT NOT NULL COMMENT '来源站ID',
    -- 大小写敏感：作品身份必须按上游原样比较，不能与大小写变体合并
    -- 同样是 PAD SPACE：尾空格视为相等，所以导入必须拒绝首尾带空白的上游作品 ID，不得静默 trim 合并
    upstream_series_id VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '上游作品稳定身份，必须来自上游持久标识；禁止用标题/序号/下标生成',
    title_snapshot VARCHAR(255) NULL COMMENT '上游标题快照；仅展示，不是身份，可变',
    mc_id VARCHAR(20) NULL COMMENT '本地主分区ID；NULL=尚未映射',
    sc_id VARCHAR(20) NULL COMMENT '本地子分区ID；NULL=尚未映射',
    cover_url_external VARCHAR(500) NULL COMMENT '上游外链封面；只由同步写，认领接口不得清空',
    sync_state TINYINT NOT NULL DEFAULT 0 COMMENT '0=未同步 1=同步中 2=已同步 3=同步失败',
    last_synced_at DATETIME NULL COMMENT '最近一次完成同步时间；从未同步为 NULL',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 作品身份唯一：同一来源内 (来源, 上游作品ID) 只能有一行
    UNIQUE KEY uk_video_source_series_identity (source_id, upstream_series_id),
    -- 只用于让将来的复合外键 (source_id, series_id) 有被引用键；id 已是主键，本键不改变语义
    UNIQUE KEY uk_video_source_series_source_id (source_id, id),
    CONSTRAINT fk_video_source_series_source FOREIGN KEY (source_id) REFERENCES video_source (id),
    CONSTRAINT ck_video_source_series_sync_state CHECK (sync_state IN (0, 1, 2, 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='上游作品目录：稳定身份唯一，无 import_uid（归属只在将来的导入任务行）';

-- 只读核查（可选，可作为执行后的手工核对依据；本脚本不自动校验旧表形状）：
--   下面的 SELECT 只读 information_schema.columns，所以**只能核对列**：
--   应查到 video_source 9 列、video_source_series 11 列，且列名 / 类型 / 可空 / 默认值符合预期。
--   **索引与外键不在本查询范围内**（它不读 information_schema.statistics /
--   key_column_usage / referential_constraints）。期望的“两张表各只有 PRIMARY KEY + 各自的
--   唯一键、唯一键内部列顺序、外键只有 video_source_series → video_source”请另用
--     SHOW CREATE TABLE video_source;  SHOW CREATE TABLE video_source_series;
--   或对应的只读 information_schema 查询核对，别把本 SELECT 当成结构已核对。
SELECT table_name, ordinal_position, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('video_source', 'video_source_series')
ORDER BY table_name, ordinal_position;
