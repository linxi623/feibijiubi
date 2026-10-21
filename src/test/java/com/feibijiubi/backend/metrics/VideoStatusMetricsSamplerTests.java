package com.feibijiubi.backend.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.config.VideoStatusProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** T05：积压采样的封顶、最老年龄、阈值、失败保留与只读约束。 */
class VideoStatusMetricsSamplerTests {

    private VideoStatusMetricsMapper mapper;
    private VideoStatusTelemetry telemetry;
    private VideoStatusProperties properties;
    private MutableClock clock;
    private VideoStatusBacklogSampler sampler;

    @BeforeEach
    void setUp() {
        mapper = mock(VideoStatusMetricsMapper.class);
        telemetry = new VideoStatusTelemetry(new ObjectMapper(), Clock.fixed(
                Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC));
        properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setSchedulingEnabled(true);
        properties.setOutboxRelayEnabled(true);
        clock = new MutableClock(Instant.parse("2026-10-21T10:00:00Z"));
        sampler = new VideoStatusBacklogSampler(
                mapper,
                telemetry,
                properties,
                mock(ScheduledExecutorService.class),
                clock
        );
        stubIndexesPresent();
    }

    @Test
    void healthySampleReportsRealZeroButNullAges() {
        stubCount(VideoStatusBacklogSampler.SOURCE_OUTBOX, 0, 0, null);
        stubCount(VideoStatusBacklogSampler.SOURCE_OUTBOX, 1, 0, null);
        stubCount(VideoStatusBacklogSampler.SOURCE_OUTBOX, 2, 0, null);
        stubCount(VideoStatusBacklogSampler.SOURCE_OUTBOX, 3, 0, null);
        stubCount(VideoStatusBacklogSampler.SOURCE_CONSUMED, 0, 2, 10_000L);
        stubCount(VideoStatusBacklogSampler.SOURCE_CONSUMED, 1, 0, null);
        stubCount(VideoStatusBacklogSampler.SOURCE_CONSUMED, 3, 0, null);

        VideoStatusBacklogSampler.Snapshot snapshot = sampler.sampleOnce();

        assertEquals(VideoStatusBacklogSampler.STATE_OK, snapshot.getState());
        assertFalse(snapshot.isStale());
        assertEquals(7, snapshot.getBacklog().size(),
                "outbox 0/1/2/3 + consumed 0/1/3 都要有读数");
        assertTrue(snapshot.getAlarms().isEmpty());
        assertEquals(0L, reading(snapshot, "OUTBOX", 0).getCount());
        assertNull(reading(snapshot, "OUTBOX", 0).getOldestAgeMillis(),
                "没有行时必须返回 null，而不是 0");
        // 计数为 0 时不再查询最老时间（避免多余查询）
        verify(mapper, never()).selectOldestOutboxAgeMillis(1);
    }

    @Test
    void cappedCountIsReportedAsAtLeastCapAndAlarms() {
        stubAllCountsZero();
        when(mapper.countOutboxStatusCapped(0, VideoStatusBacklogSampler.COUNT_CAP))
                .thenReturn(VideoStatusBacklogSampler.COUNT_CAP);
        when(mapper.selectOldestOutboxAgeMillis(0)).thenReturn(5_000L);

        VideoStatusBacklogSampler.Snapshot snapshot = sampler.sampleOnce();

        VideoStatusBacklogSampler.BacklogReading pending = reading(snapshot, "OUTBOX", 0);
        assertEquals(VideoStatusBacklogSampler.COUNT_CAP, pending.getCount());
        assertTrue(pending.getCountCapped(), "返回 >= 10001 时必须标记封顶");
        assertTrue(snapshot.getAlarms().stream()
                .anyMatch(alarm -> "BACKLOG_CAPPED".equals(alarm.getCode())));
    }

    @Test
    void notCappedWhenBelowCap() {
        stubAllCountsZero();
        when(mapper.countConsumedStatusCapped(1, VideoStatusBacklogSampler.COUNT_CAP))
                .thenReturn(VideoStatusBacklogSampler.COUNT_CAP - 1);
        when(mapper.selectOldestConsumedAgeMillis(1)).thenReturn(1_000L);

        VideoStatusBacklogSampler.BacklogReading reading =
                reading(sampler.sampleOnce(), "CONSUMED", 1);

        assertFalse(reading.getCountCapped());
        assertEquals(VideoStatusBacklogSampler.COUNT_CAP - 1, reading.getCount());
    }

    @Test
    void oldestPendingOverThresholdAlarmsAndBoundaryDoesNot() {
        stubAllCountsZero();
        when(mapper.countOutboxStatusCapped(1, VideoStatusBacklogSampler.COUNT_CAP)).thenReturn(3);
        when(mapper.selectOldestOutboxAgeMillis(1))
                .thenReturn(VideoStatusBacklogSampler.PENDING_ALERT_AGE_MILLIS);
        assertTrue(sampler.sampleOnce().getAlarms().stream()
                .noneMatch(alarm -> "PENDING_TOO_OLD".equals(alarm.getCode())),
                "恰好等于阈值不告警（严格大于）");

        when(mapper.selectOldestOutboxAgeMillis(1))
                .thenReturn(VideoStatusBacklogSampler.PENDING_ALERT_AGE_MILLIS + 1);
        VideoStatusBacklogSampler.Snapshot snapshot = sampler.sampleOnce();
        assertTrue(snapshot.getAlarms().stream()
                .anyMatch(alarm -> "PENDING_TOO_OLD".equals(alarm.getCode())));
        assertEquals(VideoStatusBacklogSampler.PENDING_ALERT_AGE_MILLIS + 1,
                reading(snapshot, "OUTBOX", 1).getOldestAgeMillis());
    }

    @Test
    void failedAndRepairStatusesAlarmWhenPresent() {
        stubAllCountsZero();
        when(mapper.countOutboxStatusCapped(3, VideoStatusBacklogSampler.COUNT_CAP)).thenReturn(1);
        when(mapper.selectOldestOutboxAgeMillis(3)).thenReturn(1_000L);
        when(mapper.countConsumedStatusCapped(3, VideoStatusBacklogSampler.COUNT_CAP)).thenReturn(4);
        when(mapper.selectOldestConsumedAgeMillis(3)).thenReturn(2_000L);

        List<VideoStatusBacklogSampler.Alarm> alarms = sampler.sampleOnce().getAlarms();

        assertTrue(alarms.stream().anyMatch(alarm -> "OUTBOX_FAILED".equals(alarm.getCode())));
        assertTrue(alarms.stream().anyMatch(alarm -> "REPAIR_REQUIRED".equals(alarm.getCode())));
    }

    @Test
    void samplingFailureKeepsLastSuccessAndMarksStaleInsteadOfZero() {
        stubAllCountsZero();
        when(mapper.countConsumedStatusCapped(0, VideoStatusBacklogSampler.COUNT_CAP)).thenReturn(7);
        when(mapper.selectOldestConsumedAgeMillis(0)).thenReturn(40_000L);
        VideoStatusBacklogSampler.Snapshot first = sampler.sampleOnce();
        assertEquals(VideoStatusBacklogSampler.STATE_OK, first.getState());

        clock.advanceMillis(VideoStatusBacklogSampler.SAMPLE_INTERVAL_MILLIS);
        when(mapper.countOutboxStatusCapped(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db unavailable"));

        VideoStatusBacklogSampler.Snapshot failed = sampler.sampleOnce();

        assertEquals(VideoStatusBacklogSampler.STATE_STALE, failed.getState());
        assertTrue(failed.isStale());
        assertNotNull(failed.getLastError());
        assertEquals(first.getSampledAtEpochMillis(), failed.getLastSuccessAtEpochMillis());
        assertEquals(7L, reading(failed, "CONSUMED", 0).getCount(),
                "失败时必须保留上次成功数值，不能变成 0");

        // 快照必须以最近的失败尝试为准，不能回退到 lastSuccess 显示 OK。
        VideoStatusBacklogSampler.Snapshot cached = sampler.snapshot();
        assertEquals(VideoStatusBacklogSampler.STATE_STALE, cached.getState());
        assertTrue(cached.isStale());
        assertEquals(first.getSampledAtEpochMillis(), cached.getLastSuccessAtEpochMillis());
    }

    @Test
    void neverSuccessfulSampleIsUnknownWithoutNumbers() {
        when(mapper.countOutboxStatusCapped(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db unavailable"));

        VideoStatusBacklogSampler.Snapshot snapshot = sampler.sampleOnce();

        assertEquals(VideoStatusBacklogSampler.STATE_UNKNOWN, snapshot.getState());
        assertTrue(snapshot.getBacklog().isEmpty());
        assertNull(snapshot.getLastSuccessAtEpochMillis());

        // 失败尝试同样要进入 HTTP 快照，而不是退回“从未采样”的永久 UNKNOWN。
        VideoStatusBacklogSampler.Snapshot cached = sampler.snapshot();
        assertEquals(VideoStatusBacklogSampler.STATE_UNKNOWN, cached.getState());
        assertNotNull(cached.getLastError());
        assertNull(cached.getLastSuccessAtEpochMillis());
    }

    @Test
    void missingObservabilityIndexReportsDiagnosticsAndSkipsCounting() {
        when(mapper.indexExists("video_status_outbox",
                VideoStatusBacklogSampler.OUTBOX_CREATED_INDEX)).thenReturn(0);

        VideoStatusBacklogSampler.Snapshot snapshot = sampler.sampleOnce();

        assertEquals(VideoStatusBacklogSampler.STATE_DEGRADED, snapshot.getState());
        assertTrue(snapshot.isStale());
        assertTrue(snapshot.getDiagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.contains("t05-video-status-observability")),
                "缺少迁移时必须给出可执行诊断");
        verify(mapper, never()).countOutboxStatusCapped(anyInt(), anyInt());
        verify(mapper, never()).countConsumedStatusCapped(anyInt(), anyInt());
        verify(mapper, never()).selectOldestOutboxAgeMillis(anyInt());
        verify(mapper, never()).selectOldestConsumedAgeMillis(anyInt());
    }

    @Test
    void stalledSchedulerHeartbeatAlarmsOnlyWhenRoleEnabled() {
        stubAllCountsZero();
        telemetry.heartbeat(VideoStatusTelemetry.Operation.RELAY_TICK);
        clock.advanceMillis(VideoStatusBacklogSampler.SCHEDULER_ALERT_AGE_MILLIS + 1);

        VideoStatusBacklogSampler.Snapshot stalled = sampler.sampleOnce();
        assertEquals("STALLED", scheduler(stalled, "RELAY_TICK").getState());
        assertTrue(stalled.getAlarms().stream()
                .anyMatch(alarm -> "SCHEDULER_STALLED".equals(alarm.getCode())));

        properties.setOutboxRelayEnabled(false);
        VideoStatusBacklogSampler.Snapshot disabled = sampler.sampleOnce();
        assertFalse(scheduler(disabled, "RELAY_TICK").isRoleEnabledOnThisInstance());
        assertTrue(disabled.getAlarms().stream()
                        .noneMatch(alarm -> "SCHEDULER_STALLED".equals(alarm.getCode())
                                && "RELAY_TICK".equals(alarm.getSubject())),
                "本实例不承担该角色时不能产生假告警");
    }

    @Test
    void neverReportedHeartbeatIsUnknownNotHealthy() {
        stubAllCountsZero();

        VideoStatusBacklogSampler.SchedulerReading reading = scheduler(sampler.sampleOnce(), "FLUSH_TICK");

        assertEquals(VideoStatusBacklogSampler.STATE_UNKNOWN, reading.getState());
        assertNull(reading.getAgeMillis());
    }

    @Test
    void enabledRoleWithoutAnyHeartbeatStallsAfterStartupGrace() {
        stubAllCountsZero();

        VideoStatusBacklogSampler.Snapshot withinGrace = sampler.sampleOnce();
        assertEquals(VideoStatusBacklogSampler.STATE_UNKNOWN,
                scheduler(withinGrace, "FLUSH_TICK").getState());
        assertTrue(withinGrace.getAlarms().stream()
                        .noneMatch(alarm -> "SCHEDULER_STALLED".equals(alarm.getCode())),
                "启动宽限期内不能产生假告警");

        clock.advanceMillis(VideoStatusBacklogSampler.SCHEDULER_ALERT_AGE_MILLIS);
        assertEquals(VideoStatusBacklogSampler.STATE_UNKNOWN,
                scheduler(sampler.sampleOnce(), "FLUSH_TICK").getState(),
                "恰好 30s 不告警（严格大于）");

        clock.advanceMillis(1L);
        VideoStatusBacklogSampler.Snapshot stalled = sampler.sampleOnce();
        VideoStatusBacklogSampler.SchedulerReading reading = scheduler(stalled, "FLUSH_TICK");
        assertEquals(VideoStatusBacklogSampler.STATE_STALLED, reading.getState());
        assertEquals(VideoStatusBacklogSampler.SCHEDULER_ALERT_AGE_MILLIS + 1L,
                reading.getAgeMillis(), "年龄从构造时刻算起");
        assertTrue(stalled.getAlarms().stream()
                .anyMatch(alarm -> "SCHEDULER_STALLED".equals(alarm.getCode())));

        // 同样的“从未心跳”在角色关闭时仍只报 UNKNOWN，不产生假告警。
        properties.setOutboxRelayEnabled(false);
        clock.advanceMillis(VideoStatusBacklogSampler.SCHEDULER_ALERT_AGE_MILLIS);
        VideoStatusBacklogSampler.Snapshot relayDisabled = sampler.sampleOnce();
        assertEquals(VideoStatusBacklogSampler.STATE_UNKNOWN,
                scheduler(relayDisabled, "RELAY_TICK").getState());
        assertTrue(relayDisabled.getAlarms().stream()
                .noneMatch(alarm -> "SCHEDULER_STALLED".equals(alarm.getCode())
                        && "RELAY_TICK".equals(alarm.getSubject())));
    }

    @Test
    void snapshotTurnsStaleWhenNoFreshSampleAndRecomputesHeartbeatAlarms() {
        stubAllCountsZero();
        VideoStatusBacklogSampler.Snapshot sampled = sampler.sampleOnce();
        assertEquals(VideoStatusBacklogSampler.STATE_OK, sampled.getState());

        clock.advanceMillis(VideoStatusBacklogSampler.SNAPSHOT_STALE_AFTER_MILLIS);
        VideoStatusBacklogSampler.Snapshot boundary = sampler.snapshot();
        assertEquals(VideoStatusBacklogSampler.STATE_OK, boundary.getState());
        assertFalse(boundary.isStale(), "恰好 45s 仍视为可接受");

        // 读快照不查库：先清掉 mapper 交互再断言。
        reset(mapper);
        clock.advanceMillis(1L);
        VideoStatusBacklogSampler.Snapshot expired = sampler.snapshot();
        assertEquals(VideoStatusBacklogSampler.STATE_STALE, expired.getState());
        assertTrue(expired.isStale(), "超过 45s 没有新采样就不能永久 OK");
        assertNotNull(expired.getLastError());
        assertEquals(sampled.getSampledAtEpochMillis(), expired.getSampledAtEpochMillis(),
                "过期快照仍标明上次采样时间，不伪造新采样");
        verifyNoInteractions(mapper);

        // 采样线程停摆期间，已开启角色的心跳告警必须能随读取重算出来。
        clock.advanceMillis(VideoStatusBacklogSampler.SCHEDULER_ALERT_AGE_MILLIS);
        VideoStatusBacklogSampler.Snapshot stalled = sampler.snapshot();
        assertEquals(VideoStatusBacklogSampler.STATE_STALLED,
                scheduler(stalled, "FLUSH_TICK").getState());
        assertTrue(stalled.getAlarms().stream()
                .anyMatch(alarm -> "SCHEDULER_STALLED".equals(alarm.getCode())));
        verifyNoInteractions(mapper);
    }

    @Test
    void sentOutboxStatusIsSampledLikeOtherStatuses() {
        stubAllCountsZero();
        when(mapper.countOutboxStatusCapped(2, VideoStatusBacklogSampler.COUNT_CAP)).thenReturn(2);
        when(mapper.selectOldestOutboxAgeMillis(2)).thenReturn(5_000L);

        VideoStatusBacklogSampler.BacklogReading sent =
                reading(sampler.sampleOnce(), "OUTBOX", 2);

        assertEquals("SENT", sent.getStatusLabel());
        assertEquals(2L, sent.getCount());
        assertEquals(5_000L, sent.getOldestAgeMillis());
    }

    @Test
    void samplingErrorDoesNotLeakSqlPayloadOrCredentialsToHttp() throws Exception {
        when(mapper.countOutboxStatusCapped(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException(
                        "SELECT * FROM video_status_outbox WHERE token='secret-credential' "
                                + "AND payload={\"vid\":42}"));

        VideoStatusBacklogSampler.Snapshot snapshot = sampler.sampleOnce();

        assertEquals("IllegalStateException", snapshot.getLastError());
        String json = new ObjectMapper().writeValueAsString(snapshot);
        assertFalse(json.contains("SELECT"), "HTTP 快照不得包含 SQL");
        assertFalse(json.toLowerCase().contains("payload"), "HTTP 快照不得包含 payload");
        assertFalse(json.contains("secret-credential"), "HTTP 快照不得包含凭证");
        assertFalse(json.contains("\"vid\""), "HTTP 快照不得包含业务字段");
    }

    @Test
    void metricsMapperIsReadOnly() throws IOException {
        ClassPathResource resource = new ClassPathResource(
                "com/feibijiubi/backend/mapper/VideoStatusMetricsMapper.xml");
        String xml;
        try (var input = resource.getInputStream()) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher matcher = Pattern.compile("<\\s*(insert|update|delete)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(xml);
        assertFalse(matcher.find(), "观测 mapper 不允许包含写语句");
        assertTrue(xml.contains("timeout="), "只读采样语句必须带 query timeout");
        assertTrue(xml.contains("ORDER BY created_at, id")
                        && xml.contains("ORDER BY consumed_at, id"),
                "最老查询必须按真实时间排序，不能按 id 假冒最老");
        assertTrue(xml.contains("LIMIT #{cap}"), "计数必须封顶");
    }

    @Test
    void metricsMapperXmlParsesWithOnlyTimedOutSelects() throws IOException {
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        String resource = "com/feibijiubi/backend/mapper/VideoStatusMetricsMapper.xml";
        try (var input = new ClassPathResource(resource).getInputStream()) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments()
            ).parse();
        }

        String namespace = "com.feibijiubi.backend.metrics.VideoStatusMetricsMapper.";
        for (String id : List.of("countOutboxStatusCapped", "selectOldestOutboxAgeMillis",
                "countConsumedStatusCapped", "selectOldestConsumedAgeMillis", "indexExists")) {
            var statement = configuration.getMappedStatement(namespace + id);
            assertNotNull(statement, "缺少语句: " + id);
            assertEquals(org.apache.ibatis.mapping.SqlCommandType.SELECT,
                    statement.getSqlCommandType());
            assertEquals(3, statement.getTimeout(), id + " 必须设置 query timeout");
        }
    }

    private void stubIndexesPresent() {
        when(mapper.indexExists("video_status_outbox",
                VideoStatusBacklogSampler.OUTBOX_CREATED_INDEX)).thenReturn(1);
        when(mapper.indexExists("video_status_consumed_event",
                VideoStatusBacklogSampler.CONSUMED_CREATED_INDEX)).thenReturn(1);
    }

    private void stubAllCountsZero() {
        when(mapper.countOutboxStatusCapped(anyInt(), anyInt())).thenReturn(0);
        when(mapper.countConsumedStatusCapped(anyInt(), anyInt())).thenReturn(0);
    }

    private void stubCount(String source, int status, Integer count, Long oldestAgeMillis) {
        if (VideoStatusBacklogSampler.SOURCE_OUTBOX.equals(source)) {
            when(mapper.countOutboxStatusCapped(status, VideoStatusBacklogSampler.COUNT_CAP))
                    .thenReturn(count);
            if (oldestAgeMillis != null) {
                when(mapper.selectOldestOutboxAgeMillis(status)).thenReturn(oldestAgeMillis);
            }
        } else {
            when(mapper.countConsumedStatusCapped(status, VideoStatusBacklogSampler.COUNT_CAP))
                    .thenReturn(count);
            if (oldestAgeMillis != null) {
                when(mapper.selectOldestConsumedAgeMillis(status)).thenReturn(oldestAgeMillis);
            }
        }
    }

    private VideoStatusBacklogSampler.BacklogReading reading(
            VideoStatusBacklogSampler.Snapshot snapshot, String source, int status) {
        return snapshot.getBacklog().stream()
                .filter(candidate -> candidate.getSource().equals(source)
                        && candidate.getStatus() == status)
                .findFirst()
                .orElseThrow();
    }

    private VideoStatusBacklogSampler.SchedulerReading scheduler(
            VideoStatusBacklogSampler.Snapshot snapshot, String operation) {
        return snapshot.getSchedulerHeartbeats().stream()
                .filter(candidate -> candidate.getOperation().equals(operation))
                .findFirst()
                .orElseThrow();
    }

    /** 可控时钟：验证阈值不靠 sleep。 */
    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(Instant instant) {
            this.millis = instant.toEpochMilli();
        }

        void advanceMillis(long delta) {
            millis += delta;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
