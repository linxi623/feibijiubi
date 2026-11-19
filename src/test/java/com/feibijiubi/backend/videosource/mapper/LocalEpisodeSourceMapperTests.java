package com.feibijiubi.backend.videosource.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Real MyBatis XML parsing and static DDL comparison only; no SQL execution. */
class LocalEpisodeSourceMapperTests {
    Configuration config;
    @BeforeEach void parse() throws Exception {
        config = new Configuration(); config.setMapUnderscoreToCamelCase(true);
        String resource = "src/main/resources/com/feibijiubi/backend/mapper/LocalEpisodeSourceMapper.xml";
        try (var input = Files.newInputStream(Path.of(resource))) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
    }
    String sql(String method) {
        return config.getMappedStatement("com.feibijiubi.backend.videosource.mapper.LocalEpisodeSourceMapper." + method)
                .getBoundSql(Map.of()).getSql().replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
    @Test void mutationsHaveEpisodeAndVersionGuards() {
        assertTrue(sql("lockEpisode").endsWith("WHERE ID = ? FOR UPDATE"));
        assertTrue(sql("advanceRevision").contains("WHERE EPISODE_ID = ? AND REVISION = ?"));
        assertTrue(sql("advanceRevision").contains("REVISION < 9223372036854775807"));
        assertTrue(sql("confirmCandidate").contains("WHERE EPISODE_ID = ? AND ID = ? AND CONFIRMED_AT IS NULL"));
        assertTrue(sql("replaceBinding").contains("WHERE EPISODE_ID = ? AND ID = ?"));
        assertTrue(sql("setDuration").contains("WHERE ID = ? AND VID IS NULL"));
    }
    @Test void importedVideoLockReadsOnlyTheExternalTimelineColumns() {
        String lock = sql("lockVideoForUpdate");
        assertTrue(lock.endsWith("FROM VIDEO WHERE VID = ? FOR UPDATE"));
        for (String column : List.of("VID, DURATION", "VIDEO_URL", "VIDEO_KEY", "DELETED_AT")) {
            assertTrue(lock.contains(column), "缺少列 " + column + "：" + lock);
        }
        assertFalse(lock.contains("ADDRESS"));
        assertEquals(3, config.getMappedStatement(
                "com.feibijiubi.backend.videosource.mapper.LocalEpisodeSourceMapper.lockVideoForUpdate")
                .getTimeout());
    }
    @Test void sourceLineChangesNeverTouchImportRevisionOrVideoStatus() {
        for (var method : LocalEpisodeSourceMapper.class.getDeclaredMethods()) {
            String sql = sql(method.getName());
            assertFalse(sql.contains("IMPORT_REVISION"), method.getName());
            assertFalse(sql.contains("VIDEO_STATUS"), method.getName());
        }
    }
    @Test void readsBoundedAndCandidateNeverUpdatedExceptConfirmation() {
        assertTrue(sql("selectCandidates").contains("WHERE EPISODE_ID = ? AND ID > ? ORDER BY ID LIMIT ?"));
        assertTrue(sql("selectBindings").endsWith("ORDER BY ID LIMIT 33"));
        assertTrue(sql("selectCandidate").endsWith("WHERE EPISODE_ID = ? AND ID = ?"));
        assertFalse(sql("confirmCandidate").contains("ADDRESS"));
        assertTrue(sql("confirmCandidate").contains("NOW(3)"));
        for (var m : LocalEpisodeSourceMapper.class.getDeclaredMethods()) {
            String sql = sql(m.getName());
            assertFalse(sql.contains("DELETE ")); assertFalse(sql.contains("VIDEO_STATUS"));
            assertFalse(sql.contains("INSERT INTO VIDEO ")); assertFalse(sql.contains("UPDATE VIDEO "));
        }
    }
    @Test void generatedKeysAndBoundParametersRemainConfigured() {
        for (String method : List.of("insertCandidate", "insertBinding")) {
            var statement = config.getMappedStatement("com.feibijiubi.backend.videosource.mapper.LocalEpisodeSourceMapper." + method);
            assertArrayEquals(new String[]{"id"}, statement.getKeyProperties()); assertEquals(3, statement.getTimeout());
        }
        var bound = config.getMappedStatement("com.feibijiubi.backend.videosource.mapper.LocalEpisodeSourceMapper.insertCandidate").getBoundSql(Map.of());
        var params = bound.getParameterMappings().stream().map(p -> p.getProperty()).toList();
        assertTrue(params.containsAll(List.of("address", "addressHash", "expectedRevision", "capturedBy")));
        assertFalse(params.contains("confirmedBy")); assertFalse(params.contains("confirmedSeconds"));
    }
    @Test void migrationAndMirrorHaveExactlySameThreeTables() throws Exception {
        String migration = Files.readString(Path.of("database/migrations/s2-local-episode-source.sql"));
        String mirror = Files.readString(Path.of("database/feibijiubi.sql"));
        var pattern = Pattern.compile("CREATE TABLE IF NOT EXISTS (local_episode_[a-z_]+) \\(.*?\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;", Pattern.DOTALL);
        var matcher = pattern.matcher(migration); int count = 0;
        while (matcher.find()) {
            count++; String ddl = matcher.group().replaceAll("\\s+", " ");
            assertTrue(mirror.replaceAll("\\s+", " ").contains(ddl), matcher.group(1));
            assertTrue(ddl.contains("ENGINE=InnoDB")); assertTrue(ddl.contains("PRIMARY KEY"));
        }
        assertEquals(3, count);
        assertTrue(migration.contains("FOREIGN KEY (episode_id, candidate_id)"));
        assertTrue(migration.contains("REFERENCES local_episode_media_candidate (episode_id, id)"));
        assertTrue(migration.contains("UNIQUE KEY uk_local_candidate_scope (episode_id, id)"));
        assertFalse(migration.contains("ALTER TABLE")); assertFalse(migration.contains("DROP TABLE"));
    }
}
