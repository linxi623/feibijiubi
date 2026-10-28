package com.feibijiubi.backend.service.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mq.VideoStatusEventPublisher;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusStatus0CompensationClaimService;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusStatus0CompensationServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusEventFingerprintService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusEventAgeGuard;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusStatus0CompensationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T09 单元测试：不依赖 MQ/Redis/MySQL 等基础设施。
 *
 * <p>真实 SQL 租约与 Redis TTL 组合放在显式 opt-in 的 isolated IT 骨架中，
 * 本类只通过 mock 验证闸门、边界和 CAS/预算决策。</p>
 */
class VideoStatusStatus0CompensationTests {

    private VideoStatusConsumedEventMapper mapper;
    private VideoStatusStatus0CompensationClaimService claimService;
    private VideoStatusEventPublisher publisher;
    private VideoStatusEventFingerprintService fingerprintService;
    private StringRedisTemplate redisTemplate;
    private VideoStatusProperties properties;
    private VideoStatusStatus0CompensationServiceImpl service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mapper = mock(VideoStatusConsumedEventMapper.class);
        claimService = mock(VideoStatusStatus0CompensationClaimService.class);
        publisher = mock(VideoStatusEventPublisher.class);
        fingerprintService = mock(VideoStatusEventFingerprintService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        properties = new VideoStatusProperties();
        properties.setConsumerRecoveryAutoReplayMaxAgeSeconds(604_800);
        properties.setConsumerMaxRetries(5);
        properties.setPublishConfirmTimeoutSeconds(5);
        properties.setStatus0CompensationMinAgeSeconds(60);
        properties.setStatus0CompensationBatchSize(20);
        properties.setStatus0CompensationLeaseSeconds(30);
        properties.setStatus0CompensationCooldownSeconds(120);
        properties.setStatus0CompensationMaxAttempts(5);
        properties.validate();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new VideoStatusStatus0CompensationServiceImpl(
                mapper, claimService, publisher, fingerprintService,
                redisTemplate, properties, objectMapper
        );
        when(mapper.countStatus0OverSafeWindow(anyLong(), anyInt())).thenReturn(0L);
    }

    @Test
    void disabledByDefaultDoesNotClaimOrPublish() {
        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.claimed()).isZero();
        assertThat(result.republished()).isZero();
        verify(claimService, never()).claimNext(any());
        verify(publisher, never()).publish(any(), anyString());
    }

    @Test
    void missingProcessedIsUnknownAndRejectedByDefaultEvenWithinOccurredAtWindow() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(-2L);

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.skippedNoProcessedEvidence()).isEqualTo(1);
        assertThat(result.unprovenRepublished()).isZero();
        assertThat(result.republished()).isZero();
        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(120L), eq("STATUS0_PROCESSED_EVIDENCE_MISSING")
        );
        verify(mapper, never()).markRepairRequired(anyString(), anyInt(), anyString());
    }

    @Test
    void allThreeGatesAllowUnprovenReplayButReportItSeparately() throws Exception {
        enable();
        properties.setStatus0CompensationAllowUnprovenReplay(true);
        VideoStatusConsumedEvent row = claimedRow(100L);
        VideoStatusChangedEvent event = objectMapper.readValue(
                row.getPayload(), VideoStatusChangedEvent.class);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(-2L);
        when(mapper.markStatus0CompensationRepublished(anyString(), anyString()))
                .thenReturn(1);

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.republished()).isEqualTo(1);
        assertThat(result.unprovenRepublished()).isEqualTo(1);
        assertThat(result.skippedNoProcessedEvidence()).isZero();
        verify(publisher).publish(event, "lease-1");
        verify(mapper).markStatus0CompensationRepublished(row.getEventId(), "lease-1");
        verify(mapper, never()).releaseStatus0Compensation(
                anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void allowUnprovenAloneWithoutMasterSwitchCannotRun() {
        properties.setStatus0CompensationAllowUnprovenReplay(true);
        properties.setStatus0CompensationEnabled(false);

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.claimed()).isZero();
        verify(claimService, never()).claimNext(any());
        verify(publisher, never()).publish(any(), anyString());
    }

    @Test
    void sufficientProcessedTtlRepublishesSameEventIdWithLeaseToken() throws Exception {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        VideoStatusChangedEvent event = objectMapper.readValue(
                row.getPayload(), VideoStatusChangedEvent.class);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(properties.getStatus0CompensationRequiredProcessedTtlSeconds() + 1);
        when(mapper.markStatus0CompensationRepublished(anyString(), anyString()))
                .thenReturn(1);

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.republished()).isEqualTo(1);
        assertThat(result.unprovenRepublished()).isZero();
        verify(publisher).publish(event, "lease-1");
        verify(mapper).markStatus0CompensationRepublished(row.getEventId(), "lease-1");
    }

    @Test
    void processedTtlExactlyAtSafetyMarginIsStillSendable() {
        enable();
        claimedRow(100L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(properties.getStatus0CompensationRequiredProcessedTtlSeconds());
        when(mapper.markStatus0CompensationRepublished(anyString(), anyString()))
                .thenReturn(1);

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.republished()).isEqualTo(1);
        verify(publisher).publish(any(), anyString());
    }

    @Test
    void processedTtlBelowRetryAndLeaseMarginIsRejectedEvenWhenDbAndOccurredAtAreFresh() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        long required = properties.getStatus0CompensationRequiredProcessedTtlSeconds();
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(required - 1);

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.skippedNoProcessedEvidence()).isEqualTo(1);
        assertThat(result.republished()).isZero();
        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(120L), eq("STATUS0_PROCESSED_TTL_INSUFFICIENT")
        );
    }

    @Test
    void persistentOrUnknownProcessedTtlIsRejected() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(-1L);

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.skippedNoProcessedEvidence()).isEqualTo(1);
        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(120L), eq("STATUS0_PROCESSED_TTL_UNKNOWN")
        );
    }

    @Test
    void redisTtlReadFailureReleasesWithoutPublishing() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenThrow(new IllegalStateException("redis unavailable"));

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.releasedOnFailure()).isEqualTo(1);
        assertThat(result.republished()).isZero();
        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(120L), anyString());
    }

    @Test
    void overSafeWindowIsRejectedWithoutPublish() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(604_800L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(Long.MAX_VALUE);

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.releasedOnFailure()).isEqualTo(1);
        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(120L), eq("STATUS0_OVER_SAFE_WINDOW")
        );
    }

    @Test
    void markRepublishedCasMissDoesNotReleaseOrChangeOutcome() {
        enable();
        claimedRow(100L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(properties.getStatus0CompensationRequiredProcessedTtlSeconds() + 1);
        when(mapper.markStatus0CompensationRepublished(anyString(), anyString()))
                .thenReturn(0);

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.republished()).isEqualTo(1);
        verify(mapper, never()).releaseStatus0Compensation(
                anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void claimNextScansOneCandidateAndAdvancesCursorEvenWhenCasConflicts() {
        VideoStatusStatus0CompensationClaimService realClaimService =
                new VideoStatusStatus0CompensationClaimService(mapper, properties);
        VideoStatusConsumedEvent first = row("event-1", 100L);
        first.setId(100L);
        when(mapper.selectStatus0CompensationCandidates(
                eq(60L), eq(604_800L), eq(5), any(), eq(1)))
                .thenReturn(List.of(first));
        when(mapper.claimStatus0Compensation(
                eq("event-1"), anyString(), eq(30), eq(60L), eq(604_800L), eq(5)))
                .thenReturn(0);

        VideoStatusStatus0CompensationClaimService.ClaimBatch batch =
                realClaimService.claimNext(null);

        assertThat(batch.claimed()).isEmpty();
        assertThat(batch.lastScannedId()).isEqualTo(100L);
    }

    @Test
    void claimNextUsesDbClockSecondsInsteadOfJvmLeaseUntil() {
        VideoStatusStatus0CompensationClaimService realClaimService =
                new VideoStatusStatus0CompensationClaimService(mapper, properties);
        VideoStatusConsumedEvent first = row("event-1", 100L);
        first.setId(101L);
        when(mapper.selectStatus0CompensationCandidates(
                eq(60L), eq(604_800L), eq(5), eq(99L), eq(1)))
                .thenReturn(List.of(first));
        when(mapper.claimStatus0Compensation(
                eq("event-1"), anyString(), eq(30), eq(60L), eq(604_800L), eq(5)))
                .thenReturn(1);

        VideoStatusStatus0CompensationClaimService.ClaimBatch batch =
                realClaimService.claimNext(99L);

        assertThat(batch.claimed()).extracting(VideoStatusConsumedEvent::getEventId)
                .containsExactly("event-1");
        assertThat(batch.claimed().get(0).getCompensationLeaseToken()).isNotBlank();
        assertThat(batch.lastScannedId()).isEqualTo(101L);
    }

    @Test
    void missingProcessedAndCurrentTogetherAreStillNotTreatedAsUnappliedByDefault() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        // 单测无法观测 Redis current；这里固定 processed 缺失，验证服务仍按 EVIDENCE_UNKNOWN
        // 处理，不会因为“看不到证据”就推断未应用。真实 current 删除组合由 IT 覆盖。
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(-2L);

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.republished()).isZero();
        assertThat(result.skippedNoProcessedEvidence()).isEqualTo(1);
        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(120L), eq("STATUS0_PROCESSED_EVIDENCE_MISSING"));
    }

    @Test
    void ageOneSecondBelowMaxIsAcceptedButExactMaxIsRejected() {
        enable();
        long maxAge = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        claimedRow(maxAge - 1);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(properties.getStatus0CompensationRequiredProcessedTtlSeconds() + 1);
        when(mapper.markStatus0CompensationRepublished(anyString(), anyString()))
                .thenReturn(1);

        VideoStatusStatus0CompensationService.RoundResult accepted =
                service.runOnce(null);

        assertThat(accepted.republished()).isEqualTo(1);

        setUp();
        enable();
        claimedRow(maxAge);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(Long.MAX_VALUE);

        VideoStatusStatus0CompensationService.RoundResult rejected =
                service.runOnce(null);

        assertThat(rejected.releasedOnFailure()).isEqualTo(1);
        verify(publisher, never()).publish(any(), anyString());
    }

    @Test
    void previewMinAgeBoundaryRequiresStrictlyOlderLastAttempt() {
        VideoStatusConsumedEvent row = row("event-1", 100L);
        LocalDateTime dbNow = LocalDateTime.now();
        row.setDatabaseNow(dbNow);
        row.setLastAttemptAt(dbNow.minusSeconds(60));
        when(mapper.selectStatus0CompensationPreview(null, 10)).thenReturn(List.of(row));

        VideoStatusStatus0CompensationService.PreviewResult exact =
                service.preview(10, null);
        assertThat(exact.items()).singleElement()
                .satisfies(item -> assertThat(item.withinGrace()).isFalse());

        row.setLastAttemptAt(dbNow.minusSeconds(61));
        VideoStatusStatus0CompensationService.PreviewResult older =
                service.preview(10, null);
        assertThat(older.items()).singleElement()
                .satisfies(item -> assertThat(item.withinGrace()).isTrue());
    }

    @Test
    void roundBudgetStopsBeforeNextClaimAndPreservesActualCursor() {
        enable();
        properties.setStatus0CompensationBatchSize(5);
        properties.setRoundBudgetMs(10);
        AtomicLong clock = new AtomicLong();
        when(claimService.claimNext(any())).thenReturn(
                new VideoStatusStatus0CompensationClaimService.ClaimBatch(List.of(), 10L),
                new VideoStatusStatus0CompensationClaimService.ClaimBatch(List.of(), 20L));
        VideoStatusStatus0CompensationServiceImpl budgeted = serviceWithNanos(
                () -> clock.getAndAdd(6_000_000L));

        VideoStatusStatus0CompensationService.RoundResult result =
                budgeted.runOnce(null);

        assertThat(result.claimed()).isZero();
        assertThat(result.nextCursor()).isEqualTo(10L);
        verify(claimService, org.mockito.Mockito.times(1)).claimNext(any());
    }

    @Test
    void exhaustedBudgetBeforeAnyClaimKeepsRequestedAfterId() {
        enable();
        properties.setRoundBudgetMs(10);
        AtomicLong clock = new AtomicLong();
        VideoStatusStatus0CompensationServiceImpl budgeted = serviceWithNanos(
                () -> clock.getAndAdd(11_000_000L));

        VideoStatusStatus0CompensationService.RoundResult result =
                budgeted.runOnce(99L);

        assertThat(result.claimed()).isZero();
        assertThat(result.nextCursor()).isEqualTo(99L);
        verify(claimService, never()).claimNext(any());
    }

    @Test
    void interruptedThreadStopsBeforeClaimAndPreservesCursor() {
        enable();
        Thread.currentThread().interrupt();
        try {
            VideoStatusStatus0CompensationService.RoundResult result =
                    service.runOnce(77L);

            assertThat(result.claimed()).isZero();
            assertThat(result.nextCursor()).isEqualTo(77L);
            verify(claimService, never()).claimNext(any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void masterSwitchIsRecheckedBeforeEachClaim() {
        enable();
        properties.setStatus0CompensationBatchSize(5);
        AtomicBoolean first = new AtomicBoolean(true);
        when(claimService.claimNext(any())).thenAnswer(invocation -> {
            if (first.getAndSet(false)) {
                properties.setStatus0CompensationEnabled(false);
                return new VideoStatusStatus0CompensationClaimService.ClaimBatch(
                        List.of(), 10L);
            }
            return new VideoStatusStatus0CompensationClaimService.ClaimBatch(
                    List.of(), 20L);
        });

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.claimed()).isZero();
        assertThat(result.nextCursor()).isEqualTo(10L);
        verify(claimService, org.mockito.Mockito.times(1)).claimNext(any());
    }

    @Test
    void casConflictsAdvanceCursorToLastScannedIdAndDoNotStarveTail() {
        enable();
        properties.setStatus0CompensationBatchSize(2);
        when(claimService.claimNext(any())).thenReturn(
                new VideoStatusStatus0CompensationClaimService.ClaimBatch(List.of(), 10L),
                new VideoStatusStatus0CompensationClaimService.ClaimBatch(List.of(), 20L),
                new VideoStatusStatus0CompensationClaimService.ClaimBatch(List.of(), null)
        );

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.claimed()).isZero();
        assertThat(result.nextCursor()).isEqualTo(20L);
    }

    @Test
    void previewCapsOverSafeWindowCountAndMarksLowerBound() {
        properties.setStatus0CompensationOverSafeWindowCountCap(7);
        when(mapper.countStatus0OverSafeWindow(604_800L, 7)).thenReturn(7L);

        VideoStatusStatus0CompensationService.PreviewResult result =
                service.preview(10, null);

        assertThat(result.overSafeWindow()).isEqualTo(7L);
        assertThat(result.overSafeWindowLowerBound()).isTrue();
        verify(mapper).countStatus0OverSafeWindow(604_800L, 7);
    }

    @Test
    void previewUsesDatabaseClockForGraceAndLeaseDecisions() {
        VideoStatusConsumedEvent row = row("event-1", 100L);
        LocalDateTime dbNow = LocalDateTime.now().plusDays(10);
        row.setDatabaseNow(dbNow);
        row.setLastAttemptAt(LocalDateTime.now());
        row.setCompensationLeaseUntil(dbNow.minusSeconds(1));
        row.setCompensationNextAttemptAt(dbNow.minusSeconds(1));
        when(mapper.selectStatus0CompensationPreview(null, 10)).thenReturn(List.of(row));

        VideoStatusStatus0CompensationService.PreviewResult result =
                service.preview(10, null);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.withinGrace()).isTrue();
            assertThat(item.leaseFree()).isTrue();
            assertThat(item.cooldownElapsed()).isTrue();
            // 正向口径：合法 fixture（DB 年龄 100s、occurredAt 100s 前、attempts 1<5、无租约/冷却）
            // 下读写两侧都放行，因而不会报任何拒绝原因码。
            assertThat(item.withinSafeWindow()).isTrue();
            assertThat(item.eligible()).isTrue();
            assertThat(item.refusalCode()).isNull();
        });
    }

    @Test
    void malformedPayloadIsNotReportedAsSafeAndReleasesWithoutPublishOrRedisRead() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        row.setPayload("{not-a-json-payload}");
        when(mapper.selectStatus0CompensationPreview(null, 10)).thenReturn(List.of(row));

        VideoStatusStatus0CompensationService.PreviewItem item =
                service.preview(10, null).items().get(0);

        assertThat(item.withinSafeWindow()).isFalse();
        assertThat(item.eligible()).isFalse();
        assertThat(item.refusalCode()).isEqualTo("STATUS0_PAYLOAD_INVALID");

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.releasedOnFailure()).isEqualTo(1);
        assertThat(result.republished()).isZero();
        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(properties.getStatus0CompensationCooldownSeconds()),
                eq("STATUS0_PAYLOAD_INVALID")
        );
        // 内容无法验证时既不读 processed，也不回显原始异常文本。
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void invalidEventIsNotReportedAsSafeAndReleasesWithoutPublishOrRedisRead() throws Exception {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        VideoStatusChangedEvent invalid = new VideoStatusChangedEvent(
                row.getEventId(), 1, VideoStatusEventType.LIKE, 1L, 1.5,
                LocalDateTime.now().minusSeconds(100), 1, "trace-1"
        );
        row.setPayload(objectMapper.writeValueAsString(invalid));
        when(mapper.selectStatus0CompensationPreview(null, 10)).thenReturn(List.of(row));

        VideoStatusStatus0CompensationService.PreviewItem item =
                service.preview(10, null).items().get(0);

        assertThat(item.withinSafeWindow()).isFalse();
        assertThat(item.eligible()).isFalse();
        assertThat(item.refusalCode()).isEqualTo("STATUS0_EVENT_INVALID");

        service.runOnce(null);

        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(properties.getStatus0CompensationCooldownSeconds()),
                eq("STATUS0_EVENT_INVALID")
        );
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void ledgerPayloadMismatchIsNotReportedAsSafeAndReleasesWithoutPublish() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        // 账本 delta 与 payload 不一致：属于内容判定失败，同样无法证明 occurredAt。
        row.setDelta(2L);
        when(mapper.selectStatus0CompensationPreview(null, 10)).thenReturn(List.of(row));

        VideoStatusStatus0CompensationService.PreviewItem item =
                service.preview(10, null).items().get(0);

        assertThat(item.withinSafeWindow()).isFalse();
        assertThat(item.eligible()).isFalse();
        assertThat(item.refusalCode()).isEqualTo("STATUS0_EVENT_MISMATCH");

        service.runOnce(null);

        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(properties.getStatus0CompensationCooldownSeconds()),
                eq("STATUS0_EVENT_MISMATCH")
        );
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void fingerprintMismatchReleasesWithoutPublishOrRedisRead() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        when(fingerprintService.hash(any())).thenReturn("hash-from-recomputed-payload");

        VideoStatusStatus0CompensationService.RoundResult result = service.runOnce(null);

        assertThat(result.releasedOnFailure()).isEqualTo(1);
        assertThat(result.republished()).isZero();
        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(properties.getStatus0CompensationCooldownSeconds()),
                eq("STATUS0_FINGERPRINT_MISMATCH")
        );
        // 指纹不符在读取 processed 证据之前就拒绝。
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void status0LeaseMustCoverPublishConfirmWithMargin() {
        properties.setStatus0CompensationLeaseSeconds(9);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status0-compensation-lease-seconds");
    }

    @Test
    void markRepublishedDoesNotConsumeConsumerRetryBudget() throws Exception {
        String xml;
        try (var in = getClass().getResourceAsStream(
                "/com/feibijiubi/backend/mapper/VideoStatusConsumedEventMapper.xml")) {
            assertThat(in).isNotNull();
            xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int start = xml.indexOf("<update id=\"markStatus0CompensationRepublished\">");
        int end = xml.indexOf("</update>", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        String markSql = xml.substring(start, end);
        assertThat(markSql).doesNotContain("consumer_retry_count");
        assertThat(markSql).contains("compensation_lease_token");

        int claimStart = xml.indexOf("<update id=\"claimStatus0Compensation\">");
        String claimSql = xml.substring(claimStart, xml.indexOf("</update>", claimStart));
        assertThat(claimSql)
                .contains("compensation_lease_until = DATE_ADD(NOW(3)")
                .contains("last_attempt_at &lt; DATE_SUB(NOW(3)")
                .doesNotContain("#{leaseUntil}");

        int releaseStart = xml.indexOf("<update id=\"releaseStatus0Compensation\">");
        String releaseSql = xml.substring(
                releaseStart, xml.indexOf("</update>", releaseStart));
        assertThat(releaseSql)
                .contains("compensation_next_attempt_at = DATE_ADD(NOW(3)")
                .doesNotContain("#{nextAttemptAt}");

        int countStart = xml.indexOf("<select id=\"countStatus0OverSafeWindow\"");
        String countSql = xml.substring(countStart, xml.indexOf("</select>", countStart));
        assertThat(countSql).contains("LIMIT #{cap}");
    }

    @Test
    void releaseAndMarkRepublishedAreLeaseTokenCasOperations() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(100L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(-2L);

        service.runOnce(null);

        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(properties.getStatus0CompensationCooldownSeconds()), anyString());
        verify(mapper, never()).markStatus0CompensationRepublished(
                anyString(), anyString());
    }

    @Test
    void previewMarksEvidenceUnknownAndShowsGatingDecision() {
        VideoStatusConsumedEvent row = row("event-1", 100L);
        row.setCompensationAttempts(5);
        when(mapper.selectStatus0CompensationPreview(null, 10)).thenReturn(List.of(row));

        VideoStatusStatus0CompensationService.PreviewResult disabled =
                service.preview(10, null);
        assertThat(disabled.items()).singleElement().satisfies(item -> {
            assertThat(item.evidenceStatus())
                    .isEqualTo(VideoStatusStatus0CompensationService.EVIDENCE_UNKNOWN);
            assertThat(item.decisionGate())
                    .isEqualTo(VideoStatusStatus0CompensationService.GATE_MASTER_SWITCH_OFF);
            assertThat(item.attemptsRemaining()).isFalse();
            assertThat(item.eligible()).isFalse();
        });

        enable();
        properties.setStatus0CompensationAllowUnprovenReplay(true);
        VideoStatusStatus0CompensationService.PreviewResult enabled =
                service.preview(10, null);
        assertThat(enabled.items()).singleElement().satisfies(item -> {
            assertThat(item.evidenceStatus())
                    .isEqualTo(VideoStatusStatus0CompensationService.EVIDENCE_UNKNOWN);
            assertThat(item.decisionGate())
                    .isEqualTo(VideoStatusStatus0CompensationService.GATE_ALLOW_UNPROVEN_REPLAY);
        });
    }

    @Test
    void enablingWithoutIntegrityConfirmationFailsStartupValidation() {
        properties.setStatus0CompensationEnabled(true);
        properties.setStatus0CompensationIntegrityConfirmed(false);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status0-compensation-integrity-confirmed");
    }

    @Test
    void allowUnprovenCanRemainConfiguredWhileMasterSwitchIsOffForIncidentShutdown() {
        properties.setStatus0CompensationAllowUnprovenReplay(true);
        properties.setStatus0CompensationEnabled(false);

        properties.validate();
        assertThat(properties.isStatus0CompensationAllowUnprovenReplay()).isTrue();
    }

    @Test
    void minAgeMustStayInsideSafeWindowWhichIsBelowProcessedTtl() {
        properties.setStatus0CompensationMinAgeSeconds(604_800);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status0-compensation-min-age-seconds");
    }

    @Test
    void retryMarginUsesMaxConsumerRetriesAndLeaseOrConfirmFloor() {
        assertThat(properties.getStatus0CompensationRequiredProcessedTtlSeconds())
                .isEqualTo(10 + 30 + 120 + 120 + 120 + 30);

        properties.setConsumerMaxRetries(1);
        assertThat(properties.getStatus0CompensationRequiredProcessedTtlSeconds())
                .isEqualTo(10 + 30);

        properties.setConsumerMaxRetries(2);
        properties.setStatus0CompensationLeaseSeconds(20);
        properties.setPublishConfirmTimeoutSeconds(50);
        assertThat(properties.getStatus0CompensationRequiredProcessedTtlSeconds())
                .isEqualTo(10 + 30 + 50);
    }

    @Test
    void occurredAtBeyondFixedThirtyDayBoundIsReleasedUnderWidenedWindow() {
        useWidenedWindow();
        enable();
        VideoStatusConsumedEvent row = claimedRow(Duration.ofDays(31).toSeconds());

        VideoStatusStatus0CompensationService.RoundResult result =
                service.runOnce(null);

        assertThat(result.releasedOnFailure()).isEqualTo(1);
        assertThat(result.republished()).isZero();
        verify(publisher, never()).publish(any(), anyString());
        verify(mapper, never()).markStatus0CompensationRepublished(
                anyString(), anyString());
        // 固定上界判定必须早于 Redis 证据检查：拒绝路径不读 processed。
        verifyNoInteractions(redisTemplate);
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(properties.getStatus0CompensationCooldownSeconds()),
                eq(VideoStatusEventAgeGuard.REFUSAL_CODE)
        );
    }

    @Test
    void previewAndRunOnceReportTheSameRefusalCodeBeyondThirtyDays() {
        useWidenedWindow();
        enable();
        VideoStatusConsumedEvent row = claimedRow(Duration.ofDays(31).toSeconds());
        when(mapper.selectStatus0CompensationPreview(null, 10)).thenReturn(List.of(row));

        VideoStatusStatus0CompensationService.PreviewItem item =
                service.preview(10, null).items().get(0);

        assertThat(item.refusalCode()).isEqualTo(VideoStatusEventAgeGuard.REFUSAL_CODE);
        assertThat(item.eligible()).isFalse();
        assertThat(item.withinSafeWindow()).isFalse();

        service.runOnce(null);

        verify(mapper).releaseStatus0Compensation(
                row.getEventId(), "lease-1",
                properties.getStatus0CompensationCooldownSeconds(),
                item.refusalCode()
        );
        verify(publisher, never()).publish(any(), anyString());
    }

    @Test
    void occurredAtInsideFixedBoundIsStillRepublishedUnderWidenedWindow() {
        useWidenedWindow();
        enable();
        claimedRow(Duration.ofDays(20).toSeconds());
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenReturn(properties.getStatus0CompensationRequiredProcessedTtlSeconds());
        when(mapper.markStatus0CompensationRepublished(anyString(), anyString()))
                .thenReturn(1);

        VideoStatusStatus0CompensationService.RoundResult result =
                service.runOnce(null);

        assertThat(result.republished()).isEqualTo(1);
        verify(publisher).publish(any(), eq("lease-1"));
    }

    @Test
    void defaultSevenDayWindowStillReportsOriginalOverSafeWindowReason() {
        enable();
        VideoStatusConsumedEvent row = claimedRow(Duration.ofDays(31).toSeconds());

        service.runOnce(null);

        verify(publisher, never()).publish(any(), anyString());
        verify(mapper).releaseStatus0Compensation(
                eq(row.getEventId()), eq("lease-1"),
                eq(properties.getStatus0CompensationCooldownSeconds()),
                eq("STATUS0_OVER_SAFE_WINDOW")
        );
    }

    private VideoStatusStatus0CompensationServiceImpl serviceWithNanos(
            LongSupplier nanoTime) {
        return new VideoStatusStatus0CompensationServiceImpl(
                mapper, claimService, publisher, fingerprintService,
                redisTemplate, properties, objectMapper, nanoTime);
    }

    private void enable() {
        properties.setStatus0CompensationEnabled(true);
        properties.setStatus0CompensationIntegrityConfirmed(true);
    }

    /**
     * 合法但把可配置安全窗口调到 30 天以上的配置：启动校验只要求窗口小于
     * {@code redisEventTtlDays * 86400}，所以 60 天 TTL + 45 天窗口可以通过 {@code validate()}。
     * 实现类每次调用都读同一个 {@code properties} 引用，所以不需要重建服务。
     */
    private void useWidenedWindow() {
        properties.setRedisEventTtlDays(60);
        properties.setConsumerRecoveryAutoReplayMaxAgeSeconds(
                (int) Duration.ofDays(45).toSeconds());
        properties.validate();
    }

    private VideoStatusConsumedEvent claimedRow(long ageSeconds) {
        VideoStatusConsumedEvent row = row("event-1", ageSeconds);
        when(claimService.claimNext(any())).thenReturn(
                new VideoStatusStatus0CompensationClaimService.ClaimBatch(
                        List.of(row), row.getId()),
                new VideoStatusStatus0CompensationClaimService.ClaimBatch(List.of(), null));
        when(fingerprintService.hash(any())).thenReturn(row.getPayloadHash());
        return row;
    }

    private VideoStatusConsumedEvent row(String eventId, long ageSeconds) {
        LocalDateTime occurredAt = LocalDateTime.now().minusSeconds(ageSeconds);
        VideoStatusChangedEvent event = new VideoStatusChangedEvent(
                eventId, 1, VideoStatusEventType.LIKE, 1L, 1.5,
                occurredAt, 2, "trace-1"
        );
        VideoStatusConsumedEvent row = new VideoStatusConsumedEvent();
        row.setId(10L);
        row.setEventId(event.eventId());
        row.setVid(event.vid());
        row.setEventType(event.type().name());
        row.setDelta(event.delta());
        row.setPayloadHash("hash-1");
        row.setProcessStatus(0);
        row.setConsumedAgeSeconds(ageSeconds);
        row.setDatabaseNow(LocalDateTime.now());
        row.setLastAttemptAt(LocalDateTime.now().minusSeconds(120));
        row.setCompensationAttempts(1);
        row.setCompensationLeaseToken("lease-1");
        try {
            row.setPayload(objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return row;
    }
}
