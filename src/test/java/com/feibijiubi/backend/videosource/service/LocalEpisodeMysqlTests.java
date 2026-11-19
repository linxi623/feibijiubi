package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.infrastructure.storage.service.FileStorageService;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.local.mapper.LocalVideoCatalogMapper;
import com.feibijiubi.backend.video.mapper.CategoryMapper;
import com.feibijiubi.backend.video.mapper.UploadTempFileMapper;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.dto.LocalEpisodeImportDTO;
import com.feibijiubi.backend.videosource.dto.LocalEpisodeSourceConfirmDTO;
import com.feibijiubi.backend.videosource.integration.ExternalMediaAddressDigest;
import com.feibijiubi.backend.videosource.mapper.LocalEpisodeImportMapper;
import com.feibijiubi.backend.videosource.mapper.LocalEpisodeSourceMapper;
import com.feibijiubi.backend.videosource.service.impl.LocalEpisodeImportServiceImpl;
import com.feibijiubi.backend.videosource.service.impl.LocalEpisodeSourceServiceImpl;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real MySQL/MyBatis/transactions, with storage and upstream replaced by test doubles. */
@EnabledIfSystemProperty(named = "externalEpisodeMysqlIT", matches = "true")
class LocalEpisodeMysqlTests {
    private static final String ADDRESS = "https://media.example.invalid/generated.m3u8";
    private static JdbcTemplate db;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager tx;
    private static final ExternalVideoSourceProperties PROPS = new ExternalVideoSourceProperties();

    @BeforeAll
    static void setup() throws Exception {
        String port = System.getenv("EPISODE_TEST_MYSQL_PORT");
        String marker = System.getenv("EPISODE_TEST_MYSQL_MARKER");
        String password = System.getenv("EPISODE_TEST_MYSQL_PASSWORD");
        if (port == null || !port.matches("[1-9][0-9]{3,4}") || Integer.parseInt(port) > 65535
                || marker == null || !marker.matches("[0-9a-f]{32}") || password == null || password.isBlank()) {
            throw new IllegalStateException("A fresh marked loopback MySQL fixture is required");
        }
        var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/feibijiubi?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=15000",
                "episode_test_only", password);
        db = new JdbcTemplate(ds);
        if (number("SELECT COUNT(*) FROM episode_test_isolation WHERE run_marker = ?", marker) != 1
                || !db.queryForObject("SELECT VERSION()", String.class).startsWith("8.4.")) {
            throw new IllegalStateException("Refusing non-fixture database");
        }
        tx = new DataSourceTransactionManager(ds);
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(NoLoggingImpl.class);
        var factory = new SqlSessionFactoryBean();
        factory.setConfiguration(config);
        factory.setDataSource(ds);
        factory.setMapperLocations(List.of("LocalEpisodeImportMapper", "LocalEpisodeSourceMapper", "LocalVideoCatalogMapper",
                "VideoMapper", "VideoStatusMapper", "UploadTempFileMapper", "CategoryMapper")
                .stream().map(name -> new ClassPathResource("com/feibijiubi/backend/mapper/" + name + ".xml"))
                .toArray(ClassPathResource[]::new));
        sql = new SqlSessionTemplate(factory.getObject());
        var source = new ExternalVideoSourceProperties.Source();
        source.setEnabled(true);
        source.setCode("demo");
        source.setName("isolated fixture");
        source.setApiBase("https://api.example.invalid/api.php");
        source.setApiAllowedEndpoints(Set.of("api.example.invalid:443"));
        source.setMediaAllowedEndpoints(Set.of("media.example.invalid:443"));
        PROPS.setSources(List.of(source));
        PROPS.setPreviewEnabled(true);
        db.update("INSERT INTO users (id, username, password_hash, nickname, role) VALUES (7, 'mysql-fixture', 'unused', 'mysql-fixture', 1)");
        db.update("INSERT INTO local_video_series (id, local_identity, title, created_by) VALUES (1, '00000000-0000-4000-8000-000000000001', 'Fixture', 7)");
    }

    private static long number(String query, Object... params) {
        return db.queryForObject(query, Long.class, params);
    }

    private static String seed(long id) {
        String cover = "temp/covers/7/20260709/episode-" + id + ".png";
        db.update("INSERT INTO upload_temp_file (uid, file_type, object_key, expire_at) VALUES (7, 2, ?, NOW() + INTERVAL 1 DAY)", cover);
        db.update("INSERT INTO local_video_episode (id, series_id, local_identity, duration_seconds, created_by) VALUES (?, 1, ?, 12.5, 7)",
                id, "00000000-0000-4000-8000-%012d".formatted(id));
        db.update("INSERT INTO local_episode_source_state (episode_id, revision) VALUES (?, 1)", id);
        long candidate = candidate(id, 0, null, "SUCCEEDED");
        db.update("UPDATE local_episode_media_candidate SET confirmed_seconds=12.5, confirmed_by=7, confirmed_at=NOW(3) WHERE id=?", candidate);
        db.update("INSERT INTO local_episode_source_binding (episode_id, candidate_id) VALUES (?, ?)", id, candidate);
        return cover;
    }

    private static long candidate(long id, long revision, Long target, String status) {
        db.update("""
                INSERT INTO local_episode_media_candidate (episode_id, expected_revision, target_binding_id, source_code,
                    work_id, line_ordinal, entry_ordinal, label, address, address_hash, probe_status,
                    candidate_seconds, manifest_hash, captured_by)
                VALUES (?, ?, ?, 'demo', '7', 1, 1, 'fixture', ?, ?, ?, ?, ?, 7)
                """, id, revision, target, ADDRESS, ExternalMediaAddressDigest.sha256Hex(ADDRESS), status,
                "SUCCEEDED".equals(status) ? 12.6 : null, "SUCCEEDED".equals(status) ? "a".repeat(64) : null);
        return number("SELECT MAX(id) FROM local_episode_media_candidate WHERE episode_id=?", id);
    }

    private static LocalEpisodeImportService importer(FileStorageService storage) {
        return new LocalEpisodeImportServiceImpl(sql.getMapper(LocalEpisodeImportMapper.class), sql.getMapper(VideoMapper.class),
                sql.getMapper(VideoStatusMapper.class), sql.getMapper(UploadTempFileMapper.class), sql.getMapper(CategoryMapper.class),
                storage, new ExternalVideoSourceRegistry(PROPS), tx);
    }

    private static LocalEpisodeSourceService sources() {
        return new LocalEpisodeSourceServiceImpl(sql.getMapper(LocalEpisodeSourceMapper.class), sql.getMapper(LocalVideoCatalogMapper.class),
                sql.getMapper(VideoMapper.class), new ExternalVideoSourceRegistry(PROPS), mock(ExternalVideoSourceClientProvider.class),
                mock(ExternalMediaProbe.class), mock(ExternalVideoSourcePreviewQuotaService.class), PROPS, tx);
    }

    private static FileStorageService storage(String cover) {
        var storage = mock(FileStorageService.class);
        var keys = new AtomicInteger();
        when(storage.objectExists(cover)).thenReturn(true);
        when(storage.buildFormalCoverKey(7, cover)).thenAnswer(it -> "covers/7/20260709/" + keys.incrementAndGet() + ".png");
        when(storage.Key2Url(anyString())).thenAnswer(it -> "https://storage.example.invalid/" + it.getArgument(0));
        return storage;
    }

    private static LocalEpisodeImportDTO request(String cover, String title) {
        return new LocalEpisodeImportDTO(1L, cover, title, "douga", "mad", 1);
    }

    @Test
    void concurrentFirstImportUsesOneVidAndLateRetrySkipsStorage() throws Exception {
        var cover = seed(11);
        var storage = storage(cover);
        var copies = new CountDownLatch(2);
        doAnswer(it -> {
            copies.countDown();
            assertTrue(copies.await(8, TimeUnit.SECONDS));
            return null;
        }).when(storage).copyObject(eq(cover), anyString());
        var service = importer(storage);
        var request = request(cover, "concurrent fixture");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.importEpisode(7, 11, request));
            var second = executor.submit(() -> service.importEpisode(7, 11, request));
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertEquals(a.vid(), b.vid());
            assertNotEquals(a.reused(), b.reused());
            assertEquals(1, number("SELECT COUNT(*) FROM video WHERE title='concurrent fixture' AND video_url IS NULL AND video_key IS NULL AND status=0"));
            assertEquals(1, number("SELECT COUNT(*) FROM video_status WHERE vid=?", a.vid()));
            assertEquals(1, number("SELECT import_revision FROM local_video_episode WHERE id=11 AND vid=?", a.vid()));
            assertEquals(1, number("SELECT status FROM upload_temp_file WHERE object_key=?", cover));
            clearInvocations(storage);
            assertEquals(a.vid(), service.importEpisode(7, 11, request).vid());
            verifyNoInteractions(storage);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void losingCoverClaimRollsBackVideoStatisticsAndBinding() {
        var cover = seed(12);
        var storage = storage(cover);
        db.execute("""
                CREATE TRIGGER episode_test_force_claim_failure BEFORE INSERT ON video_status FOR EACH ROW
                UPDATE upload_temp_file SET status=2 WHERE object_key='temp/covers/7/20260709/episode-12.png'
                """);
        try {
            var failure = assertThrows(BusinessException.class, () -> importer(storage).importEpisode(7, 12, request(cover, "rollback fixture")));
            assertEquals(409, failure.getCode());
            assertEquals(0, number("SELECT COUNT(*) FROM video WHERE title='rollback fixture'"));
            assertEquals(0, number("SELECT COUNT(*) FROM video_status s LEFT JOIN video v ON v.vid=s.vid WHERE v.vid IS NULL"));
            assertEquals(1, number("SELECT COUNT(*) FROM local_video_episode WHERE id=12 AND vid IS NULL AND import_revision IS NULL"));
            assertEquals(0, number("SELECT status FROM upload_temp_file WHERE object_key=?", cover));
            verify(storage).deleteObject("covers/7/20260709/1.png");
            verify(storage, never()).deleteObject(cover);
        } finally {
            db.execute("DROP TRIGGER episode_test_force_claim_failure");
        }
    }

    @Test
    void replacementRaceHasOneWinnerAndPreservesVidAndViewerData() throws Exception {
        var cover = seed(13);
        int vid = importer(storage(cover)).importEpisode(7, 13, request(cover, "line fixture")).vid();
        db.update("UPDATE video_status SET play_times=3, like_times=1 WHERE vid=?", vid);
        db.update("INSERT INTO user_video (vid, uid, play_time, liked, collect) VALUES (?, 7, 6.5, 1, 1)", vid);
        long binding = number("SELECT id FROM local_episode_source_binding WHERE episode_id=13");
        long old = number("SELECT candidate_id FROM local_episode_source_binding WHERE id=?", binding);
        var service = sources();
        var request = new LocalEpisodeSourceConfirmDTO(1L, 12.5, "same timeline fixture");
        long bad = candidate(13, 1, binding, "PROBE_REJECTED");
        assertEquals(409, assertThrows(BusinessException.class, () -> service.confirm(7, 13, bad, request)).getCode());
        assertEquals(old, number("SELECT candidate_id FROM local_episode_source_binding WHERE id=?", binding));
        long a = candidate(13, 1, binding, "SUCCEEDED"), b = candidate(13, 1, binding, "SUCCEEDED");
        var ready = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = executor.invokeAll(List.of(confirm(service, a, request, ready), confirm(service, b, request, ready)), 15, TimeUnit.SECONDS);
            assertEquals(Set.of(200, 409), Set.of(results.get(0).get(), results.get(1).get()));
            long winner = number("SELECT candidate_id FROM local_episode_source_binding WHERE id=?", binding);
            assertTrue(winner == a || winner == b);
            assertEquals(1, number("SELECT COUNT(*) FROM local_episode_media_candidate WHERE id IN (?, ?) AND confirmed_at IS NOT NULL", a, b));
            assertEquals(2, number("SELECT revision FROM local_episode_source_state WHERE episode_id=13"));
            assertEquals(1, number("SELECT import_revision FROM local_video_episode WHERE id=13 AND vid=?", vid));
            assertEquals(1, number("SELECT COUNT(*) FROM user_video WHERE vid=? AND play_time=6.5 AND liked=1 AND collect=1", vid));
            assertEquals(1, number("SELECT COUNT(*) FROM video_status WHERE vid=? AND play_times=3 AND like_times=1", vid));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Callable<Integer> confirm(LocalEpisodeSourceService service, long id, LocalEpisodeSourceConfirmDTO request, CountDownLatch ready) {
        return () -> {
            ready.countDown();
            assertTrue(ready.await(8, TimeUnit.SECONDS));
            try { service.confirm(7, 13, id, request); return 200; }
            catch (BusinessException e) { return e.getCode(); }
        };
    }
}
