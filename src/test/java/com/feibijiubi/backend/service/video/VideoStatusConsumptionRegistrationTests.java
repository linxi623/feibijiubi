package com.feibijiubi.backend.service.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.NonRetryableMessageException;
import com.feibijiubi.backend.common.RepairRequiredMessageException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.enums.RegistrationResult;
import com.feibijiubi.backend.enums.VideoStatusConsumeProcessStatus;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusConsumptionServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusEventAgeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T14-S1：消费登记前的 30 天绝对年龄拒绝。
 *
 * <p>覆盖三件事：超龄事件在任何 mapper 交互之前就被拒（即使账本里已有 status1/2 行，
 * 也不查询、不修改）；恰好 30 天仍按正常路径登记；既有 7 天重投守卫在 30 天以内完全不变。
 * 固定时钟，不连任何基础设施。</p>
 */
class VideoStatusConsumptionRegistrationTests {

    private static final Instant FIXED_INSTANT =
            Instant.parse("2026-10-24T03:04:05.123456789Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(FIXED_INSTANT, ZoneId.systemDefault());
    private static final LocalDateTime NOW = LocalDateTime.now(FIXED_CLOCK);
    private static final String PAYLOAD_HASH = "a".repeat(64);

    private ObjectMapper objectMapper;
    private VideoStatusConsumedEventMapper consumedEventMapper;
    private VideoStatusConsumptionServiceImpl consumptionService;

    @BeforeEach
    void setUp() {
        objectMapper = mock(ObjectMapper.class);
        consumedEventMapper = mock(VideoStatusConsumedEventMapper.class);
        consumptionService = new VideoStatusConsumptionServiceImpl(
                objectMapper,
                consumedEventMapper,
                new VideoStatusProperties(),
                FIXED_CLOCK
        );
    }

    @Test
    void staleEventIsRejectedBeforeAnyMapperInteraction() {
        VideoStatusChangedEvent event = event(NOW.minusDays(31));

        NonRetryableMessageException error = assertThrows(
                NonRetryableMessageException.class,
                () -> consumptionService.register(event, PAYLOAD_HASH)
        );

        assertEquals(VideoStatusEventAgeGuard.EXPIRED_REASON, error.getMessage());
        assertFalse(error.getMessage().contains(event.eventId()));
        verifyNoInteractions(consumedEventMapper);
    }

    @Test
    void staleEventWithExistingLedgerRowIsNeitherReadNorChanged() {
        // 该 eventId 在账本里本应已经有 status1（REDIS_APPLIED_PENDING_FLUSH）行：
        // 旧行为会走 handleDuplicate 静默返回 REDIS_ALREADY_APPLIED，现在必须被年龄守卫先拒。
        // 这里不为“账本行存在”打 stub：assertion 是“零 mapper 交互”，任何查询都会让
        // verifyNoInteractions 失败，而未被调用的 stub 只会是死代码。
        VideoStatusChangedEvent event = event(NOW.minusDays(31));

        assertThrows(
                NonRetryableMessageException.class,
                () -> consumptionService.register(event, PAYLOAD_HASH)
        );

        verifyNoInteractions(consumedEventMapper);
    }

    @Test
    void staleEventWithFlushedLedgerRowIsRejectedInsteadOfSilentlyAbsorbed() {
        VideoStatusChangedEvent event = event(NOW.minusDays(31));
        when(consumedEventMapper.selectByEventIdForUpdate(event.eventId()))
                .thenReturn(existing(event, VideoStatusConsumeProcessStatus
                        .FLUSHED.getCode()));

        assertThrows(
                NonRetryableMessageException.class,
                () -> consumptionService.register(event, PAYLOAD_HASH)
        );

        verify(consumedEventMapper, never()).insertReceived(any());
        verify(consumedEventMapper, never()).selectByEventIdForUpdate(any());
    }

    @Test
    void eventExactlyThirtyDaysOldIsRegisteredNormally() {
        VideoStatusChangedEvent event = event(NOW.minusDays(30));
        when(consumedEventMapper.insertReceived(any())).thenReturn(1);

        RegistrationResult result = consumptionService.register(
                event,
                PAYLOAD_HASH
        );

        assertEquals(RegistrationResult.NEEDS_REDIS_APPLY, result);
        verify(consumedEventMapper).insertReceived(any());
    }

    @Test
    void eventInsideWindowRegistersNormally() {
        VideoStatusChangedEvent event = event(NOW.minusDays(29));
        when(consumedEventMapper.insertReceived(any())).thenReturn(1);

        assertEquals(
                RegistrationResult.NEEDS_REDIS_APPLY,
                consumptionService.register(event, PAYLOAD_HASH)
        );
        verify(consumedEventMapper).insertReceived(any());
        verify(consumedEventMapper, never()).selectByEventIdForUpdate(any());
    }

    @Test
    void missingOccurredAtIsRejectedByEventValidationBeforeAnyMapperInteraction() {
        // 审核 P2：文档声称“缺 occurredAt 仍由既有 validate() 拒绝”，但原本没有用例钉住它。
        VideoStatusChangedEvent event = event(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> consumptionService.register(event, PAYLOAD_HASH)
        );

        verifyNoInteractions(consumedEventMapper);
    }

    @Test
    void existingSevenDayReplayGuardIsUnchangedInsideThirtyDays() {
        // occurredAt 在 30 天窗口内，因此不会触发新守卫；重复/重投路径仍由既有的
        // consumed_at + consumer-recovery-auto-replay-max-age-seconds（默认 7 天）判定。
        VideoStatusChangedEvent event = event(NOW.minusDays(29));
        when(consumedEventMapper.insertReceived(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        VideoStatusConsumedEvent existing = existing(
                event,
                VideoStatusConsumeProcessStatus.RECEIVED.getCode()
        );
        // handleReceived 用的是活的 LocalDateTime.now()（不是注入时钟），所以 consumed_at
        // 也必须从同一个活时钟派生，否则这条用例会依赖机器日期与事件固定时钟的相对位置。
        existing.setConsumedAt(LocalDateTime.now().minusDays(8));
        when(consumedEventMapper.selectByEventIdForUpdate(event.eventId()))
                .thenReturn(existing);
        when(consumedEventMapper.markRepairRequired(
                anyString(),
                anyInt(),
                anyString()
        )).thenReturn(1);

        assertThrows(
                RepairRequiredMessageException.class,
                () -> consumptionService.register(event, PAYLOAD_HASH)
        );

        verify(consumedEventMapper).markRepairRequired(
                eq(event.eventId()),
                eq(VideoStatusConsumeProcessStatus.RECEIVED.getCode()),
                anyString()
        );
        verify(consumedEventMapper, never()).touchReceivedAttempt(any());
    }

    private static VideoStatusChangedEvent event(LocalDateTime occurredAt) {
        long delta = 1L;
        return new VideoStatusChangedEvent(
                "t14-s1-event-1",
                42,
                VideoStatusEventType.PLAY,
                delta,
                VideoStatusEventType.PLAY.calculateHotScore(delta),
                occurredAt,
                2,
                null
        );
    }

    private static VideoStatusConsumedEvent existing(
            VideoStatusChangedEvent event,
            int processStatus
    ) {
        VideoStatusConsumedEvent existing = new VideoStatusConsumedEvent();
        existing.setEventId(event.eventId());
        existing.setVid(event.vid());
        existing.setEventType(event.type().name());
        existing.setDelta(event.delta());
        existing.setPayloadHash(PAYLOAD_HASH);
        existing.setProcessStatus(processStatus);
        existing.setConsumedAt(event.occurredAt());
        return existing;
    }
}
