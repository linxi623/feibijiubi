package com.feibijiubi.backend.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** T05：遥测只做有限枚举计数，端到端延迟解析失败不造假值。 */
class VideoStatusMetricsTelemetryTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-10-21T10:00:30Z"),
            ZoneOffset.UTC
    );

    private VideoStatusTelemetry newTelemetry() {
        return new VideoStatusTelemetry(new ObjectMapper(), FIXED_CLOCK);
    }

    @Test
    void recordCountsOutcomesAndTracksMaxElapsed() {
        VideoStatusTelemetry telemetry = newTelemetry();

        telemetry.record(VideoStatusTelemetry.Operation.PUBLISH,
                VideoStatusTelemetry.Outcome.SUCCESS, 1_000L);
        telemetry.record(VideoStatusTelemetry.Operation.PUBLISH,
                VideoStatusTelemetry.Outcome.SUCCESS, 3_000L);
        telemetry.record(VideoStatusTelemetry.Operation.PUBLISH,
                VideoStatusTelemetry.Outcome.FAILURE, -5L);

        VideoStatusTelemetry.OperationSnapshot publish = operation(telemetry, "PUBLISH");
        assertEquals(3L, publish.getRecorded());
        assertEquals(2L, publish.getOutcomes().get("SUCCESS"));
        assertEquals(1L, publish.getOutcomes().get("FAILURE"));
        assertEquals(0L, publish.getOutcomes().get("DEFERRED"));
        assertEquals(0L, publish.getOutcomes().get("TIMEOUT"));
        // 负耗时按 0 计，最大值来自 3000ns
        assertEquals(4_000L, publish.getElapsedNanosTotal());
        assertEquals(3_000L, publish.getElapsedNanosMax());
    }

    @Test
    void snapshotKeysAreBoundedEnumsWithoutEventOrVideoLabels() {
        VideoStatusTelemetry telemetry = newTelemetry();
        telemetry.record(VideoStatusTelemetry.Operation.CONSUME,
                VideoStatusTelemetry.Outcome.DEFERRED, 10L);

        VideoStatusTelemetry.Snapshot snapshot = telemetry.snapshot();

        assertEquals(VideoStatusTelemetry.Operation.values().length,
                snapshot.getOperations().size());
        for (VideoStatusTelemetry.OperationSnapshot operation : snapshot.getOperations()) {
            assertTrue(java.util.Arrays.stream(VideoStatusTelemetry.Operation.values())
                            .anyMatch(candidate -> candidate.name().equals(operation.getOperation())),
                    "operation 必须是有限枚举: " + operation.getOperation());
            assertEquals(4, operation.getOutcomes().size());
            assertFalse(operation.getOutcomes().containsKey("eventId"));
            assertFalse(operation.getOutcomes().containsKey("vid"));
        }
    }

    @Test
    void heartbeatExposesAgeAndNeverFakesAFreshHeartbeat() {
        VideoStatusTelemetry telemetry = newTelemetry();

        VideoStatusTelemetry.OperationSnapshot before = operation(telemetry, "RELAY_TICK");
        assertNull(before.getLastHeartbeatAtEpochMillis());
        assertNull(before.getLastHeartbeatAgeMillis());

        telemetry.heartbeat(VideoStatusTelemetry.Operation.RELAY_TICK);
        VideoStatusTelemetry.OperationSnapshot after = operation(telemetry, "RELAY_TICK");
        assertEquals(1L, after.getHeartbeatCount());
        assertEquals(FIXED_CLOCK.millis(), after.getLastHeartbeatAtEpochMillis());
        assertEquals(0L, after.getLastHeartbeatAgeMillis());
    }

    @Test
    void recordFlushedPayloadComputesEndToEndMillisFromOccurredAt() {
        VideoStatusTelemetry telemetry = newTelemetry();

        telemetry.recordFlushedPayload(
                "{\"eventId\":\"e-1\",\"vid\":42,\"occurredAt\":\"2026-10-21T10:00:00\"}");

        VideoStatusTelemetry.EndToEndSnapshot endToEnd = telemetry.snapshot().getEndToEnd();
        assertEquals(1L, endToEnd.getSamples());
        assertEquals(0L, endToEnd.getUnknown());
        assertEquals(30_000L, endToEnd.getMaxMillis());
        assertEquals(30_000L, endToEnd.getLastMillis());
        assertEquals(30_000L, endToEnd.getAvgMillis());
    }

    @Test
    void localDateTimePayloadIsParsedInClockZoneNotUtc() {
        // 业务侧用 LocalDateTime.now()（主机时区）写 occurredAt；
        // 时钟是 +08:00 时必须按同一时区解析，否则会得到负值并被 clamp 成假 0。
        Clock shanghaiClock = Clock.fixed(
                Instant.parse("2026-10-21T10:00:30Z"),
                ZoneId.of("Asia/Shanghai")
        );
        VideoStatusTelemetry telemetry =
                new VideoStatusTelemetry(new ObjectMapper(), shanghaiClock);

        telemetry.recordFlushedPayload("{\"occurredAt\":\"2026-10-21T18:00:00\"}");
        telemetry.recordFlushedPayload(
                "{\"occurredAt\":[2026,10,21,17,59,30,0]}");

        VideoStatusTelemetry.EndToEndSnapshot endToEnd = telemetry.snapshot().getEndToEnd();
        assertEquals(2L, endToEnd.getSamples());
        assertEquals(0L, endToEnd.getUnknown());
        assertEquals(60_000L, endToEnd.getMaxMillis());
        assertEquals(60_000L, endToEnd.getLastMillis());
        assertEquals(45_000L, endToEnd.getAvgMillis());
    }

    @Test
    void defaultConstructorUsesSystemDefaultZoneForLocalPayloads() {
        VideoStatusTelemetry telemetry = new VideoStatusTelemetry(new ObjectMapper());

        Clock clock = (Clock) ReflectionTestUtils.getField(telemetry, "clock");
        assertNotNull(clock);
        assertEquals(ZoneId.systemDefault(), clock.getZone(),
                "默认时钟必须与 LocalDateTime.now() 的主机时区一致");
    }

    @Test
    void recordFlushedPayloadWithoutTimestampOnlyCountsUnknown() {
        VideoStatusTelemetry telemetry = newTelemetry();

        telemetry.recordFlushedPayload("{\"vid\":42}");
        telemetry.recordFlushedPayload("not-json");
        telemetry.recordFlushedPayload(null);

        VideoStatusTelemetry.EndToEndSnapshot endToEnd = telemetry.snapshot().getEndToEnd();
        assertEquals(0L, endToEnd.getSamples());
        assertEquals(3L, endToEnd.getUnknown());
        assertNull(endToEnd.getAvgMillis(), "无样本时不能返回假 0");
        assertNull(endToEnd.getMaxMillis());
        assertNull(endToEnd.getLastMillis());
    }

    private VideoStatusTelemetry.OperationSnapshot operation(VideoStatusTelemetry telemetry,
                                                             String name) {
        return telemetry.snapshot().getOperations().stream()
                .filter(candidate -> candidate.getOperation().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
