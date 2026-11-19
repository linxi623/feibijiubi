package com.feibijiubi.backend.videosource.mapper;

import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.videosource.entity.LocalEpisodePlaybackLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6-A mapper: parses the real MyBatis XML and asserts the SQL shape. No database, no SQL execution.
 *
 * <p>The playback read must stay bounded and read-only: episode scoped line join (LIMIT 33), visible
 * episodes only (same predicate as public detail), keyset by local episode id, no COUNT/OFFSET and no
 * INSERT/UPDATE/DELETE.</p>
 */
class VideoPlaybackMapperTests {

    private static final Path XML = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/VideoPlaybackMapper.xml");
    private static final String NAMESPACE =
            "com.feibijiubi.backend.videosource.mapper.VideoPlaybackMapper";

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
    void xmlBindsThePlaybackMapperInterfaceAndResultTypes() {
        assertNotNull(VideoPlaybackMapper.class.getAnnotation(Mapper.class));
        assertTrue(configuration.hasMapper(VideoPlaybackMapper.class));
        for (Method method : VideoPlaybackMapper.class.getDeclaredMethods()) {
            assertTrue(configuration.hasStatement(NAMESPACE + "." + method.getName()),
                    "缺少 MappedStatement：" + method.getName());
        }
        assertEquals(LocalVideoEpisode.class, resultType("selectEpisodeByVid"));
        assertEquals(LocalEpisodePlaybackLine.class, resultType("selectPlaybackLines"));
        assertEquals(LocalVideoEpisode.class, resultType("selectVisibleEpisodesAfter"));
        assertTrue(sql("selectEpisodeByVid").endsWith("WHERE VID = ?"),
                "按 video.vid 找唯一绑定的本地剧集");
    }

    @Test
    void lineQueryIsEpisodeScopedBoundedAndJoinsOnlyTheCurrentBindingPointer() {
        String sql = sql("selectPlaybackLines");

        assertTrue(sql.contains("FROM LOCAL_EPISODE_SOURCE_BINDING B"));
        assertTrue(sql.contains("INNER JOIN LOCAL_EPISODE_MEDIA_CANDIDATE C"));
        assertTrue(sql.contains("C.EPISODE_ID = B.EPISODE_ID AND C.ID = B.CANDIDATE_ID"));
        assertTrue(sql.contains("WHERE B.EPISODE_ID = ?"), "必须按当前本地剧集限定");
        assertTrue(sql.endsWith("ORDER BY B.ID LIMIT 33"), "32 条上限 + 1 行溢出探测");
        assertTrue(sql.contains("C.ADDRESS"), "播放需要读取服务端保存的原始地址");
        assertTrue(sql.contains("C.ADDRESS_HASH"));
        assertTrue(sql.contains("C.CONFIRMED_AT"));
        assertTrue(sql.contains("C.PROBE_STATUS"));
        assertFalse(sql.contains("SELECT *"));
        assertFalse(sql.contains("COUNT("));
        assertFalse(sql.contains("OFFSET"));
    }

    @Test
    void visibleEpisodesUseTheSamePublicPredicateAndLocalIdKeyset() {
        String sql = sql("selectVisibleEpisodesAfter");

        assertTrue(sql.contains("FROM LOCAL_VIDEO_EPISODE E"));
        assertTrue(sql.contains("INNER JOIN VIDEO V ON V.VID = E.VID"));
        assertTrue(sql.contains("E.SERIES_ID = ?"), "选集必须限定在同一本地作品");
        assertTrue(sql.contains("E.ID > ?"), "游标必须是本地 episode.id keyset");
        assertTrue(sql.contains("V.STATUS = 1"), "未发布/未过审不可见");
        assertTrue(sql.contains("V.DELETED_AT IS NULL"), "软删不可见");
        assertTrue(sql.contains("(V.VISIBILITY = 0 OR V.UID = ?)"), "公开或本人；管理员无额外分支");
        assertTrue(sql.endsWith("ORDER BY E.ID LIMIT ?"));
        assertFalse(sql.contains("COUNT("), "不得返回总量");
        assertFalse(sql.contains("OFFSET"));
        assertFalse(sql.contains("ADDRESS"));
    }

    @Test
    void everyStatementIsARead() {
        for (Method method : VideoPlaybackMapper.class.getDeclaredMethods()) {
            String sql = sql(method.getName());
            assertTrue(method.getName().startsWith("select"),
                    "播放 mapper 只能是 SELECT：" + method.getName());
            for (String write : new String[]{"INSERT ", "UPDATE ", "DELETE ", "REPLACE "}) {
                assertFalse(sql.contains(write), method.getName() + " 不得写库：" + write);
            }
            assertFalse(sql.contains("VIDEO_STATUS"));
            assertFalse(sql.contains("USER_VIDEO"));
        }
    }

    private Class<?> resultType(String statement) {
        return configuration.getMappedStatement(NAMESPACE + "." + statement)
                .getResultMaps().get(0).getType();
    }
}
