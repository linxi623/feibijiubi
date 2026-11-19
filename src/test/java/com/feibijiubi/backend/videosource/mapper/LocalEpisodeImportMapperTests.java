package com.feibijiubi.backend.videosource.mapper;

import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 import mapper + static SQL/DDL consistency. Parses the real MyBatis XML and reads the
 * migration/fresh-schema text; no database, no SQL execution.
 */
class LocalEpisodeImportMapperTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/LocalEpisodeImportMapper.xml");
    private static final Path MIGRATION = Path.of(
            "database/migrations/m5-local-episode-import.sql");
    private static final Path SCHEMA = Path.of("database/feibijiubi.sql");
    private static final String NAMESPACE =
            "com.feibijiubi.backend.videosource.mapper.LocalEpisodeImportMapper";

    Configuration configuration;

    @BeforeEach
    void parse() throws IOException {
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream in = Files.newInputStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML.toString(),
                    configuration.getSqlFragments()).parse();
        }
    }

    String sql(String method) {
        return configuration.getMappedStatement(NAMESPACE + "." + method)
                .getBoundSql(Map.of()).getSql().replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    @Test
    void xmlBindsTheImportMapperInterfaceAndEpisodeResultType() {
        assertNotNull(LocalEpisodeImportMapper.class.getAnnotation(Mapper.class));
        assertTrue(configuration.hasMapper(LocalEpisodeImportMapper.class));
        for (Method method : LocalEpisodeImportMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
        assertEquals(LocalVideoEpisode.class, configuration
                .getMappedStatement(NAMESPACE + ".lockEpisodeForImport")
                .getResultMaps().get(0).getType());
    }

    @Test
    void lockUsesForUpdateAndBindIsAVidNullCas() {
        String lock = sql("lockEpisodeForImport");
        assertTrue(lock.contains("SELECT ID, SERIES_ID, DURATION_SECONDS, VID, IMPORT_REVISION"));
        assertTrue(lock.endsWith("WHERE ID = ? FOR UPDATE"));

        String bind = sql("bindVidAndImportRevision");
        assertTrue(bind.contains("SET VID = ?, IMPORT_REVISION = ?"));
        assertTrue(bind.contains("WHERE ID = ? AND VID IS NULL"),
                "绑定必须带 vid IS NULL CAS，唯一键不能替代行锁 + CAS");

        assertTrue(sql("selectSourceRevision").contains("WHERE EPISODE_ID = ?"));
    }

    @Test
    void coverClaimAtomicallyRequiresOwnerCoverTypeUnclaimedStatusAndUnexpiredTime() {
        assertEquals("UPDATE UPLOAD_TEMP_FILE SET STATUS = 1 WHERE OBJECT_KEY = ? AND UID = ?"
                        + " AND FILE_TYPE = 2 AND STATUS = 0 AND EXPIRE_AT > ?",
                sql("claimTempCover"));
    }

    @Test
    void confirmedSourceReadNeverProjectsMediaAddressOrHashes() {
        String sql = sql("selectConfirmedSourceCodes");
        assertTrue(sql.contains("INNER JOIN"));
        assertTrue(sql.contains("CONFIRMED_AT IS NOT NULL"));
        assertTrue(sql.contains("CONFIRMED_SECONDS IS NOT NULL"));
        assertTrue(sql.endsWith("ORDER BY B.ID LIMIT 33"));
        for (String forbidden : List.of("ADDRESS", "MANIFEST_HASH", "SELECT *", "LINE_KEY")) {
            assertFalse(sql.contains(forbidden), "导入读取不得包含 " + forbidden + "：" + sql);
        }
    }

    @Test
    void importMapperNeverWritesVideoOrMediaColumns() {
        for (Method method : LocalEpisodeImportMapper.class.getDeclaredMethods()) {
            String sql = sql(method.getName());
            for (String forbidden : List.of("DELETE ", "INSERT INTO VIDEO", "UPDATE VIDEO",
                    "VIDEO_URL", "VIDEO_KEY", "COVER_KEY", "COVER_URL", "ADDRESS")) {
                assertFalse(sql.contains(forbidden),
                        method.getName() + " 不得包含 " + forbidden + "：" + sql);
            }
        }
    }

    @Test
    void everyStatementKeepsTheThreeSecondTimeout() {
        for (Method method : LocalEpisodeImportMapper.class.getDeclaredMethods()) {
            MappedStatement statement = configuration.getMappedStatement(NAMESPACE + "." + method.getName());
            assertEquals(3, statement.getTimeout(), method.getName());
        }
    }

    @Test
    void migrationOnlyAltersTheTwoExistingTablesWithoutDataWritesOrBackfill() throws IOException {
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        String code = stripLineComments(migration);
        List<String> statements = statementsOf(code);
        assertEquals(2, statements.size(), "M5 只允许两条 ALTER：" + statements);
        for (String statement : statements) {
            assertTrue(normalize(statement).startsWith("ALTER TABLE"), statement);
        }
        String normalized = normalize(code);
        assertTrue(normalized.contains(
                "ALTER TABLE LOCAL_VIDEO_EPISODE ADD COLUMN IMPORT_REVISION BIGINT NULL"));
        assertTrue(normalized.contains(
                "ALTER TABLE VIDEO MODIFY COLUMN VIDEO_URL VARCHAR(500) NULL"));
        assertTrue(normalized.contains("MODIFY COLUMN VIDEO_KEY VARCHAR(500) NULL"));
        assertFalse(normalized.contains("COVER_KEY"), "M5 不放开 cover_key：封面由 COS 认领");
        assertFalse(normalized.contains("COVER_URL"));
        assertFalse(normalized.contains("DURATION"));
        assertFalse(normalized.contains("STATUS"));
        assertFalse(Pattern.compile("\\b(INSERT|UPDATE|DELETE|DROP|TRUNCATE|REPLACE)\\b")
                .matcher(normalized).find(), "迁移不得写数据或删除对象");
        assertFalse(normalized.contains("FOREIGN_KEY_CHECKS"));
    }

    @Test
    void freshSchemaMirrorsTheMigrationStatements() throws IOException {
        String migration = normalize(stripLineComments(Files.readString(MIGRATION, StandardCharsets.UTF_8)));
        String schema = normalize(stripLineComments(Files.readString(SCHEMA, StandardCharsets.UTF_8)));

        for (String statement : statementsOf(migration)) {
            assertTrue(schema.contains(normalize(statement)),
                    "database/feibijiubi.sql 缺少迁移语句：" + normalize(statement));
        }
        assertTrue(schema.indexOf("CREATE TABLE LOCAL_VIDEO_EPISODE")
                        < schema.indexOf("ALTER TABLE LOCAL_VIDEO_EPISODE ADD COLUMN IMPORT_REVISION"),
                "初始化脚本必须先建表再 ALTER 加列");
        assertTrue(schema.indexOf("CREATE TABLE `VIDEO`")
                        < schema.indexOf("ALTER TABLE VIDEO MODIFY COLUMN VIDEO_URL"),
                "初始化脚本必须先建 video 再放宽播放列");
    }

    @Test
    void freshSchemaKeepsExistingCosColumnsAndConstraints() throws IOException {
        String schema = normalize(stripLineComments(Files.readString(SCHEMA, StandardCharsets.UTF_8)));
        assertTrue(schema.contains("`VIDEO_URL` VARCHAR(500) NOT NULL"));
        assertTrue(schema.contains("`VIDEO_KEY` VARCHAR(500) NOT NULL"));
        assertTrue(schema.contains("`COVER_URL` VARCHAR(500) NOT NULL"));
        assertTrue(schema.contains("`COVER_KEY` VARCHAR(500) NOT NULL"));
        assertTrue(schema.contains("`DURATION` DOUBLE NOT NULL DEFAULT 0"));
        assertTrue(schema.contains("`SOURCE_TYPE` TINYINT NOT NULL DEFAULT 1"));
    }

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

    private static List<String> statementsOf(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            }
            if (c == ';' && !inQuote) {
                if (!current.toString().isBlank()) {
                    statements.add(current.toString().trim());
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}
