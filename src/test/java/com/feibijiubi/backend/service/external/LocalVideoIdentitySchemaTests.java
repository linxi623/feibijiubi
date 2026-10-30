package com.feibijiubi.backend.service.external;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4-A1：本地视频身份两表（{@code local_video_series} / {@code local_video_episode}）的离线结构契约。
 *
 * <p>只读两份 {@code .sql} 文本，不连库、不启动 Spring、不执行任何 SQL。它是一组**静态、有限的文本结构
 * 检查**：证明"脚本里写了什么"，**不**证明某个 MySQL 实例真的建成了这样 —— DDL 是否被接受、{@code CHECK}
 * 是否被强制（需要 MySQL 8.0.16+）、{@code vid} 唯一冲突行为、外键在既有 {@code users} / {@code video}
 * 上的实际约束、以及 {@code utf8mb4_bin} 的 PAD SPACE 比较行为，都未在本轮验证。</p>
 *
 * <p>刻意**不**当成契约的写法：SQL 注释（列注释可以自由改写）、列在表体里的声明顺序、外键的声明顺序。
 * 真正当契约的是：迁移文件的顶层语句只能是 {@code CREATE TABLE IF NOT EXISTS} 且恰好两张本地表、每列的
 * 类型 / NULL / 默认值、单列主键、<b>索引内部的列顺序</b>、外键与其引用列（且无级联、不得关闭外键检查）、
 * {@code CHECK} 表达式（比较时去掉全部空白）、表选项，以及"先建 series 再建 episode"的语句顺序。
 * {@code database/feibijiubi.sql} 的镜像按**逐条表体项**与迁移比对，而不是只挑几个断言通过。</p>
 */
class LocalVideoIdentitySchemaTests {

    private static final Path MIGRATION = Path.of(
            "database/migrations/m4a1-local-video-identity.sql");
    private static final Path SCHEMA = Path.of("database/feibijiubi.sql");

    private static final String SERIES = "local_video_series";
    private static final String EPISODE = "local_video_episode";

    private static final String TABLE_OPTIONS =
            "ENGINE=INNODB DEFAULT CHARSET=UTF8MB4 COLLATE=UTF8MB4_UNICODE_CI";

    private static final String IDENTITY_DEFINITION =
            "CHAR(36) CHARACTER SET UTF8MB4 COLLATE UTF8MB4_BIN NOT NULL";
    private static final String CREATED_AT_DEFINITION =
            "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)";
    private static final String UPDATED_AT_DEFINITION =
            "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)";

    /** 每列的类型 / NULL / 默认值（规范化后、去掉 {@code COMMENT} 的精确文本）；键集合就是列集合契约。 */
    private static final Map<String, String> SERIES_DEFINITIONS = Map.ofEntries(
            Map.entry("ID", "BIGINT NOT NULL AUTO_INCREMENT"),
            Map.entry("LOCAL_IDENTITY", IDENTITY_DEFINITION),
            Map.entry("TITLE", "VARCHAR(200) NOT NULL"),
            Map.entry("CREATED_BY", "INT NOT NULL"),
            Map.entry("CREATED_AT", CREATED_AT_DEFINITION),
            Map.entry("UPDATED_AT", UPDATED_AT_DEFINITION));

    private static final Map<String, String> EPISODE_DEFINITIONS = Map.ofEntries(
            Map.entry("ID", "BIGINT NOT NULL AUTO_INCREMENT"),
            Map.entry("SERIES_ID", "BIGINT NOT NULL"),
            Map.entry("LOCAL_IDENTITY", IDENTITY_DEFINITION),
            Map.entry("DISPLAY_TITLE", "VARCHAR(255) NULL"),
            Map.entry("DURATION_SECONDS", "DOUBLE NULL"),
            Map.entry("VID", "INT NULL"),
            Map.entry("CREATED_BY", "INT NOT NULL"),
            Map.entry("CREATED_AT", CREATED_AT_DEFINITION),
            Map.entry("UPDATED_AT", UPDATED_AT_DEFINITION));

    @Test
    void migrationOnlyCreatesTheTwoLocalTablesAndHasNoDataOrDdlSideEffects() throws IOException {
        String migration = migration();
        String script = stripLineComments(migration);

        for (String statement : statementsOf(migration)) {
            assertTrue(normalize(statement).startsWith("CREATE TABLE"),
                    "迁移的顶层语句只能是 CREATE TABLE，发现其它语句：" + normalize(statement));
        }
        assertEquals(List.of(SERIES, EPISODE), createdTableNames(script),
                "本片只建两张本地表，顺序固定：先 series 再 episode");

        // 只扫代码：列注释里出现 "不得 UPDATE" 这类说明文字不算写操作。
        // ON UPDATE CURRENT_TIMESTAMP(3) 是列属性，也不是数据写操作；摘掉后不允许再有 UPDATE。
        String code = withoutQuotedLiterals(script).replaceAll(
                "ON\\s+UPDATE\\s+CURRENT_TIMESTAMP\\s*\\(\\s*3\\s*\\)", "");
        assertFalse(Pattern.compile("\\b(UPDATE|INSERT|DELETE|DROP|ALTER|TRUNCATE|REPLACE)\\b")
                        .matcher(code).find(),
                "迁移不得包含任何数据写操作或 DDL 变更语句（只允许 CREATE TABLE）");
        assertFalse(script.contains("FOREIGN_KEY_CHECKS"),
                "不得关闭外键检查：外键必须按默认方式真实建立");

        assertIdempotentCreate(migration, SERIES);
        assertIdempotentCreate(migration, EPISODE);
    }

    @Test
    void seriesColumnDefinitionsPinTypeNullabilityAndDefaults() throws IOException {
        assertEquals(SERIES_DEFINITIONS, parse(migration(), SERIES).columns(),
                "local_video_series 每列的类型 / NULL / 默认值必须固定（列注释不参与比较）");
    }

    @Test
    void episodeColumnDefinitionsPinTypeNullabilityAndDefaults() throws IOException {
        assertEquals(EPISODE_DEFINITIONS, parse(migration(), EPISODE).columns(),
                "local_video_episode 每列的类型 / NULL / 默认值必须固定（列注释不参与比较）");
    }

    @Test
    void localIdentityIsUniqueWhileTitleColumnsAreNotPartOfAnyKey() throws IOException {
        for (String table : List.of(SERIES, EPISODE)) {
            TableDef def = parse(migration(), table);

            IndexDef identity = index(def, "UK_LOCAL_VIDEO_"
                    + (SERIES.equals(table) ? "SERIES" : "EPISODE") + "_IDENTITY");
            assertEquals("UNIQUE", identity.kind(), table + " 的本地身份必须唯一");
            assertEquals(List.of("LOCAL_IDENTITY"), identity.columns(),
                    table + " 的唯一键只能含 local_identity（随机身份，不含标题 / 顺序）");

            for (IndexDef candidate : def.indexes()) {
                assertFalse(candidate.columns().contains("TITLE"),
                        table + " 的标题不是身份，不得进入任何索引：" + candidate);
                assertFalse(candidate.columns().contains("DISPLAY_TITLE"),
                        table + " 的展示标题不是身份，不得进入任何索引：" + candidate);
            }
        }
    }

    @Test
    void episodeKeepsTheSeriesKeysetAndASingleNullableVid() throws IOException {
        TableDef episode = parse(migration(), EPISODE);

        assertEquals(4, episode.indexes().size(),
                "episode 只允许 PRIMARY + 身份唯一键 + vid 唯一键 + (series_id, id)，实际："
                        + episode.indexes());

        IndexDef primary = index(episode, "PRIMARY");
        assertEquals(List.of("ID"), primary.columns(), "episode 的主键必须是单列 id");

        IndexDef vid = index(episode, "UK_LOCAL_VIDEO_EPISODE_VID");
        assertEquals("UNIQUE", vid.kind(),
                "vid 必须唯一：只保证一个 vid 不属两个 episode（不阻止同集并发各建一个 video）");
        assertEquals(List.of("VID"), vid.columns(), "vid 唯一键只含 vid");

        IndexDef keyset = index(episode, "IDX_LOCAL_VIDEO_EPISODE_SERIES");
        assertEquals("KEY", keyset.kind(), "(series_id, id) 是普通索引，不是唯一键");
        assertEquals(List.of("SERIES_ID", "ID"), keyset.columns(),
                "(series_id, id) 的内部列顺序固定，供按作品分页扫描");
    }

    @Test
    void foreignKeysPointAtLocalSeriesUsersAndVideoWithoutCascade() throws IOException {
        TableDef series = parse(migration(), SERIES);
        TableDef episode = parse(migration(), EPISODE);

        assertEquals(List.of("FK_LOCAL_VIDEO_SERIES_CREATED_BY|[CREATED_BY]->USERS[ID]"),
                describe(series.foreignKeys()),
                "series 只有一个创建者外键：本片不引用 video_source / video_source_series");
        assertEquals(List.of(
                        "FK_LOCAL_VIDEO_EPISODE_CREATED_BY|[CREATED_BY]->USERS[ID]",
                        "FK_LOCAL_VIDEO_EPISODE_SERIES|[SERIES_ID]->LOCAL_VIDEO_SERIES[ID]",
                        "FK_LOCAL_VIDEO_EPISODE_VID|[VID]->VIDEO[VID]"),
                describe(episode.foreignKeys()),
                "episode 的外键集合固定：本地作品、video.vid（INT）、users.id（INT）");

        for (String table : List.of(SERIES, EPISODE)) {
            List<String> foreignKeyItems = foreignKeyItems(migration(), table);
            assertEquals(SERIES.equals(table) ? 1 : 3, foreignKeyItems.size(),
                    table + " 的外键数量与上一条断言不一致：" + foreignKeyItems);
            for (String item : foreignKeyItems) {
                assertFalse(item.contains("ON DELETE"),
                        table + " 不得使用 ON DELETE（级联 / 置空都不允许）：删除必须走应用层：" + item);
                assertFalse(item.contains("ON UPDATE"),
                        table + " 的外键不得使用 ON UPDATE 级联：" + item);
            }
        }
    }

    @Test
    void durationCheckAllowsOnlyNullOrPositiveAndClaimsNoFiniteness() throws IOException {
        TableDef episode = parse(migration(), EPISODE);

        assertEquals(1, episode.checks().size(),
                "episode 只应有一个 CHECK：" + episode.checks());
        assertEquals(compact("CONSTRAINT ck_local_video_episode_duration"
                        + " CHECK (duration_seconds IS NULL OR duration_seconds > 0)"),
                compact(episode.checks().get(0)),
                "CHECK 仅表达 NULL 或 > 0，不替代应用层 Double.isFinite 校验");
        assertFalse(compact(episode.checks().get(0)).contains("FINITE"),
                "不得把 CHECK 写成（或宣称成）有限值校验：SQL 里没有这种保证");

        assertEquals(0, parse(migration(), SERIES).checks().size(),
                "series 没有枚举 / 范围列，不应有 CHECK：" + parse(migration(), SERIES).checks());
    }

    @Test
    void neitherTableCarriesSourceBindingOwnershipOrImportStateColumns() throws IOException {
        for (String table : List.of(SERIES, EPISODE)) {
            TableDef def = parse(migration(), table);

            for (String deferred : List.of(
                    "SOURCE_ID", "SOURCE_SERIES_ID", "UPSTREAM_SERIES_ID", "UPSTREAM_EPISODE_ID",
                    "IMPORT_UID", "IMPORT_STATE", "BLOCK_REASON", "DELETED_AT")) {
                assertFalse(def.columns().containsKey(deferred),
                        table + " 不得提前建 " + deferred + " 列（来源绑定在 M4-A2，删除/状态更晚）");
            }
            for (String column : def.columns().keySet()) {
                for (String forbiddenPrefix : List.of("APPROVED_", "PROBE_", "COVER_", "PENDING_")) {
                    assertFalse(column.startsWith(forbiddenPrefix),
                            table + " 不得提前建 " + forbiddenPrefix + "* 列，实际：" + column);
                }
            }
        }
    }

    @Test
    void localIdsAreBigIntWhileReferencedIdsStayInt() throws IOException {
        TableDef series = parse(migration(), SERIES);
        TableDef episode = parse(migration(), EPISODE);
        assertEquals("INT NOT NULL AUTO_INCREMENT", parse(schema(), "users").columns().get("ID"));
        assertEquals("INT NOT NULL AUTO_INCREMENT", parse(schema(), "video").columns().get("VID"));

        assertEquals("BIGINT NOT NULL AUTO_INCREMENT", series.columns().get("ID"),
                "本地主键必须是新的 BIGINT，不能沿用既有 INT 主键空间");
        assertEquals("BIGINT NOT NULL AUTO_INCREMENT", episode.columns().get("ID"),
                "本地主键必须是新的 BIGINT");
        assertEquals("BIGINT NOT NULL", episode.columns().get("SERIES_ID"),
                "series_id 引用本片新建的 BIGINT 主键，类型必须一致");
        assertEquals("INT NOT NULL", series.columns().get("CREATED_BY"),
                "created_by 引用既有 users.id（INT），不得写成 BIGINT");
        assertEquals("INT NOT NULL", episode.columns().get("CREATED_BY"),
                "created_by 引用既有 users.id（INT），不得写成 BIGINT");
        assertEquals("INT NULL", episode.columns().get("VID"),
                "vid 引用既有 video.vid（INT）且可空：NULL 表示尚未导入");
    }

    @Test
    void bothTablesUseTheApprovedStorageOptions() throws IOException {
        for (String table : List.of(SERIES, EPISODE)) {
            assertEquals(TABLE_OPTIONS, parse(migration(), table).tableOptions(),
                    "迁移里的 " + table + " 必须 ENGINE=InnoDB 且沿用库默认的 utf8mb4_unicode_ci"
                            + "（只有 local_identity 单独 bin）");
            assertEquals(TABLE_OPTIONS, parse(schema(), table).tableOptions(),
                    "database/feibijiubi.sql 里的 " + table + " 表选项必须与迁移一致");
        }
    }

    @Test
    void freshSchemaMirrorsTheMigrationTablesStructurally() throws IOException {
        String migration = migration();
        String schema = schema();

        List<String> localTables = List.of(SERIES, EPISODE);
        assertEquals(localTables, createdTableNames(stripLineComments(schema)).stream()
                        .filter(name -> name.startsWith("local_video_")).toList(),
                "database/feibijiubi.sql 里必须恰好有这两张本地表，且先 series 再 episode");

        for (String table : localTables) {
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
            assertEquals(declared.tableOptions(), mirrored.tableOptions(),
                    "database/feibijiubi.sql 的 " + table + " 表选项必须与迁移一致");
            assertEquals(normalizedBodyItems(migration, table), normalizedBodyItems(schema, table),
                    "镜像必须是逐条表体项相同（列 / 键 / 外键 / CHECK 一条不缺），"
                            + "不能只抽几个断言就当作一致");
        }
    }

    @Test
    void seriesTableIsCreatedBeforeEpisodeBecauseOfTheForeignKeyTarget() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(MIGRATION.getFileName().toString(), migration());
        files.put(SCHEMA.getFileName().toString(), schema());

        for (Map.Entry<String, String> file : files.entrySet()) {
            Matcher series = createTableMatcher(file.getValue(), SERIES);
            Matcher episode = createTableMatcher(file.getValue(), EPISODE);
            assertTrue(series.find(), file.getKey() + " 找不到 " + SERIES + " 的 CREATE TABLE");
            assertTrue(episode.find(), file.getKey() + " 找不到 " + EPISODE + " 的 CREATE TABLE");
            assertTrue(series.start() < episode.start(),
                    file.getKey() + " 必须先建 " + SERIES + " 再建 " + EPISODE
                            + "（外键目标要先存在），实际位置：" + series.start()
                            + " vs " + episode.start());
        }
    }

    @Test
    void migrationHeaderStatesTheMysql8016CheckRequirementAndNoStructureVerification()
            throws IOException {
        String migration = migration();

        assertTrue(migration.contains("8.0.16"),
                "迁移必须写明 CHECK 需要 MySQL 8.0.16+：更低版本会解析后静默忽略，"
                        + "不能把 CHECK 当作唯一防线（应用层仍需校验）");
        assertTrue(migration.contains("不等于结构校验"),
                "迁移必须写明 IF NOT EXISTS 不等于结构校验：同名旧表会被整段跳过");
        assertFalse(migration.contains("已执行"),
                "迁移不得声称已在某个数据库执行过");
    }

    @Test
    void parserDistinguishesCheckFromColumnWhenNoSpaceBeforeParenthesis() {
        TableDef tight = parse("""
                CREATE TABLE sample (
                    id BIGINT NOT NULL,
                    duration_seconds DOUBLE NULL,
                    CONSTRAINT ck_sample_duration CHECK(duration_seconds IS NULL OR duration_seconds > 0)
                ) ENGINE=InnoDB;
                """, "sample");
        TableDef spaced = parse("""
                CREATE TABLE sample (
                    id BIGINT NOT NULL,
                    duration_seconds DOUBLE NULL,
                    CONSTRAINT ck_sample_duration CHECK ( duration_seconds IS NULL
                        OR duration_seconds > 0 )
                ) ENGINE=InnoDB;
                """, "sample");

        assertEquals(List.of("DURATION_SECONDS", "ID"), sorted(tight.columns().keySet()),
                "紧贴括号的 CHECK 不能被当成列：列集合只允许 id / duration_seconds");
        assertEquals(1, tight.checks().size(), "CHECK(…) 必须被识别为 CHECK 约束：" + tight.checks());
        assertEquals(compact(tight.checks().get(0)), compact(spaced.checks().get(0)),
                "等价的空白 / 换行写法必须比较相等：" + tight.checks() + " vs " + spaced.checks());
    }

    // ------------------------------------------------------------------
    // 小工具：去注释 / 切语句 / 取一张表的 CREATE TABLE 文本 / 解析表结构。
    // 形状与 E1A 的 SchemaTests 一致（那里的 helper 是 private，无法直接复用），
    // 只服务本片两张表，刻意不做通用 SQL 解析器。
    // ------------------------------------------------------------------

    private static String migration() throws IOException {
        assertTrue(Files.isRegularFile(MIGRATION), "缺少迁移文件：" + MIGRATION.toAbsolutePath());
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    private static String schema() throws IOException {
        assertTrue(Files.isRegularFile(SCHEMA), "缺少初始化脚本：" + SCHEMA.toAbsolutePath());
        return Files.readString(SCHEMA, StandardCharsets.UTF_8);
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

    private static void assertIdempotentCreate(String sql, String table) {
        assertTrue(Pattern.compile("^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+",
                        Pattern.CASE_INSENSITIVE).matcher(createTableText(sql, table)).find(),
                "缺少 CREATE TABLE IF NOT EXISTS " + table);
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
                String name = normalized.substring(0, normalized.indexOf(' ')).trim();
                // 列注释（COMMENT '...'）刻意不进入列定义：注释可以自由改写，不是结构契约。
                columns.put(name.replace("`", ""), withoutCommentAttribute(normalized.substring(name.length()).trim()));
            }
        }
        return new TableDef(table, Map.copyOf(columns), List.copyOf(indexes),
                List.copyOf(foreignKeys), List.copyOf(checks), tableOptionsOf(statement));
    }

    private static IndexDef parseIndex(String item) {
        String normalized = normalize(item);
        Matcher unique = Pattern.compile("^UNIQUE\\s+(?:KEY|INDEX)\\s+(\\S+)\\s*\\(")
                .matcher(normalized);
        if (unique.find()) {
            return new IndexDef("UNIQUE", unique.group(1), parenGroup(item, 0));
        }
        Matcher plain = Pattern.compile("^(?:KEY|INDEX)\\s+(\\S+)\\s*\\(").matcher(normalized);
        if (plain.find()) {
            return new IndexDef("KEY", plain.group(1), parenGroup(item, 0));
        }
        throw new AssertionError("无法解析索引定义：" + normalized);
    }

    private static ForeignKey parseForeignKey(String normalized) {
        Matcher matcher = Pattern.compile(
                        "^CONSTRAINT\\s+(\\S+)\\s+FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s+REFERENCES\\s+"
                                + "`?(\\w+)`?\\s*\\(([^)]*)\\)")
                .matcher(normalized);
        assertTrue(matcher.find(), "无法解析外键定义：" + normalized);
        return new ForeignKey(matcher.group(1), columnList(matcher.group(2)),
                matcher.group(3).trim(), columnList(matcher.group(4)));
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

    private static List<String> columnList(String columns) {
        List<String> parsed = new ArrayList<>();
        for (String column : columns.split(",")) {
            parsed.add(column.trim().toUpperCase(Locale.ROOT));
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

    private static List<String> sorted(Collection<String> names) {
        List<String> copy = new ArrayList<>(names);
        copy.sort(Comparator.naturalOrder());
        return List.copyOf(copy);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}
