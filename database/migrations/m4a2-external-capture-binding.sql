-- ============================================================================
-- M4-A2：有界抓取证据 + 稳定来源绑定 + append-only 绑定版本（只新建四张表）
--
-- 依据：docs/tutorial/external-video-source-local-identity-contract.md 第 5 节（父级已批准的 A2 DDL，
-- 仅批准表文件与静态结构测试）。本脚本新建（顺序固定，外键目标必须先存在）：
--   * external_capture_snapshot  —— 一次抓取的证据头（来源作品 + 线路数 + 条目数 + 条目集合摘要）
--   * external_capture_entry     —— 本次抓取的条目行（线路位置 / 条目位置 / 地址快照）
--   * external_episode_binding   —— 本地剧集与来源作品的稳定绑定（稳定 uid，撤销只置 active=0）
--   * external_binding_revision  —— 只追加的版本行，承担"同一证据只能被采用一次"的占用
--
-- 运行前置（不满足就由 MySQL 直接报错，本脚本不做任何自动补齐、不建来源行）：
--   * users 表已存在：captured_by / bound_by / adopted_by 引用 users.id（INT）；
--   * E1-A 已存在：video_source 与 video_source_series（含唯一键 (source_id, id) 与
--     (source_id, upstream_series_id)，复合外键按它们被引用；本脚本不 INSERT / UPDATE video_source，
--     来源行是运维动作，缺行就拒绝）；
--   * M4-A1 已存在：local_video_series 与 local_video_episode（local_episode_id 引用其 BIGINT 主键）；
--   * MySQL 8.0.16+：本脚本的 CHECK 只有 8.0.16 及以上才真正强制，更低版本会解析后静默忽略，
--     非法取值范围可被直连 SQL 写入而不报错；因此应用层仍须校验，不得把 CHECK 当成唯一防线。
--
-- 本脚本的边界（不扩大的部分）：
--   * 未在任何 MySQL 实例上执行：本片只交付建表文件与静态测试，不连库、不跑迁移、不宣称已建表。
--   * 无写接口：没有抓取任务、没有探测 / 确认、没有写 API，也没有 external_operation /
--     external_source_audit 表；不创建 video / video_status（那是 M5），不写 vid。
--   * 无清理：没有清理任务、定时器或删除入口，保留期未决定，本脚本也不发明天数。
--   * 不改既有表：不改 video_source / video_source_series / local_video_* / users / video 的形状，
--     不 INSERT / UPDATE / DELETE 任何行，不设 FOREIGN_KEY_CHECKS=0。
--
-- 幂等（别把它读成结构校验）：
--   * 四条语句都是 CREATE TABLE IF NOT EXISTS，重复执行安全；
--   * 但 IF NOT EXISTS 不等于结构校验：若库中已存在同名但形状不同的旧表，本脚本整段跳过，
--     既不报错也不修正。执行后请另用只读 information_schema 查询或 SHOW CREATE TABLE 核对
--     列、索引、外键与 CHECK。
--
-- 敏感数据责任（保留期 / 访问控制 / 静态加密都未定，因此本片禁止接真实写入、禁止部署）：
--   * address 是原始上游地址，可能内含签名 token：保存地址就是保存这类敏感材料，本脚本不声称
--     "库里没有明文 token"，也不声称已加密（仓库没有密钥设施，加密 / 轮换 / 访问控制是部署责任）。
--   * 保留期未获决策：不套用 T14 的 90 / 275 天（那只覆盖 outbox / consumed-event）。
--   * 在保留期、访问控制与静态加密责任明确并单独审查前，本片不得接真实写入，也不得部署。
--
-- 宽度对齐解析器（只对齐数字，不证明语义）：
--   * line_key VARCHAR(191) 对齐 AppleCmsJsonAdapter.MAX_LINE_KEY_LENGTH=191；
--   * label VARCHAR(255) 对齐 MAX_EPISODE_LABEL_LENGTH=255；
--   * address VARCHAR(2048) 对齐 MAX_PLAY_ADDRESS_LENGTH=2048，单位是字符不是字节；
--   * entry_payload_hash / address_hash 是 CHAR(64)：CHAR 与 utf8mb4_bin 的 PAD SPACE 比较
--     不证明"恰好 64 位小写十六进制"，更不证明摘要内容对应原地址或条目集合（当前不加格式 CHECK）。
--   * 本脚本不建 address 索引，也不建 address_hash 索引（查询需要时另议，别把不存在当存在）。
--
-- 不得过度声称（DB 保证不了的部分）：
--   * append-only 不是 schema 能强制的：没有触发器、没有权限约束；直连 SQL DELETE revision 行，
--     或把 evidence_entry_id 换成空闲行，数据库都不会拦。UNIQUE(evidence_entry_id) 只是最后防线。
--   * work_identity 与 source_series_id 是否指向同一行：两个外键只保证该 workId 在本来源目录存在、
--     该 series 在本来源存在，跨行相等性 DB 不能比较，必须服务层核对。
--   * line_count / entry_count 是写入前由解析结果确定的计数，DB 没有延迟约束，不保证与实际行数一致。
--   * snapshot / entry / revision 的不可变、local_episode_id / binding_uid / 来源归属 / revision_no
--     的不可修改，全是服务层规则（无 UPDATE / DELETE mapper、无 ON DELETE CASCADE、无触发器）。
--   * current revision = MAX(revision_no) 由未来服务层在锁内读取并核对 expectedRevision，冲突 409；
--     DB 不保证版本连续，也不保证只由服务写入。
--   * 子表外键会让 MySQL 自动补充必要索引；本文件的静态测试只核对显式 DDL，不宣称实际库里
--     只有这些显式索引。
--   * 身份 / 摘要列显式 utf8mb4_bin（大小写敏感），但它是 PAD SPACE 不是 NOPAD：尾空格在比较 /
--     唯一键 / 外键上视为相等，服务层必须拒绝首尾空白，不得 trim 后合并。展示文本
--     （label / address / format）沿用表默认 utf8mb4_unicode_ci。
-- ============================================================================

CREATE TABLE IF NOT EXISTS external_capture_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_id INT NOT NULL,
    source_series_id INT NOT NULL,
    work_identity VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '上游自报 workId 快照；服务层还须核对它等于所引用 series 行的 upstream_series_id',
    line_count SMALLINT NOT NULL COMMENT '本次线路数 1..16（解析器上限 16 线）',
    entry_count INT NOT NULL COMMENT '本次实际落库条目数 1..16000；须与落库行数同事务核对',
    entry_payload_hash CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '规范化条目集合的 SHA-256；不是签名，不是内容真实性证明',
    captured_by INT NOT NULL COMMENT '发起抓取的管理员；不是 episode owner',
    captured_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_external_capture_snapshot_scope (source_id, source_series_id, id),
    CONSTRAINT fk_external_capture_snapshot_series FOREIGN KEY (source_id, source_series_id)
        REFERENCES video_source_series (source_id, id),
    CONSTRAINT fk_external_capture_snapshot_work FOREIGN KEY (source_id, work_identity)
        REFERENCES video_source_series (source_id, upstream_series_id),
    CONSTRAINT fk_external_capture_snapshot_by FOREIGN KEY (captured_by) REFERENCES users (id),
    CONSTRAINT ck_external_capture_snapshot_line_count CHECK (line_count BETWEEN 1 AND 16),
    CONSTRAINT ck_external_capture_snapshot_entry_count CHECK (entry_count BETWEEN 1 AND 16000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_capture_entry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    source_id INT NOT NULL,
    source_series_id INT NOT NULL,
    line_ordinal SMALLINT NOT NULL COMMENT '本次快照内线路位置（1 起）；仅本次证据定位，不是跨快照身份',
    entry_ordinal SMALLINT NOT NULL COMMENT '本线路内条目位置（1 起）；仅本次证据定位',
    line_key VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL
        COMMENT '上游线路键快照；宽度对齐 AppleCmsJsonAdapter.MAX_LINE_KEY_LENGTH=191；server-only',
    label VARCHAR(255) NULL COMMENT '上游标签快照；对齐 MAX_EPISODE_LABEL_LENGTH=255；不可信文本，需转义',
    address VARCHAR(2048) NOT NULL
        COMMENT '原始地址，可能内含签名 token；宽度对齐 MAX_PLAY_ADDRESS_LENGTH=2048（字符不是字节）；server-only，不返前端/不写日志/不写 audit',
    address_hash CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    format VARCHAR(16) NULL COMMENT 'HLS / MP4 / UNKNOWN；不凭后缀判定',
    PRIMARY KEY (id),
    UNIQUE KEY uk_external_capture_entry_position (snapshot_id, line_ordinal, entry_ordinal),
    UNIQUE KEY uk_external_capture_entry_scope (id, source_id, source_series_id),
    CONSTRAINT fk_external_capture_entry_snapshot FOREIGN KEY (source_id, source_series_id, snapshot_id)
        REFERENCES external_capture_snapshot (source_id, source_series_id, id),
    CONSTRAINT ck_external_capture_entry_line_ordinal CHECK (line_ordinal BETWEEN 1 AND 16),
    CONSTRAINT ck_external_capture_entry_entry_ordinal CHECK (entry_ordinal BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_episode_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    binding_uid CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT '服务端生成规范小写 UUIDv4；稳定 id；重指向不变；禁止 UPDATE',
    local_episode_id BIGINT NOT NULL
        COMMENT '归属本地剧集；写入后不得 UPDATE —— 换剧集必须新建 binding',
    source_id INT NOT NULL,
    source_series_id INT NOT NULL,
    active TINYINT NOT NULL DEFAULT 1 COMMENT '1=当前有效 0=已撤销；撤销不删行、不释放证据占用',
    bound_by INT NOT NULL,
    bound_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_external_episode_binding_uid (binding_uid),
    UNIQUE KEY uk_external_episode_binding_scope (id, source_id, source_series_id),
    KEY idx_external_episode_binding_episode (local_episode_id, active, id),
    CONSTRAINT fk_external_episode_binding_episode FOREIGN KEY (local_episode_id)
        REFERENCES local_video_episode (id),
    CONSTRAINT fk_external_episode_binding_series FOREIGN KEY (source_id, source_series_id)
        REFERENCES video_source_series (source_id, id),
    CONSTRAINT fk_external_episode_binding_by FOREIGN KEY (bound_by) REFERENCES users (id),
    CONSTRAINT ck_external_episode_binding_active CHECK (active IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_binding_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    binding_id BIGINT NOT NULL,
    revision_no INT NOT NULL COMMENT '1 起、按采用顺序递增；current = MAX(revision_no)',
    source_id INT NOT NULL,
    source_series_id INT NOT NULL,
    evidence_entry_id BIGINT NOT NULL COMMENT '本版本采用的证据；全局 UNIQUE，即永久占用',
    adopted_by INT NOT NULL,
    adopted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_external_binding_revision_no (binding_id, revision_no),
    UNIQUE KEY uk_external_binding_revision_evidence (evidence_entry_id),
    CONSTRAINT fk_external_binding_revision_binding FOREIGN KEY (binding_id, source_id, source_series_id)
        REFERENCES external_episode_binding (id, source_id, source_series_id),
    CONSTRAINT fk_external_binding_revision_entry FOREIGN KEY (evidence_entry_id, source_id, source_series_id)
        REFERENCES external_capture_entry (id, source_id, source_series_id),
    CONSTRAINT fk_external_binding_revision_by FOREIGN KEY (adopted_by) REFERENCES users (id),
    CONSTRAINT ck_external_binding_revision_no CHECK (revision_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
