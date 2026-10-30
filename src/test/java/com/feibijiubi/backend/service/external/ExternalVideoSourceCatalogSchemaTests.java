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
 * E1-A：外部视频源最小基础表（{@code video_source} / {@code video_source_series}）的离线结构契约。
 *
 * <p>只读两份 {@code .sql} 文本，不连库、不启动 Spring、不执行任何 SQL。它是一组**静态、有限的文本结构
 * 检查**：证明"脚本里写了什么"，**不**证明某个 MySQL 实例真的建成了这样 —— DDL 是否被接受、{@code CHECK}
 * 是否被强制（需要 MySQL 8.0.16+）、唯一键冲突行为、索引键长在真实行格式下是否成立，都未在本轮验证。</p>
 *
 * <p>刻意**不**当成契约的两类写法：SQL 注释（列注释与表注释可以自由改写），以及列在表体里的声明顺序
 * （列集合固定即可）。真正当契约的是：列名集合、每列的类型 / NULL / 默认值、单列主键、
 * <b>唯一键内部的列顺序</b>、外键与其引用列、{@code CHECK} 的取值范围、表选项
 * （{@code ENGINE} / {@code CHARSET} / {@code COLLATE}），以及先建 {@code video_source} 再建
 * {@code video_source_series} 的语句顺序（外键目标必须先存在）。{@code CHECK} 比较会去掉全部空白，
 * 因此 {@code CHECK (enabled IN (0, 1))} 与 {@code CHECK(enabled IN (0,1))} 视为同一约束。</p>
 */
class ExternalVideoSourceCatalogSchemaTests {

    private static final Path MIGRATION = Path.of(
            "database/migrations/e1a-external-video-source-catalog.sql");
    private static final Path SCHEMA = Path.of("database/feibijiubi.sql");

    private static final String SOURCE = "video_source";
    private static final String SERIES = "video_source_series";

    private static final String TABLE_OPTIONS =
            "ENGINE=INNODB DEFAULT CHARSET=UTF8MB4 COLLATE=UTF8MB4_UNICODE_CI";

    /**
     * 每列的类型 / NULL / 默认值（规范化后、去掉 {@code COMMENT} 的精确文本）。键集合同时就是列集合契约；
     * 列在 DDL 里的书写顺序不在断言范围内。
     */
    private static final Map<String, String> SOURCE_DEFINITIONS = Map.ofEntries(
            Map.entry("ID", "INT NOT NULL AUTO_INCREMENT"),
            Map.entry("CODE", "VARCHAR(64) CHARACTER SET UTF8MB4 COLLATE UTF8MB4_BIN NOT NULL"),
            Map.entry("NAME", "VARCHAR(100) NOT NULL"),
            Map.entry("ADAPTER_TYPE", "VARCHAR(32) NOT NULL"),
            Map.entry("BASE_URL", "VARCHAR(500) NOT NULL"),
            Map.entry("ENABLED", "TINYINT NOT NULL DEFAULT 0"),
            Map.entry("CREDENTIAL_REF", "VARCHAR(128) NULL"),
            Map.entry("CREATED_AT", "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP"),
            Map.entry("UPDATED_AT",
                    "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"));

    private static final Map<String, String> SERIES_DEFINITIONS = Map.ofEntries(
            Map.entry("ID", "INT NOT NULL AUTO_INCREMENT"),
            Map.entry("SOURCE_ID", "INT NOT NULL"),
            Map.entry("UPSTREAM_SERIES_ID",
                    "VARCHAR(191) CHARACTER SET UTF8MB4 COLLATE UTF8MB4_BIN NOT NULL"),
            Map.entry("TITLE_SNAPSHOT", "VARCHAR(255) NULL"),
            Map.entry("MC_ID", "VARCHAR(20) NULL"),
            Map.entry("SC_ID", "VARCHAR(20) NULL"),
            Map.entry("COVER_URL_EXTERNAL", "VARCHAR(500) NULL"),
            Map.entry("SYNC_STATE", "TINYINT NOT NULL DEFAULT 0"),
            Map.entry("LAST_SYNCED_AT", "DATETIME NULL"),
            Map.entry("CREATED_AT", "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP"),
            Map.entry("UPDATED_AT",
                    "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"));

    @Test
    void migrationOnlyCreatesTheTwoCatalogTablesAndNeverTouchesExistingObjects()
            throws IOException {
        String migration = migration();
        List<String> statements = statementsOf(migration);

        for (String statement : statements) {
            String normalized = normalize(statement);
            // 顶层语句只允许两种形态：建这两张表，或文件末尾那条只读 SELECT。
            // 因此 ALTER / DROP / UPDATE / INSERT / DELETE / TRUNCATE / REPLACE 都不可能出现。
            assertTrue(normalized.startsWith("CREATE TABLE") || normalized.startsWith("SELECT"),
                    "迁移只允许 CREATE TABLE 与只读 SELECT，发现其它顶层语句：" + normalized);
        }

        List<String> created = statements.stream()
                .map(ExternalVideoSourceCatalogSchemaTests::normalize)
                .filter(normalized -> normalized.startsWith("CREATE TABLE"))
                .toList();
        assertEquals(2, created.size(), "本切片只建两张表，实际：" + created);

        assertIdempotentCreate(migration, SOURCE);
        assertIdempotentCreate(migration, SERIES);
        assertSourceTableIsCreatedFirst(migration, MIGRATION.getFileName().toString());
    }

    @Test
    void sourceColumnSetExcludesLeaseOwnershipAndCredentialValueColumns() throws IOException {
        TableDef source = parse(migration(), SOURCE);

        assertEquals(sorted(SOURCE_DEFINITIONS.keySet()), sorted(source.columns().keySet()),
                "video_source 的列集合必须固定：不含租约列(active_job_*)、归属列(uid/owner_uid/"
                        + "created_by/import_uid)，也不含任何凭据值列");

        // 下面三条与上面的列集合断言有意重复：失败时要直接指出"被提前加的"是哪一类列/索引。
        for (String deferred : List.of("ACTIVE_JOB_ID", "ACTIVE_JOB_EXPIRE_AT")) {
            assertFalse(source.columns().containsKey(deferred),
                    "本切片不实现源级租约，不得提前建 " + deferred + " 列");
        }
        assertTrue(source.indexes().stream().noneMatch(index -> index.name().contains("ACTIVE_JOB")),
                "本切片不实现源级租约，不得建 active_job 索引");
        assertEquals(0, source.foreignKeys().size(),
                "来源表不引用任何表：本切片既没有租约外键，也没有作者归属外键");

        IndexDef code = index(source, "UK_VIDEO_SOURCE_CODE");
        assertEquals("UNIQUE", code.kind(), "来源编码唯一键必须是 UNIQUE");
        assertEquals(List.of("CODE"), code.columns(),
                "来源编码必须唯一，且唯一键只含 code（部署白名单按它引用来源）");
    }

    @Test
    void seriesColumnSetExcludesImportOwnerAndKeepsTheIdentityKeys() throws IOException {
        TableDef series = parse(migration(), SERIES);

        assertEquals(sorted(SERIES_DEFINITIONS.keySet()), sorted(series.columns().keySet()),
                "video_source_series 的列集合必须固定：没有 import_uid，也没有租约列");
        assertFalse(series.columns().containsKey("IMPORT_UID"),
                "作品目录不得携带归属列：归属只在将来的导入任务行");

        IndexDef identity = index(series, "UK_VIDEO_SOURCE_SERIES_IDENTITY");
        assertEquals("UNIQUE", identity.kind(), "作品身份唯一键必须是 UNIQUE");
        assertEquals(List.of("SOURCE_ID", "UPSTREAM_SERIES_ID"), identity.columns(),
                "作品身份唯一键必须是 (source_id, upstream_series_id) 且内部列序固定");

        IndexDef composite = index(series, "UK_VIDEO_SOURCE_SERIES_SOURCE_ID");
        assertEquals("UNIQUE", composite.kind(), "(source_id, id) 必须是 UNIQUE 才能被复合外键引用");
        assertEquals(List.of("SOURCE_ID", "ID"), composite.columns(),
                "必须保留 (source_id, id) 唯一键，供将来的复合外键引用（id 已是主键，本键不改变语义）");
    }

    @Test
    void seriesKeepsSingleSourceForeignKeyWithoutCascade() throws IOException {
        TableDef series = parse(migration(), SERIES);

        assertEquals(1, series.foreignKeys().size(), "作品表只应有一个外键：" + series.foreignKeys());
        ForeignKey foreignKey = series.foreignKeys().get(0);
        assertEquals("FK_VIDEO_SOURCE_SERIES_SOURCE", foreignKey.name());
        assertEquals(List.of("SOURCE_ID"), foreignKey.columns(), "外键列必须是 source_id");
        assertEquals("VIDEO_SOURCE", foreignKey.refTable(),
                "外键必须指向 video_source：本切片不得引用 users 或 video");
        assertEquals(List.of("ID"), foreignKey.refColumns(), "外键必须引用 video_source(id)");
        assertFalse(normalize(createTableText(migration(), SERIES)).contains("ON DELETE"),
                "不得使用 ON DELETE CASCADE：删除必须走应用层并留审计");
    }

    @Test
    void columnDefinitionsPinTypeNullabilityAndDefaults() throws IOException {
        assertEquals(SOURCE_DEFINITIONS, parse(migration(), SOURCE).columns(),
                "video_source 每列的类型 / NULL / 默认值必须固定（列注释不参与比较）");
        assertEquals(SERIES_DEFINITIONS, parse(migration(), SERIES).columns(),
                "video_source_series 每列的类型 / NULL / 默认值必须固定（列注释不参与比较）");
    }

    @Test
    void primaryKeysAreAutoIncrementIntegerIds() throws IOException {
        for (String table : List.of(SOURCE, SERIES)) {
            TableDef def = parse(migration(), table);

            IndexDef primary = index(def, "PRIMARY");
            assertEquals("PRIMARY", primary.kind(), table + " 必须声明主键");
            assertEquals(List.of("ID"), primary.columns(), table + " 的主键必须是单列 id");

            // 与 columnDefinitionsPinTypeNullabilityAndDefaults 有意重复：id 自增是这条断言的主题。
            assertEquals("INT NOT NULL AUTO_INCREMENT", def.columns().get("ID"),
                    table + " 的 id 必须是 INT NOT NULL AUTO_INCREMENT：" + def.columns().get("ID"));
        }
    }

    @Test
    void bothTablesUseTheApprovedStorageOptions() throws IOException {
        for (String table : List.of(SOURCE, SERIES)) {
            assertEquals(TABLE_OPTIONS, parse(migration(), table).tableOptions(),
                    "迁移里的 " + table + " 必须 ENGINE=InnoDB，并沿用库默认的 utf8mb4_unicode_ci"
                            + "（只有身份列单独 bin，表默认仍是 ci）");
            assertEquals(TABLE_OPTIONS, parse(schema(), table).tableOptions(),
                    "database/feibijiubi.sql 里的 " + table + " 表选项必须与迁移一致");
        }
    }

    @Test
    void enumChecksAreDeclaredAndComparedWithoutDependingOnSpacing() throws IOException {
        TableDef source = parse(migration(), SOURCE);
        assertEquals(1, source.checks().size(), "video_source 只应有一个 CHECK：" + source.checks());
        assertEquals(compact("CONSTRAINT ck_video_source_enabled CHECK (enabled IN (0,1))"),
                compact(source.checks().get(0)),
                "enabled 只允许 0/1；比较会去掉空白，因此 CHECK (…) 与 CHECK(…) 等价");

        TableDef series = parse(migration(), SERIES);
        assertEquals(1, series.checks().size(), "video_source_series 只应有一个 CHECK：" + series.checks());
        assertEquals(compact("CONSTRAINT ck_video_source_series_sync_state CHECK (sync_state IN (0,1,2,3))"),
                compact(series.checks().get(0)),
                "sync_state 只允许 0..3；将来新增第二个 CHECK 时必须同步更新本断言");
    }

    @Test
    void parserTreatsCheckWithNoSpaceBeforeTheParenthesisAsACheckNotAColumn() {
        TableDef tight = parse("""
                CREATE TABLE sample (
                    id INT NOT NULL,
                    state TINYINT NOT NULL DEFAULT 0,
                    CONSTRAINT ck_sample_state CHECK(state IN (0,1))
                ) ENGINE=InnoDB;
                """, "sample");
        TableDef spaced = parse("""
                CREATE TABLE sample (
                    id INT NOT NULL,
                    state TINYINT NOT NULL DEFAULT 0,
                    CONSTRAINT ck_sample_state CHECK ( state IN ( 0 , 1 ) )
                ) ENGINE=InnoDB;
                """, "sample");

        assertEquals(sorted(List.of("ID", "STATE")), sorted(tight.columns().keySet()),
                "紧贴括号的 CHECK 不能被当成列：列集合只允许 id / state");
        assertEquals(1, tight.checks().size(), "CHECK(…) 必须被识别为 CHECK 约束：" + tight.checks());
        assertEquals(compact(tight.checks().get(0)), compact(spaced.checks().get(0)),
                "等价的空白写法必须比较相等：" + tight.checks() + " vs " + spaced.checks());
    }

    @Test
    void freshSchemaMirrorsTheMigrationTablesStructurally() throws IOException {
        String schema = schema();

        for (String table : List.of(SOURCE, SERIES)) {
            TableDef declared = parse(migration(), table);
            TableDef mirrored = parse(schema, table);

            assertEquals(sorted(declared.columns().keySet()), sorted(mirrored.columns().keySet()),
                    "database/feibijiubi.sql 的 " + table + " 列集合必须与迁移一致");
            assertEquals(declared.columns(), mirrored.columns(),
                    "database/feibijiubi.sql 的 " + table + " 列定义必须与迁移一致（注释不参与比较）");
            assertEquals(declared.tableOptions(), mirrored.tableOptions(),
                    "database/feibijiubi.sql 的 " + table + " 表选项必须与迁移一致");
            assertEquals(declared.indexes(), mirrored.indexes(),
                    "database/feibijiubi.sql 的 " + table + " 索引必须与迁移一致");
            assertEquals(declared.foreignKeys(), mirrored.foreignKeys(),
                    "database/feibijiubi.sql 的 " + table + " 外键必须与迁移一致");
            assertEquals(declared.checks(), mirrored.checks(),
                    "database/feibijiubi.sql 的 " + table + " CHECK 必须与迁移一致");
        }

        assertSourceTableIsCreatedFirst(schema, SCHEMA.getFileName().toString());
    }

    @Test
    void checksAreDeclaredAsMandatoryBecauseMysqlBefore8016IgnoresThem() throws IOException {
        assertTrue(migration().contains("8.0.16"),
                "迁移必须写明 CHECK 需要 MySQL 8.0.16+：更低版本会解析后静默忽略，"
                        + "不能把 CHECK 当成唯一防线（应用层仍需校验）");
    }

    // ------------------------------------------------------------------
    // 小工具：去注释 / 切语句 / 取一张表的 CREATE TABLE 文本 / 解析表结构。
    // 只服务本文件这两张表，刻意不做通用 SQL 解析器。
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
        assertTrue(createTableMatcher(sql, table).find(),
                "重跑同一迁移必须安全：缺少 CREATE TABLE IF NOT EXISTS " + table);
    }

    /** 外键目标必须先存在：{@code video_source} 的 CREATE TABLE 必须写在 {@code video_source_series} 之前。 */
    private static void assertSourceTableIsCreatedFirst(String sql, String file) {
        String script = stripLineComments(sql);
        Matcher source = createTableMatcher(sql, SOURCE);
        Matcher series = createTableMatcher(sql, SERIES);
        assertTrue(source.find(), file + " 找不到 " + SOURCE + " 的 CREATE TABLE");
        assertTrue(series.find(), file + " 找不到 " + SERIES + " 的 CREATE TABLE");
        assertTrue(source.start() < series.start(),
                file + " 必须先建 " + SOURCE + " 再建 " + SERIES + "（外键目标要先存在），"
                        + "实际位置：" + source.start() + " vs " + series.start() + "（脚本长度 "
                        + script.length() + "）");
    }

    private record IndexDef(String kind, String name, List<String> columns) {
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

    /** 表体右括号之后的表选项，例如 {@code ENGINE=INNODB DEFAULT CHARSET=...（表注释已去掉）}。 */
    private static String tableOptionsOf(String statement) {
        int open = statement.indexOf('(');
        return withoutCommentAttribute(
                normalize(statement.substring(bodyCloseIndex(statement, open) + 1)));
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
                columns.put(name, withoutCommentAttribute(normalized.substring(name.length()).trim()));
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

    /** 取原文本里第 {@code ordinal} 个括号组的内容（先做空白规范化）。 */
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

    /** 统一关键字大小写并去掉空白；仅用于本测试中不含字符串字面量的 CHECK 表达式。 */
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
