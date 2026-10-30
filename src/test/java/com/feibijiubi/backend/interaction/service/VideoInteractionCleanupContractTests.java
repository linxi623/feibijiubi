package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.interaction.service.impl.VideoInteractionCleanupScheduler;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionCleanupServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T15 有界清理的静态契约：mapper SQL 形态、索引迁移的幂等性、以及“清理不碰统计表/Redis”的结构性约束。
 *
 * <p>不连 DB、不启动 Spring。真实索引生效（{@code EXPLAIN} 命中新索引且无 filesort）、真实守卫行为
 * 与真实锁交错由 {@code VideoInteractionCleanupIsolatedTests} 在隔离 MySQL 上验证；本轮只编译该类、未运行。</p>
 */
class VideoInteractionCleanupContractTests {

    private static final Path MAPPER = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/VideoInteractionMapper.xml");
    private static final Path MIGRATION = Path.of(
            "database/migrations/t15-video-interaction-cleanup-indexes.sql");
    private static final Path SCHEMA = Path.of("database/feibijiubi.sql");
    private static final Path ISOLATED_IT = Path.of(
            "src/test/java/com/feibijiubi/backend/interaction/service/"
                    + "VideoInteractionCleanupIsolatedTests.java");

    private static final String REQUESTS_SELECT =
            "<select id=\"selectExpiredRequestIds\" resultType=\"java.lang.Long\" timeout=\"3\">";
    private static final String REQUESTS_DELETE =
            "<delete id=\"deleteExpiredRequestsByIds\" timeout=\"3\">";
    private static final String WINDOWS_SELECT =
            "<select id=\"selectIdleWindowIds\" resultType=\"java.lang.Long\" timeout=\"3\">";
    private static final String WINDOWS_DELETE =
            "<delete id=\"deleteIdleWindowsByIds\" timeout=\"3\">";

    @Test
    void selectsAreBoundedAndForceTheCleanupIndexes() throws IOException {
        String xml = read(MAPPER);

        String requests = statement(xml, REQUESTS_SELECT, "</select>");
        assertTrue(requests.contains(
                        "FORCE INDEX (idx_video_interaction_request_state_completed)"),
                "缺索引时必须报错，而不是静默退化成全表扫描");
        assertTrue(requests.contains("state = #{state}"),
                "state 必须是单值查询：IN (1,2) 会让两个 range 需要全局 filesort，LIMIT 提前终止失效");
        assertFalse(requests.contains("state IN ("),
                "候选查询不允许写成多 state 的 IN");
        assertTrue(requests.contains("completed_at IS NOT NULL"));
        assertTrue(requests.contains("TIMESTAMPADD(DAY, -#{retentionDays}, NOW(3))"),
                "阈值必须用 DB 时钟，且由服务传入的固定保留期决定");
        assertTrue(requests.contains("ORDER BY completed_at, id"),
                "ORDER BY 必须与索引列序一致");
        assertTrue(requests.contains("LIMIT #{limit}"), "候选必须有界");

        String windows = statement(xml, WINDOWS_SELECT, "</select>");
        assertTrue(windows.contains(
                "FORCE INDEX (idx_video_interaction_window_last_counted)"));
        assertTrue(windows.contains("last_counted_at IS NOT NULL"),
                "从未计数（NULL）的窗口行永不进入候选：删掉它等于让下一次请求必然计数");
        assertTrue(windows.contains("TIMESTAMPADD(DAY, -#{retentionDays}, NOW(3))"));
        assertTrue(windows.contains("ORDER BY last_counted_at, id"));
        assertTrue(windows.contains("LIMIT #{limit}"));
    }

    @Test
    void deletesRepeatTimeAndStateGuardsAndFailClosedOnEmptyIds() throws IOException {
        String xml = read(MAPPER);

        String requests = statement(xml, REQUESTS_DELETE, "</delete>");
        assertTrue(requests.contains("WHERE id IN"),
                "删除必须按主键等值，范围删除会取 next-key 锁并放大锁足迹");
        assertTrue(requests.contains("AND state IN (1, 2)"),
                "删除必须重复终态守卫：PENDING 永远不能被清理删掉");
        assertTrue(requests.contains("completed_at IS NOT NULL"));
        assertTrue(requests.contains("TIMESTAMPADD(DAY, -#{retentionDays}, NOW(3))"),
                "删除必须重复时间守卫，否则会删掉候选读之后刚被推进/改状态的行");
        assertTrue(requests.contains("WHERE 1 = 0"),
                "空 id 集合必须 fail closed（删 0 行），不能拼出无 WHERE 的整表删除");

        String windows = statement(xml, WINDOWS_DELETE, "</delete>");
        assertTrue(windows.contains("WHERE id IN"));
        assertTrue(windows.contains("last_counted_at IS NOT NULL"));
        assertTrue(windows.contains("TIMESTAMPADD(DAY, -#{retentionDays}, NOW(3))"),
                "窗口删除必须重复时间守卫：这是“不删刚推进的窗口行”的唯一防线");
        assertTrue(windows.contains("WHERE 1 = 0"));
    }

    @Test
    void mapperXmlParsesAndRendersFailClosedEmptyDeletes() throws IOException {
        Configuration configuration = new Configuration();
        try (InputStream in = Files.newInputStream(MAPPER)) {
            new XMLMapperBuilder(in, configuration, MAPPER.toString(),
                    configuration.getSqlFragments()).parse();
        }

        // 空 id 集合必须渲染成 WHERE 1 = 0：任何情况下都不能拼出无 WHERE 的整表删除。
        String emptyRequestDelete = renderedSql(configuration, "deleteExpiredRequestsByIds",
                Map.of("ids", List.of(), "retentionDays", 30));
        assertTrue(normalized(emptyRequestDelete).contains("WHERE 1 = 0"),
                "空 id 集合必须 fail closed，实际渲染：" + emptyRequestDelete);
        String emptyWindowDelete = renderedSql(configuration, "deleteIdleWindowsByIds",
                Map.of("ids", List.of(), "retentionDays", 30));
        assertTrue(normalized(emptyWindowDelete).contains("WHERE 1 = 0"));

        // 非空 id 集合必须渲染出主键 IN + 时间/状态守卫。
        String requestDelete = normalized(renderedSql(configuration, "deleteExpiredRequestsByIds",
                Map.of("ids", List.of(7L, 8L), "retentionDays", 30)));
        assertTrue(requestDelete.contains("ID IN ( ? , ? )"));
        assertTrue(requestDelete.contains("STATE IN (1, 2)"));
        assertTrue(requestDelete.contains("COMPLETED_AT IS NOT NULL"));
        assertTrue(requestDelete.contains("TIMESTAMPADD(DAY, -?, NOW(3))"));

        String windowDelete = normalized(renderedSql(configuration, "deleteIdleWindowsByIds",
                Map.of("ids", List.of(7L), "retentionDays", 30)));
        assertTrue(windowDelete.contains("ID IN ( ? )"));
        assertTrue(windowDelete.contains("LAST_COUNTED_AT IS NOT NULL"));
        assertTrue(windowDelete.contains("TIMESTAMPADD(DAY, -?, NOW(3))"));
    }

    @Test
    void mapperSelectsRenderBoundedOrderedQueries() throws IOException {
        Configuration configuration = new Configuration();
        try (InputStream in = Files.newInputStream(MAPPER)) {
            new XMLMapperBuilder(in, configuration, MAPPER.toString(),
                    configuration.getSqlFragments()).parse();
        }

        String requests = normalized(renderedSql(configuration, "selectExpiredRequestIds",
                Map.of("state", 1, "retentionDays", 30, "limit", 3)));
        assertTrue(requests.contains("FORCE INDEX (IDX_VIDEO_INTERACTION_REQUEST_STATE_COMPLETED)"));
        assertTrue(requests.contains("ORDER BY COMPLETED_AT, ID"));
        assertTrue(requests.contains("LIMIT ?"));

        String windows = normalized(renderedSql(configuration, "selectIdleWindowIds",
                Map.of("retentionDays", 30, "limit", 3)));
        assertTrue(windows.contains("FORCE INDEX (IDX_VIDEO_INTERACTION_WINDOW_LAST_COUNTED)"));
        assertTrue(windows.contains("ORDER BY LAST_COUNTED_AT, ID"));
        assertTrue(windows.contains("LIMIT ?"));
    }

    /**
     * 收尾阶段（{@code @AfterEach}）的静态契约：<b>只能用本类跟踪的 id 集合按主键删</b>，
     * 绝不能再调用全局 {@code cleanupOnce()}。
     *
     * <p>为什么必须钉住这个：{@code @AfterEach} 在用例失败（包括 foreign guard 拒绝）时照样执行。
     * 如果收尾跑全局清理，它就会把 guard 刚拒绝的那些“别人的过期行”删掉，把“不删未知测试数据”的承诺
     * 变成假话。这条用例只读源码文本，不连 DB，因此可以跟随普通单测一起跑。</p>
     */
    @Test
    void isolatedTeardownDeletesOnlyOwnTrackedIdsAndNeverRunsGlobalCleanup() throws IOException {
        String source = read(ISOLATED_IT);
        String teardown = isolatedTeardownMethod(source);

        assertFalse(teardown.contains("cleanupOnce("),
                "@AfterEach 绝不能调用全局 cleanupOnce()：foreign guard 拒绝后收尾仍会执行，"
                        + "那样会删掉别人的过期行。实际收尾代码：" + teardown);
        assertFalse(teardown.contains("runCleanupUntilOwnCandidatesGone"),
                "收尾不能复用全局清理辅助方法");
        assertTrue(teardown.contains("deleteExpiredRequestsByIds"),
                "收尾必须用按主键等值删除（保留时间/状态守卫）");
        assertTrue(teardown.contains("ownRequestIds"),
                "请求行删除必须以本次跟踪的 id 集合为范围");
        assertTrue(teardown.contains("deleteIdleWindowsByIds"));
        assertTrue(teardown.contains("ownWindowIds"),
                "窗口行删除必须以本次跟踪的 id 集合为范围");
    }

    @Test
    void cleanupStatementsNeverTouchStatisticsTablesOrRedisEvidence() throws IOException {
        String xml = read(MAPPER);
        int start = xml.indexOf("T15 有界清理");
        assertTrue(start > 0, "missing cleanup section marker");
        String cleanupSection = xml.substring(start).toUpperCase(Locale.ROOT);

        for (String forbidden : List.of("VIDEO_STATUS_OUTBOX", "VIDEO_STATUS_CONSUMED_EVENT",
                "VIDEO_STATUS", "PROCESSED", "REDIS", "JOIN")) {
            assertFalse(cleanupSection.contains(forbidden),
                    "清理语句不得涉及 " + forbidden + "：不碰统计账本/Redis 证据，也不做跨表关联");
        }
        assertTrue(cleanupSection.contains("VIDEO_INTERACTION_REQUEST"));
        assertTrue(cleanupSection.contains("VIDEO_INTERACTION_WINDOW"));
    }

    @Test
    void migrationAddsBothIndexesIdempotentlyAndDeletesNothing() throws IOException {
        assertTrue(Files.isRegularFile(MIGRATION), "missing migration: " + MIGRATION);
        String executable = executableSql(read(MIGRATION));

        assertTrue(executable.contains("INFORMATION_SCHEMA.STATISTICS"),
                "重复执行必须先查元数据");
        assertTrue(executable.contains("COUNT(*) = 0"));
        assertTrue(executable.contains("'SELECT 1'"), "第二次执行必须是 no-op");
        assertTrue(executable.contains("CREATE INDEX IDX_VIDEO_INTERACTION_REQUEST_STATE_COMPLETED"),
                "请求表索引缺失");
        assertTrue(executable.contains("ON VIDEO_INTERACTION_REQUEST (STATE, COMPLETED_AT, ID)"));
        assertTrue(executable.contains("CREATE INDEX IDX_VIDEO_INTERACTION_WINDOW_LAST_COUNTED"),
                "窗口表索引缺失");
        assertTrue(executable.contains("ON VIDEO_INTERACTION_WINDOW (LAST_COUNTED_AT, ID)"));

        for (String forbidden : List.of("DELETE FROM", "DROP TABLE", "TRUNCATE", "DROP INDEX",
                "UPDATE VIDEO_INTERACTION")) {
            assertFalse(executable.contains(forbidden),
                    "索引迁移不得包含 " + forbidden + "（rollback 必须留在注释里）");
        }
    }

    @Test
    void freshSchemaMirrorsBothCleanupIndexes() throws IOException {
        String schema = read(SCHEMA);

        assertTrue(schema.contains(
                        "KEY idx_video_interaction_request_state_completed (state, completed_at, id)"),
                "新建库必须自带请求表清理索引（清理的 FORCE INDEX 是硬前置）");
        assertTrue(schema.contains(
                        "KEY idx_video_interaction_window_last_counted (last_counted_at, id)"),
                "新建库必须自带窗口表清理索引");
    }

    @Test
    void cleanupAndSchedulerHaveNoRedisOrStatisticsCollaborator() {
        for (Class<?> type : List.of(VideoInteractionCleanupServiceImpl.class,
                VideoInteractionCleanupScheduler.class)) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    assertFalse(isRedisOrStatisticsType(parameter),
                            type.getSimpleName() + " 构造器不应依赖 "
                                    + parameter.getSimpleName()
                                    + "：清理不得读写 Redis，也不得碰统计链路");
                }
            }
            for (Field field : type.getDeclaredFields()) {
                assertFalse(isRedisOrStatisticsType(field.getType()),
                        type.getSimpleName() + " 字段不应依赖 " + field.getType().getSimpleName());
            }
        }
    }

    private static boolean isRedisOrStatisticsType(Class<?> type) {
        String name = type.getName().toLowerCase(Locale.ROOT);
        return name.contains("redis") || name.contains("redisson")
                || name.contains("videostatus") || name.contains("rabbit");
    }

    /** 截取 {@code @AfterEach} 方法体：按“单独成行”的标注定位，避开 javadoc 里的同名文字。 */
    private String isolatedTeardownMethod(String source) {
        // 仓库里两种行尾都可能出现（Windows 工作副本），先归一化，避免断言依赖 CRLF/LF。
        String normalizedSource = source.replace("\r\n", "\n");
        String afterEachMarker = "\n    @AfterEach\n";
        int start = normalizedSource.indexOf(afterEachMarker);
        assertTrue(start >= 0, "隔离类必须保留显式的 @AfterEach 收尾（单独成行）");
        int end = normalizedSource.length();
        for (String boundary : List.of("\n    @BeforeEach\n", "\n    @Test\n")) {
            int index = normalizedSource.indexOf(boundary, start + afterEachMarker.length());
            if (index > start) {
                end = Math.min(end, index);
            }
        }
        assertTrue(end > start, "无法定位 @AfterEach 方法体的结束位置");
        return normalizedSource.substring(start, end);
    }

    private String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "missing file: " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 用真实 MyBatis 解析结果渲染 SQL：证明动态标签（choose/foreach）真的按预期分支。 */
    private String renderedSql(Configuration configuration, String statementId,
                               Map<String, Object> parameters) {
        BoundSql boundSql = configuration.getMappedStatement(
                "com.feibijiubi.backend.interaction.mapper.VideoInteractionMapper." + statementId)
                .getBoundSql(parameters);
        return boundSql.getSql();
    }

    /** 压平空白并转大写，避免断言依赖缩进。 */
    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private String statement(String xml, String marker, String end) {
        int start = xml.indexOf(marker);
        assertTrue(start >= 0, "missing mapper statement " + marker);
        int endIndex = xml.indexOf(end, start);
        assertTrue(endIndex > start, "unterminated mapper statement " + marker);
        return xml.substring(start, endIndex);
    }

    /** 去掉整行 SQL 注释，避免把 rollback 段当成可执行语句。 */
    private String executableSql(String script) {
        StringBuilder sql = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                sql.append(line).append('\n');
            }
        }
        return sql.toString().toUpperCase(Locale.ROOT);
    }
}
