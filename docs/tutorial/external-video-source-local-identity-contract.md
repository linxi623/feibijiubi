# 外部视频来源 M4 数据契约（第一片：本地身份与来源绑定）

- 状态：**仅第4节 M4-A1 两表设计获父级批准实施；A2及后续仍为candidate**。目前未执行迁移、未连接MySQL；文件/静态测试的后续进度以实施计划为准，不把设计当数据库实测。
- 本轮只读设计：未读 `application.yml` / 密钥，未联网，未启动容器，未跑测试，不改其它任何文件。
- 不继承旧 E0 的 source-owned episode 形状（`video_source_episode` / `episode_candidate` 已废弃）；`video_source` / `video_source_series` 仍是**纯上游目录**，本片不改这两张表。

## 最新决定：首版简化同步流程

用户已确认采用同步探测、人工确认、短事务保存。本文后续关于通用operation表、任务租约/fencing、崩溃接管、强制operationId/请求指纹及独立审计体系的提案均被覆盖，不再实施，也不作为首版阻塞项。只保留唯一约束、导入时episode锁/已有vid复用、确认时expectedRevision检查，以及必要操作者/时间/理由记录。请求失败人工重试，响应丢失先查询当前结果，不承诺自动续跑或完整请求重放。详见人工导入计划§0。

A1/A2 SQL均未执行，既有静态验收只说明文件符合当时契约；后续可按最小人工闭环精简，不因已经写了DDL就强制实现所有表。真实部署仍需正常敏感数据权限保护，不在首版自建加密/归档平台。

## 1. 已定规则（直接作为实现前提，不再询问）

- 跨 source 的同一内容由**管理员确认**共享一个本地 episode 与唯一 `vid`；系统不做自动合并。
- 本地 series / episode 独立于来源；本地身份是服务端生成的**随机 UUID**，不是 title / order / URL；上游目录只作来源。
- 首次导入的管理员是 `video.uid`（owner）；后续加 source 不改 owner；互动、统计、进度共用原 `vid`。
- 上游有稳定 `workId`，缺稳定 `episodeId`；映射全靠管理员，禁止下次刷新按位置 / title / URL 自动重映射。
- 探测结果只是候选；管理员可确认或改写为**有限且 > 0 的秒数**，本地 episode 使用统一 local duration；每 line 保留自己的探测证据。
- 不同剪辑 / 广告时间轴不能因为时长相同就判定同内容；标题或秒数都不是合并证据。
- 导入前可暂存；缺管理员确认的时长**不创建 video**；已导入后不通过补齐 API 静默改 `video.duration`；M5 才创建 `video` / `video_status`。
- M4 不自动启用播放；封面不接 M4（M5 用现有 COS 管理员上传再认领，另行审查），禁止后端下载 / 转存外链封面。

## 2. 与 M3 配置的接线前置（M4 的硬前提）

M3 只有部署配置（`app.external-video-source.*` + `ExternalVideoSourceRegistry`），**尚无任何 DB 接线**。M4 任何写路径都必须同时满足，否则拒绝（不降级、不自动补行）：

1. `sourceCode` 命中的 descriptor 必须 `enabled=true`，且 DB 存在 `video_source.code = descriptor.code`（`utf8mb4_bin` 精确相等，不做 trim；首尾空白直接拒绝）；
2. DB `video_source.base_url` 必须与 descriptor `apiBase()` **完全相等**（同一批准 base），不一致即拒绝；M4 不 UPDATE `base_url`；
3. DB `video_source.enabled` 必须为 1；`enabled=0` 只拒绝新写入，不动既有行；
4. `sourceId` 恒为 `video_source.id`（INT），**不得**把配置 `sourceCode` 当 DB id 或外键；需要复合归属时用已存在的 `video_source_series(source_id, id)` 被引用键；
5. 为了 M4 去任意 INSERT / 启用 `video_source` 行是违约：来源行是运维动作，缺行就是拒绝，**不自动补行**；`sync_state` 本片不读不写；`adapter_type` 必须等于实现已支持的适配器（当前只有 AppleCMS），`credential_ref` 非 NULL 时明确拒绝（当前实现无凭据支持）——这两列一律 fail-closed，**不得**“无条件忽略”。

## 3. 拆片（A1 可单独实现与验收）

| 片 | 内容 | 可单独验收的边界 |
| --- | --- | --- |
| **M4-A1** | 仅本地身份两张表：`local_video_series`、`local_video_episode` | 建表文件 + 静态结构测试；无来源表、无绑定、无 video 写入；不提供任何接口 |
| M4-A2 | 有界证据 `external_capture_snapshot` / `external_capture_entry` + 稳定 `external_episode_binding` + append-only `external_binding_revision` | 同一证据只能被采用一次（append-only，不释放）；无抓取任务、无写接口、无探测/确认 |
| M4-B | 探测证据 `external_duration_probe` + 确认 `external_duration_confirmation` + 本地时长写入 | 同一事务：确认 + 本地时长 + audit + operation |
| M4-C | 线路地址 `external_line_address`（pending → approved 两级） | 新地址不替换已批准值；播放仍未接线 |
| M4-D | 幂等/审计 `external_operation` / `external_source_audit`（可随首个写接口落地） | operationId 唯一 + 请求指纹冲突 409 |
| M5 | 导入事务：锁 episode → 校验确认版本 → 建 `video` / `video_status` → CAS 绑定 `vid` | 另有验收；不在本片 |

A1 为什么不含绑定：绑定必须指向**服务器已保存**的 evidence 行，否则只能退回 position / title / URL 这类被禁止的身份来源。因此“来源绑定”作为 A2 的第一件事紧随 A1，而不是预建整套未审状态（A1 故意没有 `import_state` / `block_reason` / `cover_*` / `approved_*` / `probe_*` 列）。

## 4. M4-A1 表结构

ID 规则：引用既有主键的列必须是 `INT`（`users.id`、`video.vid`、`video_source.id`、`video_source_series.id`）；本片新建的本地主键用 `BIGINT`。身份列大小写敏感（`_bin`，与 E1-A 一致）且是 PAD SPACE：服务端必须生成规范小写 UUID 并拒绝首尾空白，**不得 trim 后合并**。校验用 `CHECK`，但只有 MySQL **8.0.16+** 强制；低版本会静默忽略，因此不得把 CHECK 当唯一防线。

```sql
CREATE TABLE local_video_series (
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

CREATE TABLE local_video_episode (
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
```

A1 的准确边界（不要扩大）：

- `UNIQUE (vid)` 只保证“一个 `vid` 不属两个 episode”，**不阻止**同集并发各建一个 `video` 再互相覆盖；那是 M5 的 episode 行锁 + `vid IS NULL` CAS 的事。
- `duration_seconds` 的合法性是 `NULL` 或 `> 0`；此 CHECK 不替代应用层 `Double.isFinite` 校验，不据此断言 MySQL 可以保存 `NaN`/`Infinity`，且 A1 不提供任何时长写入路径。
- `local_identity` 不可变只是服务层规则（无 UPDATE mapper、无触发器）；数据库只能保证唯一与非空。
- 排序暂用 `id`；自定义顺序属后续，identity 与唯一键永不包含顺序。
- 没有软删 / 合并 / 迁移入口；历史多 `vid` 数据另议。

## 5. M4-A2：有界证据 + 稳定绑定 + append-only 版本（仅批准表文件及静态测试，禁止执行/开放写入）

父级 A2 阻塞项的修复：证据占用不再由**可变 binding 行上的 `UNIQUE(evidence_entry_id)`** 承担，而由**只追加、不删除**的 revision 行承担；`binding` 只保留稳定身份与不可变归属（本地剧集 + 来源作品）。重新指向 = 追加一行新 revision，撤销 = `active=0`，两者都**不释放**任何历史证据。仅 audit 哈希不承担唯一性，本片也不用 audit 表证明永久占用。

前置：M4-A1 两表与 E1-A 两表已存在。类型规则同 §4：新表主键 / 行 id 用 `BIGINT`，引用 `users` / `video_source` / `video_source_series` / `video` 的列保持 `INT`。本片仍**只有建表文件**：不执行迁移、不连库、无抓取任务、无写接口、无审计行为。

```sql
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
```

关键语义：

- **一次抓取 = 一条 snapshot + 有界 entry 行**：线路 1..16 × 每条 1..1000 条，单次快照最多 16000 行，来自 E2-B 解析器上限（响应体 512 KiB）；超限在解析期拒绝，不落“整页无界 JSON”。
- `line_ordinal` / `entry_ordinal` 只是**本次证据的定位**，不是跨快照身份；新快照必须管理员重新选择，系统不得认为“第 N 项还是同一集”。
- **不同快照之间机器无法去重**：不建 `UNIQUE(source_series_id, entry_payload_hash)`，不按 address / label / 位置合并；两个管理员把同内容各建一个 episode / `vid` 属于人工判断，不声称已解决。
- **证据占用是 append-only 的**：重新指向插入新的 `external_binding_revision` 行，旧行永不删除，因此 `UNIQUE(evidence_entry_id)` 的占用在“行存在”期间永久有效；撤销 binding 只写 `active=0`，行、revision、证据全部保留。
- **同一证据重复采用**：① 该证据正是本 binding 的 current revision 且 `active=1` → **幂等**（不新增行、不 +1、返回现有 revision，零写）；② 该证据已被任何 binding 的任一 revision 引用（含本 binding 的历史 revision、已撤销 binding）→ 固定码 `EVIDENCE_ALREADY_BOUND` **明确拒绝**；不跳版本、不改 current、不静默复用。
- **current revision = `MAX(revision_no)`**：不设独立指针表、不建循环 FK、不需要分段 DDL。未来写请求必须携带 expectedRevision；按 episode → binding 加锁，在锁内读取当前最大版本并核对预期值，再追加 current+1。版本冲突返回409，禁止自动重读后替管理员覆盖新选择。唯一键仅作最后防线，DB不保证版本连续，也不保证只由服务写入；INT版本耗尽明确拒绝，不回绕。
- **跨来源防串**：binding 固定 `(source_id, source_series_id)`；revision 的两个复合外键都携带同一对列，所以“A 来源的 binding 指向 B 来源的条目”在 DB 层不可能；entry 的复合外键同时保证 entry 与 snapshot 同源、同作品。
- **collation 归属**：身份 / 摘要列显式 `utf8mb4_bin`（`work_identity`、`line_key`、`entry_payload_hash`、`address_hash`、`binding_uid`，大小写敏感），展示文本（`label`、`address`、`format`）用表默认 `utf8mb4_unicode_ci`。`utf8mb4_bin` 是 **PAD SPACE 不是 NOPAD**：尾空格在比较 / 唯一键 / 外键上视为相等；本轮不换 `utf8mb4_0900_bin`（与 E1-A 一致，做决定前不改表默认排序规则）。
- 同一 local episode 允许多条 binding（不同来源 / 不同线路），撤销不删除任何行。

**DB 不能保证的部分（必须如实记录）**：

1. append-only **不是 schema 能强制的**：没有触发器、没有权限约束；直连 SQL 的 `DELETE`，或把 revision 的 `evidence_entry_id` 换成空闲行，数据库不会拦。所以这里是“append-only 业务行 + UNIQUE”，不是“数据库永久唯一”，也不声称能防直连 SQL。
2. `work_identity` 与 `source_series_id` 是否指向同一行：两个外键只保证该 workId 在本来源目录存在、该 series 在本来源存在；**相等性必须服务层核对**（DB 不能跨行比较）。且 `utf8mb4_bin` 是 PAD SPACE：带尾空格的 workId 也能通过外键，服务层必须拒绝首尾空白，不得 trim 合并。
3. `line_count` / `entry_count`：两者在写入前已由解析结果确定（先写 snapshot、再写 entry 行），落库后服务层在同一事务内核对实际行数；DB 没有延迟约束，也不保证二者一致。
4. snapshot / entry / revision 的不可变、以及 `local_episode_id` / `binding_uid` / 来源归属 / `revision_no` 的不可修改，全是服务层规则（无 UPDATE / DELETE mapper、无 `ON DELETE CASCADE`、无触发器）。
5. 摘要必须由未来写方生成恰好64位小写十六进制；CHAR/PAD SPACE不证明格式正确，更不证明摘要对应原地址/条目集合。当前不增加格式CHECK。
6. 子表外键可使MySQL自动补充必要索引；文件测试只核对显式DDL，不得宣称实际库只有这些索引。
7. `CHECK` 只表达范围，且只有 MySQL **8.0.16+** 强制；低版本静默忽略 —— 应用层仍须校验（与 A1 同一前提）。

**误映射恢复限制（管理员误操作的代价）**：

- 证据绑错 episode 后，**不能**改 binding 的 `local_episode_id`，也**不能**把该证据改绑到正确 episode（`EVIDENCE_ALREADY_BOUND`）；
- 唯一恢复路径：撤销错误 binding（`active=0`）→ 重新抓取产生新 snapshot / entry → 新 binding（新 `binding_uid`）指向正确 episode；
- A2 **不提供纠正接口**：授权、审计、并发与回滚语义未设计，禁止用“改绑定”这类静默修补；
- 因此一次误操作的代价是“重新抓取一次 + 一条永久占用的证据 + 一条已撤销 binding”，错误历史无法被抹掉（这正是不做纠正接口的原因）。

**A2 边界**：只交付四个 `CREATE TABLE IF NOT EXISTS` 文件（未来实现）+ 静态结构测试；无抓取 / 探测 / 确认、无写 API、无 `external_operation` / audit 表，不创建 `video` / `video_status`（M5）。§2 的来源守卫在写路径落地时全部保留，并新增：`adapter_type` 必须是实现已支持的适配器（当前只有 AppleCMS），`credential_ref` 非 NULL 明确拒绝 —— 两者 fail-closed，不得“无条件忽略”。

## 6. M4-B / C 的不变量（只列契约，不写完整 DDL）

- `external_duration_probe`：引用 `binding_id` + `revision_no`（不再用可变的 “binding.version” 命名）+ `address_hash` + `manifest_sha256` + `candidate_seconds`（候选值，允许 NULL）+ `outcome`（SUCCEEDED / FAILED）+ `failure_code` + `probed_at` + `probed_by`。每条 line 独立一行；探测失败也是证据，**不得**写成成功。
- `external_duration_confirmation`：`kind` = `PROBE_CONFIRMED`（必须引用 probe）或 `MANUAL_VERIFIED`（probe 为 NULL 且 `reason` NOT NULL）；同时保存 `revision_no`、操作者与时间。
- 确认事务：锁 `local_video_episode` → `duration_version` CAS → 核对 probe 属于当前 binding 且该 `revision_no` 仍是 current、`address_hash` 未变 → 插入 confirmation → 更新 episode 时长与版本 → 追加 audit。任一不匹配即 409、零业务写；旧 probe 不能批准新地址或新映射。
- `external_line_address`：`UNIQUE (binding_id)` 的“已批准 + 单一待审”两段 + revision CAS；新地址不替换已批准值，M4 不启用播放。
- `audit`：append-only，只存标量与hash，不存原始URL/token；无CASCADE、无UPDATE/DELETE。`operation` 状态是否可变及其CAS/恢复另行设计，不能一边要求状态转换一边声明operation无UPDATE。
- M5：锁 episode → 校验时长与 current revision → 新建 `video` / `video_status` → `UPDATE ... SET vid=? WHERE id=? AND vid IS NULL`（影响行数必须为 1）→ audit。

## 7. 网络 operation、租约与幂等（历史提案，已由简化决定取消）

- A2 不实现任何网络任务。抓取 / 探测的租约、fencing（epoch / token）、过期判定、崩溃重试与接管仍是 M4 后续候选；**不得**用 `external_operation` 的 `STARTED` 状态冒充已解决。
- 同 `operationId` 并发、旧执行者写回、锁顺序（episode → binding → revision 追加 → operation / audit）必须与首个写服务一并定稿；只写“operation 追加”不算方案。
- 审计与幂等（`operationId` + 请求指纹；同 id 同指纹幂等、不同指纹 409）必须与**首个写服务同事务**落地。本片只有 schema，没有任何审计 / 幂等行为可声称。

## 8. 保留期与地址安全责任（历史部署讨论，不再阻塞同步流程开发）

- A2 的 snapshot / entry / revision 保留期**未获用户决策**：禁止发明天数，也禁止把 T14 的 90 / 275 天套过来（那只覆盖 outbox / consumed-event）。
- 默认**无自动删除**：本片不建清理任务、定时器或删除入口，也没有“到期即删”的承诺。
- `address` 可能内含签名 token，保存地址就是保存这类敏感材料：不声称“DB 无明文 token”，也不声称已加密（仓库无密钥设施，加密 / 轮换 / 访问控制是部署责任，需单独审查）。
- 在保留期、访问控制与静态加密责任明确并单独审查前，A2 **不接真实写入、不部署**；这些未定项就是 A2 不能上线的原因，不能因为“未定”而把风险从文档里删掉。
- M3-B preview 只返回位置与标签、**没有 snapshot token**，不能当写证据；未来写接口只接受服务器已保存的 `evidenceId` / `binding_uid` / revision，客户端不能上传任意 URL 宣称“已 fetch”。

## 9. 未定点（父可裁定）

1. 保留期 + 访问控制 + 加密责任：阻塞 A2 写入口（§8），A2 只到建表为止。
2. 是否要独立的 `external_binding_current` 指针表：本片选 `MAX(revision_no)` 推导，避免循环 FK 与分段 DDL；若将来需要 DB 级“指针必须最新”，再单独加表。
3. 同一 local episode 是否允许同 source series 的多条 binding：本片允许（线路级证据），未加该唯一约束。
4. 撤销后是否允许“复活”同一 binding：建议不允许，复活 = 新 binding + 新证据。
5. 误映射纠正接口：未设计（§5 已记录恢复限制），需要单独的授权 / 审计 / 并发方案。
6. `address` 原文在保留期内是否长期留存：与 §8 同一项决策，未定。

## 10. 交接（本轮）

已解决（A2 设计层）：

- 永久占用改由 append-only `external_binding_revision` 承担，不再挂在可变 binding 行上；撤销 / 重指向都不释放旧证据。
- binding 稳定 id（`binding_uid`）+ 不可变 `local_episode_id` + 固定来源归属 + `active`；换 episode 必须新建 binding。
- 旧证据不跨 episode 复用；重复采用的处理明确为“current 幂等 / 其它 `EVIDENCE_ALREADY_BOUND` 拒绝”，不跳版本。
- current 用 `MAX(revision_no)` 推导；未来写入必须锁binding并校验expectedRevision，冲突409、不自动重试覆盖；唯一键是最后防线。
- 跨来源关联由复合外键在 DB 层挡住；误映射恢复限制写清楚。
- 字段宽度对齐解析器常量（line_key 191 / label 255 / address 2048 字符）；`address` 用 `VARCHAR(2048)`（字符不是字节；本片既不建原地址索引，也不建address_hash索引，查询需要时另议）。
- `adapter_type` / `credential_ref` 明确 fail-closed；不自动补来源行。

仍未定：

- 保留期、访问控制、静态加密责任（阻塞 A2 写入口）；`address` 原文留存未决。
- 网络 operation 租约 / fencing / 接管；审计与幂等的首个写服务同事务设计；误映射纠正接口；指针表是否必要。

本轮边界与下一步：

- 只改本文档的 A2 部分：未改 A1 两表、未写 SQL / 代码 / 其它 docs、未执行迁移、未连库、未跑测试；不声称任何测试通过，也不自我验收（A2 仍需父级评审）。
- 父级已批准§5仅落一个包含四表的迁移文件、初始化SQL镜像和静态测试；不执行SQL、不开放写入口。未来同证据幂等仍须先校验operationId/actor/action/请求指纹；M4-B所需duration_version尚未建列，需独立迁移评审。
