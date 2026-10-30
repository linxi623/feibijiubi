package com.feibijiubi.backend.videosource.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-A2：有界抓取证据 + 稳定来源绑定 + append-only 绑定版本（{@code external_capture_snapshot} /
 * {@code external_capture_entry} / {@code external_episode_binding} / {@code external_binding_revision}）
 * 的离线结构契约。
 *
 * <p>只读两份 {@code .sql} 文本，不连库、不启动 Spring、不执行任何 SQL、不跑迁移。它是一组**静态、有限的
 * 文本结构检查**：证明"脚本里写了什么"，**不**证明某个 MySQL 实例真的建成了这样。以下都未在本轮验证：
 * DDL 是否被该实例接受、{@code CHECK} 是否被强制（需要 MySQL 8.0.16+）、复合外键在既有
 * {@code video_source_series} / {@code local_video_episode} 上是否真能建立、
 * {@code UNIQUE(evidence_entry_id)} 在直连 SQL 下的实际行为、{@code utf8mb4_bin} 的 PAD SPACE 比较行为。</p>
 *
 * <p>刻意**不**当成契约的写法：SQL 注释（列注释 / 表注释可以自由改写）、列在表体里的声明顺序、外键与索引的
 * 声明顺序。真正当契约的是：迁移文件的顶层语句只能是四条 {@code CREATE TABLE IF NOT EXISTS}、四表顺序
 * （snapshot → entry → binding → revision）、每列的类型 / NULL / 默认值、单列主键、<b>唯一键内部的列顺序</b>、
 * 每个外键的列序与引用列（且无级联）、{@code CHECK} 表达式（比较时去掉全部空白）、表选项，以及
 * {@code database/feibijiubi.sql} 的镜像按**逐条表体项**与迁移一致并追加在文件末尾。</p>
 *
 * <p>明确**不**声称的内容：外键会让 MySQL 自动给子表补索引，所以本文件只核对<b>显式声明</b>的索引集合，
 * 不宣称实际库里只有这些索引；{@code address_hash} / {@code entry_payload_hash} 是 {@code CHAR(64)}，
 * 不证明"恰好 64 位小写十六进制"，也不证明摘要对应原地址 / 条目集合；append-only 不是 schema 强制的；
 * {@code work_identity} 与 {@code source_series_id} 是否同一行只能由服务层核对。</p>
 */
class ExternalCaptureBindingSchemaTests {

    private static final Path MIGRATION = Path.of(
            "database/migrations/m4a2-external-capture-binding.sql");
    private static final Path SCHEMA = Path.of("database/feibijiubi.sql");

    private static final String SNAPSHOT = "external_capture_snapshot";
    private static final String ENTRY = "external_capture_entry";
    private static final String BINDING = "external_episode_binding";
    private static final String REVISION = "external_binding_revision";

    /** 四表顺序固定：外键目标必须先存在（entry → snapshot，revision → binding / entry）。 */
    private static final List<String> TABLES = List.of(SNAPSHOT, ENTRY, BINDING, REVISION);

    private static final String TABLE_OPTIONS =
            "ENGINE=INNODB DEFAULT CHARSET=UTF8MB4 COLLATE=UTF8MB4_UNICODE_CI";

    private static final String BIN_NOT_NULL =
            "CHARACTER SET UTF8MB4 COLLATE UTF8MB4_BIN NOT NULL";
    private static final String CREATED_AT_DEFINITION =
            "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)";
    private static final String UPDATED_AT_DEFINITION =
            "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)";

    /** 每列的类型 / NULL / 默认值（规范化后、去掉 {@code COMMENT} 的精确文本）；键集合就是列集合契约。 */
    private static final Map<String, String> SNAPSHOT_DEFINITIONS = Map.ofEntries(
            Map.entry("ID", "BIGINT NOT NULL AUTO_INCREMENT"),
            Map.entry("SOURCE_ID", "INT NOT NULL"),
            Map.entry("SOURCE_SERIES_ID", "INT NOT NULL"),
            Map.entry("WORK_IDENTITY", "VARCHAR(191) " + BIN_NOT_NULL),
            Map.entry("LINE_COUNT", "SMALLINT NOT NULL"),
            Map.entry("ENTRY_COUNT", "INT NOT NULL"),
            Map.entry("ENTRY_PAYLOAD_HASH", "CHAR(64) " + BIN_NOT_NULL),
            Map.entry("CAPTURED_BY", "INT NOT NULL"),
            Map.entry("CAPTURED_AT", CREATED_AT_DEFINITION));

    private static final Map<String, String> ENTRY_DEFINITIONS = Map.ofEntries(
            Map.entry("ID", "BIGINT NOT NULL AUTO_INCREMENT"),
            Map.entry("SNAPSHOT_ID", "BIGINT NOT NULL"),
            Map.entry("SOURCE_ID", "INT NOT NULL"),
            Map.entry("SOURCE_SERIES_ID", "INT NOT NULL"),
            Map.entry("LINE_ORDINAL", "SMALLINT NOT NULL"),
            Map.entry("ENTRY_ORDINAL", "SMALLINT NOT NULL"),
            Map.entry("LINE_KEY", "VARCHAR(191) " + BIN_NOT_NULL.replace("NOT NULL", "NULL")),
            Map.entry("LABEL", "VARCHAR(255) NULL"),
            Map.entry("ADDRESS", "VARCHAR(2048) NOT NULL"),
            Map.entry("ADDRESS_HASH", "CHAR(64) " + BIN_NOT_NULL),
            Map.entry("FORMAT", "VARCHAR(16) NULL"));

    private static final Map<String, String> BINDING_DEFINITIONS = Map.ofEntries(
            Map.entry("ID", "BIGINT NOT NULL AUTO_INCREMENT"),
            Map.entry("BINDING_UID", "CHAR(36) " + BIN_NOT_NULL),
            Map.entry("LOCAL_EPISODE_ID", "BIGINT NOT NULL"),
            Map.entry("SOURCE_ID", "INT NOT NULL"),
            Map.entry("SOURCE_SERIES_ID", "INT NOT NULL"),
            Map.entry("ACTIVE", "TINYINT NOT NULL DEFAULT 1"),
            Map.entry("BOUND_BY", "INT NOT NULL"),
            Map.entry("BOUND_AT", CREATED_AT_DEFINITION),
            Map.entry("UPDATED_AT", UPDATED_AT_DEFINITION));

    private static final Map<String, String> REVISION_DEFINITIONS = Map.ofEntries(
            Map.entry("ID", "BIGINT NOT NULL AUTO_INCREMENT"),
            Map.entry("BINDING_ID", "BIGINT NOT NULL"),
            Map.entry("REVISION_NO", "INT NOT NULL"),
            Map.entry("SOURCE_ID", "INT NOT NULL"),
            Map.entry("SOURCE_SERIES_ID", "INT NOT NULL"),
            Map.entry("EVIDENCE_ENTRY_ID", "BIGINT NOT NULL"),
            Map.entry("ADOPTED_BY", "INT NOT NULL"),
            Map.entry("ADOPTED_AT", CREATED_AT_DEFINITION));

    @Test
    void migrationOnlyCreatesTheFourTablesInTheApprovedOrder() throws IOException {
        String migration = migration();
        String script = stripLineComments(migration);

        for (String statement : statementsOf(migration)) {
            // 顶层语句只允许建表：因此 ALTER / DROP / UPDATE / INSERT / DELETE / TRUNCATE / REPLACE
            // 都不可能出现在本文件里（文件里也没有只读 SELECT）。
            assertTrue(normalize(statement).startsWith("CREATE TABLE"),
                    "迁移的顶层语句只能是 CREATE TABLE，发现其它语句：" + normalize(statement));
        }
        assertEquals(TABLES, createdTableNames(script),
                "本片只建这四张表，且顺序固定：snapshot → entry → binding → revision（外键目标先存在）");

        // 只扫代码：列注释里出现 "不得 UPDATE" 这类说明文字不算写操作；
        // ON UPDATE CURRENT_TIMESTAMP(3) 是列属性，也不是数据写操作，摘掉后不允许再有写关键字。
        String code = withoutQuotedLiterals(script).replaceAll(
                "ON\\s+UPDATE\\s+CURRENT_TIMESTAMP\\s*\\(\\s*3\\s*\\)", "");
        assertFalse(Pattern.compile("\\b(UPDATE|INSERT|DELETE|DROP|ALTER|TRUNCATE|REPLACE)\\b")
                        .matcher(code).find(),
                "迁移不得包含任何数据写操作或 DDL 变更语句（只允许 CREATE TABLE IF NOT EXISTS），"
                        + "也不得建 operation / audit / probe / confirmation 表");
        assertFalse(script.contains("FOREIGN_KEY_CHECKS"),
                "不得关闭外键检查：复合外键必须按默认方式真实建立");
    }

    @Test
    void everyCreateStatementIsStrictlyIdempotent() throws IOException {
        for (String table : TABLES) {
            // 严格匹配 CREATE TABLE IF NOT EXISTS：不用"可选 IF NOT EXISTS"的宽松匹配器当保证。
            assertStrictIfNotExists(createTableText(migration(), table), table);
        }
    }

    @Test
    void strictIdempotencyGuardRejectsAFixtureWithoutTheClause() {
        assertThrows(AssertionError.class,
                () -> assertStrictIfNotExists("CREATE TABLE sample (id BIGINT NOT NULL);", "fixture"),
                "缺少 IF NOT EXISTS 的建表语句必须被拒绝：宽松匹配器会把缺省当成已声明");
    }

    @Test
    void snapshotColumnDefinitionsPinTypeNullabilityAndDefaults() throws IOException {
        assertEquals(SNAPSHOT_DEFINITIONS, parse(migration(), SNAPSHOT).columns(),
                "external_capture_snapshot 每列的类型 / NULL / 默认值必须固定（列注释不参与比较）");
    }

    @Test
    void entryColumnDefinitionsPinTypeNullabilityAndDefaults() throws IOException {
        assertEquals(ENTRY_DEFINITIONS, parse(migration(), ENTRY).columns(),
                "external_capture_entry 每列的类型 / NULL / 默认值必须固定（列注释不参与比较）");
    }

    @Test
    void bindingColumnDefinitionsPinTypeNullabilityAndDefaults() throws IOException {
        assertEquals(BINDING_DEFINITIONS, parse(migration(), BINDING).columns(),
                "external_episode_binding 每列的类型 / NULL / 默认值必须固定（列注释不参与比较）");
    }

    @Test
    void revisionColumnDefinitionsPinTypeNullabilityAndDefaults() throws IOException {
        assertEquals(REVISION_DEFINITIONS, parse(migration(), REVISION).columns(),
                "external_binding_revision 每列的类型 / NULL / 默认值必须固定（列注释不参与比较）");
    }

    @Test
    void evidenceTablesKeepTheirScopeCandidateKeysInColumnOrder() throws IOException {
        TableDef snapshot = parse(migration(), SNAPSHOT);
        assertEquals(2, snapshot.indexes().size(),
                "snapshot 只允许 PRIMARY + (source_id, source_series_id, id) 唯一键，实际："
                        + snapshot.indexes());
        IndexDef snapshotScope = index(snapshot, "UK_EXTERNAL_CAPTURE_SNAPSHOT_SCOPE");
        assertEquals("UNIQUE", snapshotScope.kind(),
                "(source_id, source_series_id, id) 必须是 UNIQUE 才能被 entry 的复合外键引用");
        assertEquals(List.of("SOURCE_ID", "SOURCE_SERIES_ID", "ID"), snapshotScope.columns(),
                "snapshot 归属唯一键的内部列序固定；id 必须在最后，才能被 (source_id, source_series_id, ?)"
                        + " 复合外键整体引用");

        TableDef entry = parse(migration(), ENTRY);
        assertEquals(3, entry.indexes().size(),
                "entry 只允许 PRIMARY + 位置唯一键 + 归属唯一键，实际：" + entry.indexes());
        IndexDef position = index(entry, "UK_EXTERNAL_CAPTURE_ENTRY_POSITION");
        assertEquals("UNIQUE", position.kind(),
                "同一快照内 (线路位置, 条目位置) 必须唯一：位置只是本次证据定位，不跨快照去重");
        assertEquals(List.of("SNAPSHOT_ID", "LINE_ORDINAL", "ENTRY_ORDINAL"), position.columns(),
                "位置唯一键的内部列序固定：" + position.columns());

        IndexDef entryScope = index(entry, "UK_EXTERNAL_CAPTURE_ENTRY_SCOPE");
        assertEquals("UNIQUE", entryScope.kind(),
                "(id, source_id, source_series_id) 必须是 UNIQUE 才能被 revision 的复合外键引用");
        assertEquals(List.of("ID", "SOURCE_ID", "SOURCE_SERIES_ID"), entryScope.columns(),
                "entry 归属唯一键的内部列序固定：" + entryScope.columns());
    }

    @Test
    void bindingAndRevisionKeepStableIdentityAndAppendOnlyKeys() throws IOException {
        TableDef binding = parse(migration(), BINDING);
        assertEquals(4, binding.indexes().size(),
                "binding 只允许 PRIMARY + uid 唯一键 + 归属唯一键 + episode 普通索引，实际："
                        + binding.indexes());
        IndexDef uid = index(binding, "UK_EXTERNAL_EPISODE_BINDING_UID");
        assertEquals("UNIQUE", uid.kind(), "binding_uid 是稳定 id，必须唯一");
        assertEquals(List.of("BINDING_UID"), uid.columns(), "binding_uid 唯一键只含该列");
        assertEquals(List.of("ID", "SOURCE_ID", "SOURCE_SERIES_ID"),
                index(binding, "UK_EXTERNAL_EPISODE_BINDING_SCOPE").columns(),
                "binding 归属唯一键的内部列序固定，供 revision 复合外键引用");
        IndexDef episode = index(binding, "IDX_EXTERNAL_EPISODE_BINDING_EPISODE");
        assertEquals("KEY", episode.kind(), "(local_episode_id, active, id) 是普通索引，不是唯一键");
        assertEquals(List.of("LOCAL_EPISODE_ID", "ACTIVE", "ID"), episode.columns(),
                "同一 local episode 允许多条 binding，所以这里不能是唯一键");

        TableDef revision = parse(migration(), REVISION);
        assertEquals(3, revision.indexes().size(),
                "revision 只允许 PRIMARY + 版本号唯一键 + 证据唯一键，实际：" + revision.indexes());
        assertEquals(List.of("BINDING_ID", "REVISION_NO"),
                index(revision, "UK_EXTERNAL_BINDING_REVISION_NO").columns(),
                "版本号唯一键必须是 (binding_id, revision_no)，内部列序固定；"
                        + "它只是最后防线，DB 不保证版本连续");
        IndexDef evidence = index(revision, "UK_EXTERNAL_BINDING_REVISION_EVIDENCE");
        assertEquals("UNIQUE", evidence.kind(),
                "evidence_entry_id 必须全局唯一：这就是 append-only 行对证据的占用");
        assertEquals(List.of("EVIDENCE_ENTRY_ID"), evidence.columns(),
                "证据占用只能由 revision 行承担，不得把 UNIQUE(evidence_entry_id) 挂回可变的 binding 行");
    }

    @Test
    void foreignKeysPointAtExplicitCandidateKeysWithoutCascade() throws IOException {
        String migration = migration();

        assertEquals(List.of(
                        "FK_EXTERNAL_CAPTURE_SNAPSHOT_BY|[CAPTURED_BY]->USERS[ID]",
                        "FK_EXTERNAL_CAPTURE_SNAPSHOT_SERIES|[SOURCE_ID, SOURCE_SERIES_ID]"
                                + "->VIDEO_SOURCE_SERIES[SOURCE_ID, ID]",
                        "FK_EXTERNAL_CAPTURE_SNAPSHOT_WORK|[SOURCE_ID, WORK_IDENTITY]"
                                + "->VIDEO_SOURCE_SERIES[SOURCE_ID, UPSTREAM_SERIES_ID]"),
                describe(parse(migration, SNAPSHOT).foreignKeys()),
                "snapshot 的外键集合固定：来源作品归属、workId 目录归属、抓取人；"
                        + "两个外键只保证行存在，不证明 work_identity 与 source_series_id 指向同一行");

        assertEquals(List.of(
                        "FK_EXTERNAL_CAPTURE_ENTRY_SNAPSHOT|[SOURCE_ID, SOURCE_SERIES_ID, SNAPSHOT_ID]"
                                + "->EXTERNAL_CAPTURE_SNAPSHOT[SOURCE_ID, SOURCE_SERIES_ID, ID]"),
                describe(parse(migration, ENTRY).foreignKeys()),
                "entry 只有一个复合外键：它同时保证 entry 与 snapshot 同来源、同作品");

        assertEquals(List.of(
                        "FK_EXTERNAL_EPISODE_BINDING_BY|[BOUND_BY]->USERS[ID]",
                        "FK_EXTERNAL_EPISODE_BINDING_EPISODE|[LOCAL_EPISODE_ID]->LOCAL_VIDEO_EPISODE[ID]",
                        "FK_EXTERNAL_EPISODE_BINDING_SERIES|[SOURCE_ID, SOURCE_SERIES_ID]"
                                + "->VIDEO_SOURCE_SERIES[SOURCE_ID, ID]"),
                describe(parse(migration, BINDING).foreignKeys()),
                "binding 的外键集合固定：本地剧集、来源作品归属、操作人");

        assertEquals(List.of(
                        "FK_EXTERNAL_BINDING_REVISION_BINDING|[BINDING_ID, SOURCE_ID, SOURCE_SERIES_ID]"
                                + "->EXTERNAL_EPISODE_BINDING[ID, SOURCE_ID, SOURCE_SERIES_ID]",
                        "FK_EXTERNAL_BINDING_REVISION_BY|[ADOPTED_BY]->USERS[ID]",
                        "FK_EXTERNAL_BINDING_REVISION_ENTRY|[EVIDENCE_ENTRY_ID, SOURCE_ID, SOURCE_SERIES_ID]"
                                + "->EXTERNAL_CAPTURE_ENTRY[ID, SOURCE_ID, SOURCE_SERIES_ID]"),
                describe(parse(migration, REVISION).foreignKeys()),
                "revision 的两个复合外键都携带同一对来源列，所以跨来源串指（A 来源的 binding 指向"
                        + " B 来源的条目）在 DB 层不可能");

        for (String table : TABLES) {
            List<String> foreignKeyItems = foreignKeyItems(migration, table);
            assertEquals(parse(migration, table).foreignKeys().size(), foreignKeyItems.size(),
                    table + " 的外键数量与上面的断言不一致：" + foreignKeyItems);
            for (String item : foreignKeyItems) {
                assertFalse(item.contains("ON DELETE"),
                        table + " 不得使用 ON DELETE（级联 / 置空都不允许）；撤销只写 active=0：" + item);
                assertFalse(item.contains("ON UPDATE"),
                        table + " 的外键不得使用 ON UPDATE 级联：" + item);
            }
        }

        // 每个外键的引用列必须是父表**显式声明**的候选键前缀。子表上由 MySQL 自动补的外键索引
        // 不在本断言范围内，本文件也不宣称实际库里只有显式索引。
        Map<String, TableDef> parents = parents();
        for (String table : TABLES) {
            for (ForeignKey foreignKey : parse(migration, table).foreignKeys()) {
                TableDef parent = parents.get(foreignKey.refTable());
                assertReferencedColumnsAreACandidateKey(parent, foreignKey);
            }
        }
    }

    @Test
    void foreignKeyColumnTypesAndCollationsPairWithTheParentColumns() throws IOException {
        Map<String, TableDef> parents = parents();
        String migration = migration();

        for (String table : TABLES) {
            TableDef def = parse(migration, table);
            for (ForeignKey foreignKey : def.foreignKeys()) {
                TableDef parent = parents.get(foreignKey.refTable());
                assertNotNull(parent, "缺少父表 " + foreignKey.refTable() + " 的定义");
                for (int position = 0; position < foreignKey.columns().size(); position++) {
                    String leafColumn = foreignKey.columns().get(position);
                    String parentColumn = foreignKey.refColumns().get(position);
                    String leaf = def.columns().get(leafColumn);
                    String target = parent.columns().get(parentColumn);
                    String label = table + "." + leafColumn + " -> " + parent.name() + "." + parentColumn;

                    assertNotNull(leaf, "找不到外键列定义：" + label);
                    assertNotNull(target, "找不到父表外键列定义：" + label);
                    assertEquals(firstToken(target), firstToken(leaf),
                            label + " 的类型必须与被引用列一致（引用 users / video_source* / local_* 的列"
                                    + "保持既有类型，新表主键用 BIGINT）：" + leaf + " vs " + target);
                    if (isTextual(leaf)) {
                        assertEquals(target, leaf,
                                label + " 是字符串外键列：字符集 / 排序规则 / 长度必须与被引用列完全一致"
                                        + "（utf8mb4_bin 大小写敏感，但仍是 PAD SPACE）");
                    }
                }
            }
        }

        // 显式列出关键的类型配对，避免上面的通用检查在表集合变化时静默覆盖不全。
        TableDef snapshot = parse(migration, SNAPSHOT);
        TableDef entry = parse(migration, ENTRY);
        assertEquals("INT NOT NULL", snapshot.columns().get("SOURCE_ID"),
                "source_id 引用 video_source_series.source_id（INT），不得写成 BIGINT");
        assertEquals("BIGINT NOT NULL", entry.columns().get("SNAPSHOT_ID"),
                "snapshot_id 引用本片新建的 BIGINT 主键");
        assertEquals("BIGINT NOT NULL", parse(migration, REVISION).columns().get("EVIDENCE_ENTRY_ID"),
                "evidence_entry_id 引用本片新建的 BIGINT entry 主键");
        assertEquals("VARCHAR(191) " + BIN_NOT_NULL, snapshot.columns().get("WORK_IDENTITY"),
                "work_identity 必须与 video_source_series.upstream_series_id 的类型和 bin 排序规则一致，"
                        + "否则复合外键无法建立");
        assertEquals(parents.get("VIDEO_SOURCE_SERIES").columns().get("UPSTREAM_SERIES_ID"),
                snapshot.columns().get("WORK_IDENTITY"),
                "work_identity 的定义必须逐字等同被引用的 upstream_series_id（含长度与 collation）");
    }

    @Test
    void checksPinOrdinalCountsRangesAndTheActiveFlag() throws IOException {
        String migration = migration();

        assertEquals(List.of(
                        compact("CONSTRAINT ck_external_capture_snapshot_entry_count"
                                + " CHECK (entry_count BETWEEN 1 AND 16000)"),
                        compact("CONSTRAINT ck_external_capture_snapshot_line_count"
                                + " CHECK (line_count BETWEEN 1 AND 16)")),
                compactChecks(parse(migration, SNAPSHOT)),
                "snapshot 的范围：线路 1..16、条目 1..16000（解析器上限），比较会去掉空白");

        assertEquals(List.of(
                        compact("CONSTRAINT ck_external_capture_entry_entry_ordinal"
                                + " CHECK (entry_ordinal BETWEEN 1 AND 1000)"),
                        compact("CONSTRAINT ck_external_capture_entry_line_ordinal"
                                + " CHECK (line_ordinal BETWEEN 1 AND 16)")),
                compactChecks(parse(migration, ENTRY)),
                "entry 的范围：线路位置 1..16、条目位置 1..1000；位置不是跨快照身份");

        assertEquals(List.of(compact("CONSTRAINT ck_external_episode_binding_active"
                        + " CHECK (active IN (0, 1))")),
                compactChecks(parse(migration, BINDING)),
                "active 只允许 0/1；撤销是写 0，不删行");

        assertEquals(List.of(compact("CONSTRAINT ck_external_binding_revision_no"
                        + " CHECK (revision_no >= 1)")),
                compactChecks(parse(migration, REVISION)),
                "revision_no 从 1 起；CHECK 不保证版本连续，也不保证只由服务写入");
    }

    @Test
    void tablesCarryNoOperationProbeConfirmationVidOrSourceMutationColumns() throws IOException {
        Map<String, TableDef> parents = parents();
        String migration = migration();

        for (String table : TABLES) {
            TableDef def = parse(migration, table);

            for (String deferred : List.of("VID", "OPERATION_ID", "DURATION_SECONDS",
                    "IMPORT_UID", "DELETED_AT", "ENABLED", "SYNC_STATE", "BASE_URL",
                    "ADAPTER_TYPE", "CREDENTIAL_REF")) {
                assertFalse(def.columns().containsKey(deferred),
                        table + " 不得提前建 " + deferred + " 列（vid 写入是 M5，探测 / 确认是 M4-B，"
                                + "来源行字段不得在这里复制或改写）");
            }
            for (String column : def.columns().keySet()) {
                for (String forbiddenPrefix : List.of(
                        "PROBE", "CONFIRMATION", "APPROVED", "AUDIT", "OPERATION", "PENDING")) {
                    assertFalse(column.startsWith(forbiddenPrefix),
                            table + " 不得提前建 " + forbiddenPrefix + "* 列，实际：" + column);
                }
            }
            for (ForeignKey foreignKey : def.foreignKeys()) {
                assertTrue(parents.containsKey(foreignKey.refTable()),
                        table + " 的外键目标必须是本片或既有目录表，实际：" + foreignKey.refTable());
                assertFalse(List.of("VIDEO", "VIDEO_STATUS", "VIDEO_SOURCE")
                                .contains(foreignKey.refTable()),
                        table + " 不得引用 video / video_status（M5 才创建），也不得引用 video_source"
                                + "（来源行是运维动作，本片不自动补行、不改写）：" + foreignKey);
            }
        }

        TableDef binding = parse(migration, BINDING);
        assertTrue(binding.columns().containsKey("ACTIVE"),
                "binding 必须有 active 列：撤销只置 0，不删行、不释放证据占用");
    }

    @Test
    void addressAndDigestWidthsAreServerOnlyAndNoAddressIndexIsDeclared() throws IOException {
        TableDef entry = parse(migration(), ENTRY);

        assertEquals("VARCHAR(2048) NOT NULL", entry.columns().get("ADDRESS"),
                "address 宽度对齐 MAX_PLAY_ADDRESS_LENGTH=2048，单位是字符不是字节；"
                        + "原文可能内含签名 token，属 server-only");
        assertEquals("VARCHAR(255) NULL", entry.columns().get("LABEL"),
                "label 宽度对齐 MAX_EPISODE_LABEL_LENGTH=255，是不可信展示文本");
        assertEquals("VARCHAR(191) " + BIN_NOT_NULL.replace("NOT NULL", "NULL"),
                entry.columns().get("LINE_KEY"),
                "line_key 宽度对齐 MAX_LINE_KEY_LENGTH=191，显式 bin（线路键大小写敏感）");
        assertEquals("CHAR(64) " + BIN_NOT_NULL, entry.columns().get("ADDRESS_HASH"),
                "address_hash 是 CHAR(64)：CHAR 与 PAD SPACE 不证明格式正确，也不证明摘要对应原地址");

        for (IndexDef candidate : entry.indexes()) {
            assertFalse(candidate.columns().contains("ADDRESS")
                            || candidate.columns().contains("ADDRESS_HASH"),
                    "本片不建 address 索引，也不建 address_hash 索引（需要时另议），实际：" + candidate);
        }
    }

    @Test
    void migrationHeaderStatesPrerequisitesMysql8016NoWriteAndNoDeployment() throws IOException {
        String migration = migration();

        assertTrue(migration.contains("8.0.16"),
                "迁移必须写明 CHECK 需要 MySQL 8.0.16+：更低版本会解析后静默忽略，"
                        + "不能把 CHECK 当作唯一防线（应用层仍需校验）");
        assertTrue(migration.contains("不等于结构校验"),
                "迁移必须写明 IF NOT EXISTS 不等于结构校验：同名旧表会被整段跳过");
        assertTrue(migration.contains("未在任何 MySQL 实例上执行"),
                "迁移必须写明本片未执行：文件与静态测试不等于已在某个库建表");
        assertTrue(migration.contains("users") && migration.contains("video_source_series")
                        && migration.contains("local_video_episode"),
                "迁移头必须写明前置：users、E1-A 两张表、M4-A1 两张本地表必须先存在");
        assertTrue(migration.contains("无写接口"),
                "迁移头必须写明没有写接口 / 抓取任务 / 探测 / 确认");
        assertTrue(migration.contains("保留期未决定"),
                "迁移头必须写明保留期未决策：不建清理任务，也不发明天数");
        assertTrue(migration.contains("不声称"),
                "迁移头必须如实说明 address 可能含签名 token：不声称无明文 token、不声称已加密");
        assertTrue(migration.contains("自动补充必要索引"),
                "迁移头必须说明 MySQL 会为子表外键自动补索引，避免把显式 DDL 当成实际索引全集");
        assertTrue(migration.contains("不得部署"),
                "迁移头必须写明：保留期 / 访问控制 / 静态加密责任明确前不得接真实写入、不得部署");
        assertFalse(migration.contains("已执行"),
                "迁移不得声称已在某个数据库执行过");
    }

    @Test
    void freshSchemaMirrorsTheMigrationTablesInDependencyOrderAllowingLaterSlices() throws IOException {
        String migration = migration();
        String schema = schema();

        String strippedSchema = stripLineComments(schema);
        List<String> allCreated = createdTableNames(strippedSchema);
        int start = allCreated.indexOf(SNAPSHOT);
        assertTrue(start > allCreated.indexOf("local_video_episode"));
        assertTrue(start > allCreated.indexOf("video_source_series"));
        assertTrue(start >= 0 && start + TABLES.size() <= allCreated.size());
        assertEquals(TABLES, allCreated.subList(start, start + TABLES.size()),
                "A2四表仍须连续、按依赖顺序且在父表之后；后续S2等切片可以继续追加新表");
        for (String table : TABLES) {
            assertEquals(1, allCreated.stream().filter(table::equals).count(),
                    "database/feibijiubi.sql 里 " + table + " 只能出现一次");
        }

        // 不再把“历史切片必须永远位于文件末尾”当作契约。下方仍逐表比较全部列、索引、外键和CHECK。

        for (String table : TABLES) {
            TableDef declared = parse(migration, table);
            TableDef mirrored = parse(schema, table);

            assertEquals(declared.columns(), mirrored.columns(),
                    "database/feibijiubi.sql 的 " + table + " 列定义必须与迁移一致（注释不参与比较）");
            assertEquals(declared.indexes(), mirrored.indexes(),
                    "database/feibijiubi.sql 的 " + table + " 索引必须与迁移一致（含内部列顺序）");
            assertEquals(describe(declared.foreignKeys()), describe(mirrored.foreignKeys()),
                    "database/feibijiubi.sql 的 " + table + " 外键必须与迁移一致");
            assertEquals(declared.checks(), mirrored.checks(),
                    "database/feibijiubi.sql 的 " + table + " CHECK 必须与迁移一致");
            assertEquals(TABLE_OPTIONS, declared.tableOptions(), "表选项须符合批准值");
            assertEquals(declared.tableOptions(), mirrored.tableOptions(),
                    "database/feibijiubi.sql 的 " + table + " 表选项必须与迁移一致");
            assertEquals(normalizedBodyItems(migration, table), normalizedBodyItems(schema, table),
                    "镜像必须是逐条表体项相同（列 / 键 / 外键 / CHECK 一条不缺），"
                            + "不能只抽几个断言就当作一致");
        }
    }

    @Test
    void parserHandlesForeignKeyWithoutConstraintPrefixAndBacktickedColumns() {
        // 既有表里存在不带 CONSTRAINT 名的 FOREIGN KEY 与反引号列，读父表列定义时不能因此抛错。
        TableDef sample = parse("""
                CREATE TABLE sample (
                    `id` BIGINT NOT NULL,
                    `parent_id` INT NOT NULL,
                    PRIMARY KEY (`id`),
                    FOREIGN KEY (`parent_id`) REFERENCES `parent` (`id`)
                ) ENGINE=InnoDB;
                """, "sample");

        assertEquals(List.of("ID", "PARENT_ID"), sorted(sample.columns().keySet()),
                "反引号列名必须被剥掉，否则父表列定义取不到：" + sample.columns().keySet());
        assertEquals(1, sample.foreignKeys().size(),
                "不带 CONSTRAINT 名的 FOREIGN KEY 必须被解析而不是抛断言错误：" + sample.foreignKeys());
        assertEquals(List.of("PARENT_ID"), sample.foreignKeys().get(0).columns(),
                "反引号列清单必须被剥掉：" + sample.foreignKeys().get(0));
        assertEquals("PARENT", sample.foreignKeys().get(0).refTable(),
                "反引号表名必须被剥掉：" + sample.foreignKeys().get(0));
        assertEquals(List.of("ID"), sample.foreignKeys().get(0).refColumns(),
                "反引号引用列必须被剥掉：" + sample.foreignKeys().get(0));
    }

    // ------------------------------------------------------------------
    // 小工具：去注释 / 切语句 / 取一张表的 CREATE TABLE 文本 / 解析表结构。
    // 形状与同目录 E1A / M4-A1 的 SchemaTests 一致（那些 helper 是 private，无法直接复用），
    // 只服务本片四张表与其父表，刻意不做通用 SQL 解析器。
    // ------------------------------------------------------------------

    private static String migration() throws IOException {
        assertTrue(Files.isRegularFile(MIGRATION), "缺少迁移文件：" + MIGRATION.toAbsolutePath());
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    private static String schema() throws IOException {
        assertTrue(Files.isRegularFile(SCHEMA), "缺少初始化脚本：" + SCHEMA.toAbsolutePath());
        return Files.readString(SCHEMA, StandardCharsets.UTF_8);
    }

    /** 本片四表外键指向的父表：既有目录表取初始化脚本，本片新表取迁移。 */
    private static Map<String, TableDef> parents() throws IOException {
        Map<String, TableDef> parents = new LinkedHashMap<>();
        parents.put("USERS", parse(schema(), "users"));
        parents.put("VIDEO_SOURCE_SERIES", parse(schema(), "video_source_series"));
        parents.put("LOCAL_VIDEO_EPISODE", parse(schema(), "local_video_episode"));
        parents.put("EXTERNAL_CAPTURE_SNAPSHOT", parse(migration(), SNAPSHOT));
        parents.put("EXTERNAL_CAPTURE_ENTRY", parse(migration(), ENTRY));
        parents.put("EXTERNAL_EPISODE_BINDING", parse(migration(), BINDING));
        return parents;
    }

    /** 去掉整行 {@code --} 注释：本仓库的 SQL 注释都独占整行，没有行尾注释。 */
    private static String stripLineComments(String sql) {
        StringBuilder stripped = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (line.stripLeading().startsWith("--")) {
                continue;
            }
            stripped.append(line).append('\n');
        }
        return stripped.toString();
    }

    /** 按顶层 {@code ;} 切语句（跳过单引号字符串内部的 {@code ;}）。 */
    private static List<String> statementsOf(String sql) {
        String script = stripLineComments(sql);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            }
            if (c == ';' && !inQuote) {
                addIfMeaningful(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfMeaningful(statements, current);
        return statements;
    }

    private static void addIfMeaningful(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }

    /** 脚本里所有 {@code CREATE TABLE} 的表名（统一小写、按出现顺序），用来证明"只建了这些表"。 */
    private static List<String> createdTableNames(String commentFreeScript) {
        Matcher matcher = Pattern.compile(
                        "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?",
                        Pattern.CASE_INSENSITIVE)
                .matcher(commentFreeScript);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }

    /** 表体里所有外键项（规范化文本），用来单独检查级联子句。 */
    private static List<String> foreignKeyItems(String sql, String table) {
        List<String> items = new ArrayList<>();
        for (String item : normalizedBodyItems(sql, table)) {
            if (item.startsWith("CONSTRAINT") && item.contains("FOREIGN KEY")) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    /** 去掉单引号字符串字面量（列注释、表注释），只留 SQL 代码。 */
    private static String withoutQuotedLiterals(String sql) {
        StringBuilder code = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                code.append(' ');
                continue;
            }
            code.append(inQuote ? ' ' : c);
        }
        return code.toString();
    }

    private static Pattern createTablePattern(String table) {
        return Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?" + table + "`?\\s*\\(",
                Pattern.CASE_INSENSITIVE);
    }

    private static Matcher createTableMatcher(String sql, String table) {
        return createTablePattern(table).matcher(stripLineComments(sql));
    }

    /** 取 {@code CREATE TABLE [IF NOT EXISTS] <table> (...)} 这一条语句的原文。 */
    private static String createTableText(String sql, String table) {
        String script = stripLineComments(sql);
        Matcher start = createTablePattern(table).matcher(script);
        assertTrue(start.find(), "找不到 " + table + " 的 CREATE TABLE 语句");

        int close = bodyCloseIndex(script, start.end() - 1);
        int end = script.indexOf(';', close);
        return script.substring(start.start(), end < 0 ? script.length() : end);
    }

    /** 必须是严格的 {@code CREATE TABLE IF NOT EXISTS}：不靠"可选 IF NOT EXISTS"匹配器当作已声明。 */
    private static void assertStrictIfNotExists(String createStatement, String label) {
        assertTrue(Pattern.compile("^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`?\\w+`?\\s*\\(")
                        .matcher(createStatement).find(),
                label + " 必须严格写成 CREATE TABLE IF NOT EXISTS ...，实际开头："
                        + createStatement.substring(0, Math.min(60, createStatement.length())));
    }

    /** 表体右括号的下标（跳过单引号字符串与嵌套括号）。 */
    private static int bodyCloseIndex(String statement, int openParen) {
        assertTrue(openParen > 0, "CREATE TABLE 缺少表体：" + statement);
        boolean inQuote = false;
        int depth = 0;
        for (int i = openParen; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && c == '(') {
                depth++;
            } else if (!inQuote && c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new AssertionError("表体括号不闭合：" + statement);
    }

    /** 取 {@code CREATE TABLE} 语句里第一个括号组的内部（表体）；表注释里的括号不影响结果。 */
    private static String tableBody(String statement) {
        int open = statement.indexOf('(');
        return statement.substring(open + 1, bodyCloseIndex(statement, open));
    }

    /** 表体右括号之后的表选项，例如 {@code ENGINE=INNODB DEFAULT CHARSET=...}（表注释已去掉）。 */
    private static String tableOptionsOf(String statement) {
        int open = statement.indexOf('(');
        return withoutCommentAttribute(
                normalize(statement.substring(bodyCloseIndex(statement, open) + 1)));
    }

    /** 表体的逐条项（列 / 键 / 外键 / CHECK），规范化后按声明顺序返回，用于镜像的逐条比对。 */
    private static List<String> normalizedBodyItems(String sql, String table) {
        List<String> items = new ArrayList<>();
        for (String item : splitTopLevel(tableBody(createTableText(sql, table)))) {
            items.add(withoutCommentAttribute(normalize(item)));
        }
        return List.copyOf(items);
    }

    private record IndexDef(String kind, String name, List<String> columns) {
    }

    private record ForeignKey(String name, List<String> columns, String refTable,
                              List<String> refColumns) {
    }

    private record TableDef(String name, Map<String, String> columns, List<IndexDef> indexes,
                            List<ForeignKey> foreignKeys, List<String> checks, String tableOptions) {
    }

    private static TableDef parse(String sql, String table) {
        String statement = createTableText(sql, table);
        String body = tableBody(statement);

        Map<String, String> columns = new LinkedHashMap<>();
        List<IndexDef> indexes = new ArrayList<>();
        List<ForeignKey> foreignKeys = new ArrayList<>();
        List<String> checks = new ArrayList<>();

        for (String item : splitTopLevel(body)) {
            String normalized = normalize(item);
            if (normalized.startsWith("CONSTRAINT") && normalized.contains("CHECK")) {
                checks.add(normalized);
            } else if (normalized.startsWith("FOREIGN KEY") || normalized.startsWith("CONSTRAINT")) {
                foreignKeys.add(parseForeignKey(normalized));
            } else if (normalized.startsWith("PRIMARY KEY")) {
                indexes.add(new IndexDef("PRIMARY", "PRIMARY", parenGroup(item, 0)));
            } else if (normalized.startsWith("UNIQUE KEY") || normalized.startsWith("KEY")
                    || normalized.startsWith("INDEX") || normalized.startsWith("UNIQUE INDEX")) {
                indexes.add(parseIndex(item));
            } else {
                // 反引号列名（既有表）必须先量出原始长度，否则会切错列定义起点。
                String rawName = normalized.substring(0, normalized.indexOf(' ')).trim();
                // 列注释（COMMENT '...'）刻意不进入列定义：注释可以自由改写，不是结构契约。
                columns.put(rawName.replace("`", ""),
                        withoutCommentAttribute(normalized.substring(rawName.length()).trim()));
            }
        }
        return new TableDef(table, Map.copyOf(columns), List.copyOf(indexes),
                List.copyOf(foreignKeys), List.copyOf(checks), tableOptionsOf(statement));
    }

    private static IndexDef parseIndex(String item) {
        String normalized = normalize(item);
        Matcher unique = Pattern.compile("^UNIQUE\\s+(?:KEY|INDEX)\\s+`?(\\S+?)`?\\s*\\(")
                .matcher(normalized);
        if (unique.find()) {
            return new IndexDef("UNIQUE", unique.group(1), parenGroup(item, 0));
        }
        Matcher plain = Pattern.compile("^(?:KEY|INDEX)\\s+`?(\\S+?)`?\\s*\\(").matcher(normalized);
        if (plain.find()) {
            return new IndexDef("KEY", plain.group(1), parenGroup(item, 0));
        }
        throw new AssertionError("无法解析索引定义：" + normalized);
    }

    /** 兼容两种写法：带 {@code CONSTRAINT <name>} 的，以及既有表里直接写 {@code FOREIGN KEY (...)} 的。 */
    private static ForeignKey parseForeignKey(String normalized) {
        Matcher matcher = Pattern.compile(
                        "^(?:CONSTRAINT\\s+`?(\\w+)`?\\s+)?FOREIGN\\s+KEY\\s*\\(([^)]*)\\)"
                                + "\\s+REFERENCES\\s+`?(\\w+)`?\\s*\\(([^)]*)\\)")
                .matcher(normalized);
        assertTrue(matcher.find(), "无法解析外键定义：" + normalized);
        String name = matcher.group(1) == null ? "" : matcher.group(1);
        return new ForeignKey(name, columnList(matcher.group(2)),
                matcher.group(3).trim().toUpperCase(Locale.ROOT), columnList(matcher.group(4)));
    }

    /** 外键的可比较描述（按名称排序，声明顺序不在契约内）。 */
    private static List<String> describe(List<ForeignKey> foreignKeys) {
        List<String> described = new ArrayList<>();
        for (ForeignKey foreignKey : foreignKeys) {
            described.add(foreignKey.name() + "|" + foreignKey.columns()
                    + "->" + foreignKey.refTable() + foreignKey.refColumns());
        }
        described.sort(Comparator.naturalOrder());
        return List.copyOf(described);
    }

    /** 引用列必须精确匹配父表显式主键或唯一键；子表隐式索引不在断言范围。 */
    private static void assertReferencedColumnsAreACandidateKey(TableDef parent, ForeignKey foreignKey) {
        assertNotNull(parent, foreignKey.name() + " 的父表未在本测试的父表集合里声明");
        List<String> matching = new ArrayList<>();
        for (IndexDef candidate : parent.indexes()) {
            if ((candidate.kind().equals("PRIMARY") || candidate.kind().equals("UNIQUE"))
                    && candidate.columns().equals(foreignKey.refColumns())) {
                matching.add(candidate.name());
            }
        }
        assertFalse(matching.isEmpty(),
                foreignKey.name() + " 的引用列 " + foreignKey.refColumns() + " 必须是 "
                        + parent.name() + " 显式声明的完整主键或唯一键，实际索引：" + parent.indexes());
    }

    private static List<String> columnList(String columns) {
        List<String> parsed = new ArrayList<>();
        for (String column : columns.split(",")) {
            parsed.add(column.trim().replace("`", "").toUpperCase(Locale.ROOT));
        }
        return List.copyOf(parsed);
    }

    private static IndexDef index(TableDef table, String name) {
        return table.indexes().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        table.name() + " 缺少索引 " + name + "，实际：" + table.indexes()));
    }

    /** 取规范化文本里第 {@code ordinal} 个括号组的内容。 */
    private static List<String> parenGroup(String item, int ordinal) {
        String normalized = normalize(item);
        int seen = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) != '(') {
                continue;
            }
            int close = normalized.indexOf(')', i);
            assertTrue(close > i, "括号不闭合：" + normalized);
            if (seen == ordinal) {
                return columnList(normalized.substring(i + 1, close));
            }
            seen++;
            i = close;
        }
        throw new AssertionError("找不到第 " + ordinal + " 个括号组：" + normalized);
    }

    /** 按顶层逗号切分表体（跳过括号与单引号内部）。 */
    private static List<String> splitTopLevel(String body) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (!inQuote && c == '(') {
                depth++;
            } else if (!inQuote && c == ')') {
                depth--;
            } else if (!inQuote && depth == 0 && c == ',') {
                items.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            items.add(current.toString().trim());
        }
        return items;
    }

    /** 去掉 {@code COMMENT '...'} / {@code COMMENT='...'}：注释可以自由改写，不参与结构比较。 */
    private static String withoutCommentAttribute(String normalized) {
        return normalized.replaceAll("\\s*COMMENT\\s*=?\\s*'(?:[^']|'')*'", "").trim();
    }

    /** 统一关键字大小写并去掉空白；仅用于本测试中不含字符串字面量的约束文本。 */
    private static String compact(String sql) {
        return sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static List<String> compactChecks(TableDef table) {
        List<String> compacted = new ArrayList<>();
        for (String check : table.checks()) {
            compacted.add(compact(check));
        }
        compacted.sort(Comparator.naturalOrder());
        return List.copyOf(compacted);
    }

    private static String firstToken(String definition) {
        return definition.split(" ")[0];
    }

    private static boolean isTextual(String definition) {
        return definition.startsWith("VARCHAR(") || definition.startsWith("CHAR(");
    }

    private static List<String> sorted(Collection<String> names) {
        List<String> copy = new ArrayList<>(names);
        copy.sort(Comparator.naturalOrder());
        return List.copyOf(copy);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}
