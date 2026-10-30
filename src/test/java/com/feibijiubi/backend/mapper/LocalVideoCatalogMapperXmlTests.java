package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.LocalVideoEpisode;
import com.feibijiubi.backend.entity.LocalVideoSeries;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1：{@code LocalVideoCatalogMapper.xml} 的离线 SQL 契约。
 *
 * <p>不是“整文件 contains 假绿”：这里用<b>真实 MyBatis 解析</b>（{@link XMLMapperBuilder}）拿到每个
 * {@link MappedStatement} 的 {@link BoundSql}，再对<b>渲染后的</b>语句断言表名、绑定参数名与顺序、
 * {@code ORDER BY} / {@code LIMIT}、以及 {@code useGeneratedKeys} + {@code keyProperty}。</p>
 *
 * <p>证明的范围：脚本写出了什么、会渲染成什么。**不**证明：SQL 在真实 MySQL 上执行成功、外键/唯一键
 * 真的生效、生成主键回填真的发生、索引真的被使用 —— 那些都需要连库演练，本片没有连库。</p>
 */
class LocalVideoCatalogMapperXmlTests {

    private static final Path MAPPER = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/LocalVideoCatalogMapper.xml");
    private static final String NAMESPACE = "com.feibijiubi.backend.mapper.LocalVideoCatalogMapper.";

    private Configuration configuration;

    @BeforeEach
    void parseMapper() throws IOException {
        assertTrue(Files.isRegularFile(MAPPER), "缺少 mapper 资源：" + MAPPER.toAbsolutePath());
        configuration = new Configuration();
        try (InputStream in = Files.newInputStream(MAPPER)) {
            new XMLMapperBuilder(in, configuration, MAPPER.toString(),
                    configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void everyStatementTargetsOnlyTheTwoLocalTables() {
        List<String> statements = statementNames();
        assertEquals(6, statements.size(),
                "本片只有两条 INSERT + 四条 SELECT：" + statements);

        for (String name : statements) {
            String sql = normalized(rendered(shortName(name), parameterFor(shortName(name))));
            assertTrue(sql.contains("LOCAL_VIDEO_"),
                    name + " 必须只读写本地目录表，实际：" + sql);
            for (String forbidden : List.of(
                    "VIDEO_STATUS", "VIDEO_SOURCE", "VIDEO_INTERACTION", "JOIN",
                    "INSERT INTO VIDEO ", "UPDATE VIDEO ", "DELETE FROM VIDEO ")) {
                assertFalse(sql.contains(forbidden),
                        name + " 不得涉及 " + forbidden + "（本片不建 video、不联表、不碰来源/统计）：" + sql);
            }
        }
    }

    @Test
    void insertsOmitDatabaseOwnedAndNotYetWritableColumns() {
        List<String> seriesColumns = insertColumns(rendered("insertSeries", new LocalVideoSeries()), "SERIES");
        assertEquals(List.of("LOCAL_IDENTITY", "TITLE", "CREATED_BY"), seriesColumns,
                "作品 INSERT 只写三个业务列：时间戳交给数据库默认值，身份/创建者由服务端决定");

        String episodeSql = normalized(rendered("insertEpisode", new LocalVideoEpisode()));
        List<String> episodeColumns = insertColumns(episodeSql, "EPISODE");
        assertEquals(List.of("SERIES_ID", "LOCAL_IDENTITY", "DISPLAY_TITLE", "CREATED_BY"),
                episodeColumns,
                "剧集 INSERT 不写 duration_seconds / vid：由数据库 NULL 默认值决定，本片没有赋值入口");

        assertEquals(3, placeholderCount(seriesSql()), "作品 INSERT 的占位符数量必须与列数一致");
        assertEquals(4, placeholderCount(episodeSql), "剧集 INSERT 的占位符数量必须与列数一致");
    }

    @Test
    void insertBoundParametersAreExactlyTheEntityPropertiesWithoutVidOrDuration() {
        assertEquals(List.of("localIdentity", "title", "createdBy"),
                boundProperties("insertSeries", new LocalVideoSeries()),
                "作品 INSERT 的绑定参数只有身份 / 标题 / 创建者");

        assertEquals(List.of("seriesId", "localIdentity", "displayTitle", "createdBy"),
                boundProperties("insertEpisode", new LocalVideoEpisode()),
                "剧集 INSERT 没有 durationSeconds / vid 绑定参数：服务端无法写入它们");
    }

    @Test
    void insertStatementsAskForTheGeneratedKeyIntoId() {
        for (String id : List.of("insertSeries", "insertEpisode")) {
            MappedStatement statement = configuration.getMappedStatement(NAMESPACE + id);
            assertEquals(SqlCommandType.INSERT, statement.getSqlCommandType(), id + " 必须是 INSERT");
            assertTrue(statement.getKeyGenerator() instanceof Jdbc3KeyGenerator,
                    id + " 必须使用 JDBC 生成主键（useGeneratedKeys=true）");
            assertArrayEquals(new String[]{"id"}, statement.getKeyProperties(),
                    id + " 必须把生成主键回填到单实体参数的 id 属性");
        }
    }

    @Test
    void seriesSelectsAreKeyedOrderedAndBounded() {
        String byId = normalized(rendered("selectSeriesById", Map.of("id", 1L)));
        assertTrue(byId.contains("FROM LOCAL_VIDEO_SERIES"));
        assertTrue(byId.contains("WHERE ID = ?"), "按主键回读：" + byId);

        String page = normalized(rendered("selectSeriesAfter",
                Map.of("afterId", 0L, "limit", 3)));
        assertTrue(page.contains("FROM LOCAL_VIDEO_SERIES"));
        assertTrue(page.contains("WHERE ID > ?"), "keyset 用 id > afterId，不用 OFFSET：" + page);
        assertTrue(page.contains("ORDER BY ID"), "必须按 id 升序：" + page);
        assertTrue(page.contains("LIMIT ?"), "LIMIT 必须是绑定参数而不是拼接字符串：" + page);
        assertFalse(page.contains("OFFSET"), "本片不用 OFFSET：" + page);
        assertEquals(List.of("afterId", "limit"),
                boundProperties("selectSeriesAfter", Map.of("afterId", 0L, "limit", 3)));
    }

    @Test
    void episodeSelectsFilterBySeriesThenKeyset() {
        String byId = normalized(rendered("selectEpisodeById", Map.of("id", 1L)));
        assertTrue(byId.contains("FROM LOCAL_VIDEO_EPISODE"));
        assertTrue(byId.contains("WHERE ID = ?"));

        String page = normalized(rendered("selectEpisodesAfter",
                Map.of("seriesId", 9L, "afterId", 0L, "limit", 3)));
        assertTrue(page.contains("FROM LOCAL_VIDEO_EPISODE"));
        assertTrue(page.contains("WHERE SERIES_ID = ?"), "先按父作品过滤：" + page);
        assertTrue(page.contains("AND ID > ?"), "再按 id 游标取下一页：" + page);
        assertTrue(page.contains("ORDER BY ID"));
        assertTrue(page.contains("LIMIT ?"));
        assertFalse(page.contains("OFFSET"));
        assertEquals(List.of("seriesId", "afterId", "limit"), boundProperties("selectEpisodesAfter",
                Map.of("seriesId", 9L, "afterId", 0L, "limit", 3)));
    }

    @Test
    void mapperDeclaresNoUpdateOrDeleteStatement() {
        for (String name : statementNames()) {
            SqlCommandType commandType = configuration.getMappedStatement(name).getSqlCommandType();
            assertTrue(commandType == SqlCommandType.SELECT || commandType == SqlCommandType.INSERT,
                    name + " 只能是 SELECT / INSERT：本片没有 update、delete、合并或导入：" + commandType);
        }

        String xml;
        try {
            xml = Files.readString(MAPPER, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        } catch (IOException e) {
            throw new AssertionError("读取 mapper 失败", e);
        }
        for (String tag : List.of("<UPDATE", "<DELETE", "UPDATE LOCAL_VIDEO", "DELETE FROM")) {
            assertFalse(xml.contains(tag), "mapper 不得包含 " + tag);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private List<String> statementNames() {
        List<String> names = new ArrayList<>();
        for (String name : configuration.getMappedStatementNames()) {
            if (name.startsWith(NAMESPACE)) {
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private static String shortName(String fullName) {
        return fullName.substring(NAMESPACE.length());
    }

    private static Object parameterFor(String statementId) {
        return switch (statementId) {
            case "insertSeries" -> new LocalVideoSeries();
            case "insertEpisode" -> new LocalVideoEpisode();
            case "selectSeriesById", "selectEpisodeById" -> Map.of("id", 1L);
            case "selectSeriesAfter" -> Map.of("afterId", 0L, "limit", 3);
            case "selectEpisodesAfter" -> Map.of("seriesId", 9L, "afterId", 0L, "limit", 3);
            default -> throw new AssertionError("未预期的语句：" + statementId);
        };
    }

    private String rendered(String statementId, Object parameter) {
        return configuration.getMappedStatement(NAMESPACE + statementId)
                .getBoundSql(parameter)
                .getSql();
    }

    private String seriesSql() {
        return rendered("insertSeries", new LocalVideoSeries());
    }

    private List<String> boundProperties(String statementId, Object parameter) {
        BoundSql boundSql = configuration.getMappedStatement(NAMESPACE + statementId)
                .getBoundSql(parameter);
        List<String> properties = new ArrayList<>();
        for (ParameterMapping mapping : boundSql.getParameterMappings()) {
            properties.add(mapping.getProperty());
        }
        return List.copyOf(properties);
    }

    /** 取 INSERT 语句的列清单（表名后缀用于定位，例如 {@code "SERIES"} / {@code "EPISODE"}）。 */
    private List<String> insertColumns(String sql, String tableSuffix) {
        String normalized = normalized(sql);
        String marker = "INSERT INTO LOCAL_VIDEO_" + tableSuffix + "(";
        int open = normalized.indexOf(marker);
        assertTrue(open >= 0, "找不到 INSERT 目标表 LOCAL_VIDEO_" + tableSuffix + "：" + normalized);
        int start = open + marker.length();
        int close = normalized.indexOf(')', start);
        assertTrue(close > start, "列清单括号不闭合：" + normalized);

        List<String> columns = new ArrayList<>();
        for (String column : normalized.substring(start, close).split(",")) {
            columns.add(column.trim());
        }
        return List.copyOf(columns);
    }

    /** 统计 VALUES 部分的占位符数量：列数与占位符数必须一致，否则 SQL 本身就是错的。 */
    private static int placeholderCount(String sql) {
        String normalized = normalized(sql);
        int values = normalized.indexOf("VALUES");
        assertTrue(values >= 0, "找不到 VALUES：" + normalized);
        int count = 0;
        for (int i = values; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}
