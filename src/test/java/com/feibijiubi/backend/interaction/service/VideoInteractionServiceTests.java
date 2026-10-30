package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.interaction.entity.VideoInteractionRequest;
import com.feibijiubi.backend.interaction.entity.VideoInteractionWindow;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.interaction.mapper.VideoInteractionMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * T15 第一阶段 DB 核心的纯单元测试：不连 MySQL / Redis / MQ，不启动 Spring。
 *
 * <p>覆盖幂等重放、形状冲突、首次计数、窗口抑制、异步/同步两条计数路径、计数失败与终态写入失败
 * 的异常传播，以及“抑制不推进窗口”“重放不重新判定”等分支。</p>
 *
 * <p><b>本类不声称证明事务原子性</b>：这里的事务管理器是 mock，只能证明
 * “异常会穿过 TransactionTemplate 并触发 rollback、且后续写操作没有被执行”。
 * 真实的“请求占位 + 窗口推进 + 计数 + 终态同生共死”由
 * {@code VideoInteractionIsolatedTests} / {@code VideoInteractionSyncIsolatedTests}
 * 在隔离 MySQL 上验证。</p>
 */
class VideoInteractionServiceTests {

    private static final String REQUEST_KEY = "req-2026-10-24-0001";
    private static final String ACTOR_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final int VID = 91;

    private VideoInteractionMapper interactionMapper;
    private VideoStatusMapper videoStatusMapper;
    private VideoStatusService videoStatusService;
    private VideoStatusProperties properties;
    private PlatformTransactionManager transactionManager;
    private VideoInteractionServiceImpl service;

    @BeforeEach
    void setUp() {
        interactionMapper = mock(VideoInteractionMapper.class);
        videoStatusMapper = mock(VideoStatusMapper.class);
        videoStatusService = mock(VideoStatusService.class);
        properties = new VideoStatusProperties();
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new VideoInteractionServiceImpl(
                interactionMapper, videoStatusMapper, videoStatusService,
                properties, transactionManager);
    }

    // ---------------------------------------------------------------- first request

    @Test
    void firstRequestOnNullWindowCountsAndCreatesOneEvent() {
        properties.setAsyncEnabled(true);
        givenPlaceholderRequestInserted();
        givenWindow(null);
        when(interactionMapper.advanceWindowIfDue(anyLong(), eq(30))).thenReturn(1);
        when(videoStatusService.createEvent(VID, VideoStatusEventType.PLAY, 1))
                .thenReturn(event("evt-play-1"));
        when(interactionMapper.completeRequest(anyLong(), anyInt(), any())).thenReturn(1);

        VideoInteractionResult result = service.record(playCommand());

        assertEquals(VideoInteractionService.STATE_COUNTED, result.state());
        assertTrue(result.counted());
        assertFalse(result.duplicate());
        assertEquals("evt-play-1", result.eventId());
        assertEquals(REQUEST_KEY, result.requestKey());
        verify(interactionMapper).completeRequest(500L, 1, "evt-play-1");
    }

    @Test
    void windowSuppressionDoesNotCountAndDoesNotAdvanceWindow() {
        properties.setAsyncEnabled(true);
        givenPlaceholderRequestInserted();
        // 窗口行已存在且 5 分钟前刚计过：守卫更新 0 行 = 抑制，且没有写任何列。
        givenWindow(LocalDateTime.now().minusMinutes(5));
        when(interactionMapper.advanceWindowIfDue(anyLong(), eq(30))).thenReturn(0);
        when(interactionMapper.completeRequest(anyLong(), anyInt(), any())).thenReturn(1);

        VideoInteractionResult result = service.record(playCommand());

        assertEquals(VideoInteractionService.STATE_SUPPRESSED, result.state());
        assertFalse(result.counted());
        assertNull(result.eventId());
        verify(videoStatusService, never()).createEvent(any(), any(), anyLong());
        verify(interactionMapper).completeRequest(500L, 2, null);
    }

    @Test
    void placeholderUsesPendingStateAndNeverInitialisesWindowTime() {
        properties.setAsyncEnabled(true);
        givenPlaceholderRequestInserted();
        givenWindow(null);
        when(interactionMapper.advanceWindowIfDue(anyLong(), eq(30))).thenReturn(1);
        when(videoStatusService.createEvent(any(), any(), anyLong()))
                .thenReturn(event("evt-play-1"));
        when(interactionMapper.completeRequest(anyLong(), anyInt(), any())).thenReturn(1);

        service.record(playCommand());

        // 请求占位必须是 PENDING；窗口行只能“保证存在”，不得由服务层写入初始时间。
        verify(interactionMapper).insertRequest(org.mockito.ArgumentMatchers.argThat(request ->
                request.getState() == 0
                        && REQUEST_KEY.equals(request.getRequestKey())
                        && ACTOR_HASH.equals(request.getActorHash())
                        && "USER".equals(request.getActorType())
                        && "PLAY".equals(request.getAction())
                        && request.getVid() == VID));
        verify(interactionMapper).ensureWindowRow("USER", ACTOR_HASH, VID, "PLAY");
        // 只有这一处会写 last_counted_at，窗口长度必须是已批准的 30 分钟。
        verify(interactionMapper).advanceWindowIfDue(77L, 30);
    }

    // --------------------------------------------------------------------- replay

    @Test
    void replayOfCountedRequestReturnsStoredResultWithoutCountingAgain() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY))
                .thenReturn(existing(1, "evt-play-1"));

        VideoInteractionResult result = service.record(playCommand());

        assertEquals(VideoInteractionService.STATE_COUNTED, result.state());
        assertTrue(result.counted());
        assertTrue(result.duplicate());
        assertEquals("evt-play-1", result.eventId());
        verifyNoInteractions(transactionManager);
        verify(interactionMapper, never()).insertRequest(any());
        verify(videoStatusService, never()).createEvent(any(), any(), anyLong());
    }

    @Test
    void replayOfSuppressedRequestStaysSuppressedEvenAfterWindowWouldExpire() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY))
                .thenReturn(existing(2, null));

        VideoInteractionResult result = service.record(playCommand());

        assertEquals(VideoInteractionService.STATE_SUPPRESSED, result.state());
        assertFalse(result.counted());
        assertTrue(result.duplicate());
        assertNull(result.eventId());
        // 重放不做窗口判定：旧请求永远重放“被抑制”这个结果，不会因为过了 30 分钟重新计数。
        verify(interactionMapper, never()).advanceWindowIfDue(anyLong(), anyInt());
    }

    @Test
    void replayWithDifferentVidIsShapeConflict() {
        VideoInteractionRequest stored = existing(1, "evt-play-1");
        stored.setVid(VID + 1);
        when(interactionMapper.selectRequestByKey(REQUEST_KEY)).thenReturn(stored);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("不同形状"));
    }

    @Test
    void replayWithDifferentActionIsShapeConflict() {
        VideoInteractionRequest stored = existing(1, "evt-play-1");
        stored.setAction("SHARE");
        when(interactionMapper.selectRequestByKey(REQUEST_KEY)).thenReturn(stored);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(409, error.getCode());
    }

    @Test
    void replayWithDifferentActorHashIsShapeConflict() {
        VideoInteractionRequest stored = existing(1, "evt-play-1");
        stored.setActorHash("f".repeat(64));
        when(interactionMapper.selectRequestByKey(REQUEST_KEY)).thenReturn(stored);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(409, error.getCode());
        // 错误信息不回显已存储的 actorHash。
        assertFalse(error.getMessage().contains("f".repeat(64)));
    }

    @Test
    void replayOfCommittedPendingStateIsRejectedInsteadOfGuessing() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY)).thenReturn(existing(0, null));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(409, error.getCode());
    }

    @Test
    void replayOfUnknownStateIsServerError() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY)).thenReturn(existing(7, null));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(500, error.getCode());
    }

    // ------------------------------------------------------- concurrent requestKey

    @Test
    void duplicateKeyRollsBackAttemptAndReplaysWinnerResult() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY))
                .thenReturn(null, existing(1, "evt-winner"));
        when(interactionMapper.insertRequest(any())).thenThrow(new DuplicateKeyException("uk"));

        VideoInteractionResult result = service.record(playCommand());

        assertTrue(result.duplicate());
        assertTrue(result.counted());
        assertEquals("evt-winner", result.eventId());
        // 本次尝试事务必须整体回滚，不能在已 rollback-only 的事务里继续写。
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(interactionMapper, never()).ensureWindowRow(anyString(), anyString(),
                anyInt(), anyString());
        verify(videoStatusService, never()).createEvent(any(), any(), anyLong());
    }

    @Test
    void duplicateKeyWithoutReadableWinnerIsServerError() {
        // selectRequestByKey 默认返回 null：幂等读与冲突后重读都读不到赢家。
        when(interactionMapper.insertRequest(any())).thenThrow(new DuplicateKeyException("uk"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(500, error.getCode());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void outboxUniqueConflictWithoutWinnerIsServerErrorAndRollsBack() {
        givenPlaceholderRequestInserted();
        givenWindow(null);
        when(interactionMapper.advanceWindowIfDue(anyLong(), eq(30))).thenReturn(1);
        when(videoStatusService.createEvent(any(), any(), anyLong()))
                .thenThrow(new DuplicateKeyException("outbox unique conflict"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(500, error.getCode());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(interactionMapper, never()).completeRequest(anyLong(), anyInt(), any());
    }

    // ------------------------------------------------------------------ sync path

    @Test
    void syncPathUsesGuardedUpdateAndCreatesNoEvent() {
        properties.setAsyncEnabled(false);
        givenPlaceholderRequestInserted();
        givenWindow(null);
        when(interactionMapper.advanceWindowIfDue(anyLong(), eq(30))).thenReturn(1);
        when(videoStatusMapper.increasePlayTimes(VID)).thenReturn(1);
        when(interactionMapper.completeRequest(anyLong(), anyInt(), any())).thenReturn(1);

        VideoInteractionResult result = service.record(playCommand());

        assertTrue(result.counted());
        assertNull(result.eventId());
        verify(videoStatusMapper).increasePlayTimes(VID);
        verify(videoStatusService, never()).createEvent(any(), any(), anyLong());
    }

    @Test
    void syncShareUsesShareGuardedUpdate() {
        properties.setAsyncEnabled(false);
        givenPlaceholderRequestInserted();
        givenWindow(null);
        when(interactionMapper.advanceWindowIfDue(anyLong(), eq(30))).thenReturn(1);
        when(videoStatusMapper.increaseShareTimes(VID)).thenReturn(1);
        when(interactionMapper.completeRequest(anyLong(), anyInt(), any())).thenReturn(1);

        VideoInteractionResult result = service.record(shareCommand());

        assertTrue(result.counted());
        verify(videoStatusMapper).increaseShareTimes(VID);
        verify(videoStatusMapper, never()).increasePlayTimes(any());
    }

    @Test
    void syncCountFailurePropagatesAndRollsBackWithoutCompletingRequest() {
        properties.setAsyncEnabled(false);
        givenPlaceholderRequestInserted();
        givenWindow(null);
        when(interactionMapper.advanceWindowIfDue(anyLong(), eq(30))).thenReturn(1);
        // video_status 缺行或已达 Long.MAX：守卫更新 0 行。
        when(videoStatusMapper.increasePlayTimes(VID)).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(500, error.getCode());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(interactionMapper, never()).completeRequest(anyLong(), anyInt(), any());
    }

    @Test
    void eventCreationFailurePropagatesAndRollsBackWithoutCompletingRequest() {
        properties.setAsyncEnabled(true);
        givenPlaceholderRequestInserted();
        givenWindow(null);
        when(interactionMapper.advanceWindowIfDue(anyLong(), eq(30))).thenReturn(1);
        when(videoStatusService.createEvent(any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("outbox insert failed"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.record(playCommand()));

        assertEquals("outbox insert failed", error.getMessage());
        verify(transactionManager).rollback(any());
        verify(interactionMapper, never()).completeRequest(anyLong(), anyInt(), any());
    }

    @Test
    void eventWithoutEventIdFailsInsteadOfWritingCountedWithoutReference() {
        properties.setAsyncEnabled(true);
        givenPlaceholderRequestInserted();
        givenWindow(null);
        when(interactionMapper.advanceWindowIfDue(anyLong(), eq(30))).thenReturn(1);
        when(videoStatusService.createEvent(any(), any(), anyLong())).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(500, error.getCode());
        verify(transactionManager).rollback(any());
    }

    // ------------------------------------------------------------- write anomalies

    @Test
    void placeholderInsertWithoutRowsFails() {
        when(interactionMapper.insertRequest(any())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(500, error.getCode());
        verify(interactionMapper, never()).ensureWindowRow(anyString(), anyString(),
                anyInt(), anyString());
        verify(transactionManager).rollback(any());
    }

    @Test
    void missingWindowRowAfterEnsureFails() {
        givenPlaceholderRequestInserted();
        when(interactionMapper.selectWindowForUpdate(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(500, error.getCode());
        verify(interactionMapper, never()).advanceWindowIfDue(anyLong(), anyInt());
    }

    @Test
    void completeRequestWithoutRowsFails() {
        properties.setAsyncEnabled(true);
        givenPlaceholderRequestInserted();
        givenWindow(null);
        when(interactionMapper.advanceWindowIfDue(anyLong(), eq(30))).thenReturn(1);
        when(videoStatusService.createEvent(any(), any(), anyLong()))
                .thenReturn(event("evt-play-1"));
        when(interactionMapper.completeRequest(anyLong(), anyInt(), any())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(playCommand()));

        assertEquals(500, error.getCode());
        verify(transactionManager).rollback(any());
    }

    // ---------------------------------------------------------------- validation

    @Test
    void invalidRequestKeyIsRejectedBeforeAnyDatabaseWork() {
        for (String bad : new String[]{"", " ", "a".repeat(65), "req key", "req/key", "req\nkey"}) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.record(new VideoInteractionCommand(
                            VideoInteractionActorType.USER, ACTOR_HASH, VID,
                            VideoInteractionAction.PLAY, bad)));
            assertEquals(400, error.getCode(), "requestKey 应被拒绝: " + bad);
        }
        verifyNoInteractions(interactionMapper);
    }

    @Test
    void nonCanonicalActorHashIsRejected() {
        for (String bad : new String[]{null, "", ACTOR_HASH.toUpperCase(),
                ACTOR_HASH.substring(1), ACTOR_HASH + "a", "z".repeat(64)}) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.record(new VideoInteractionCommand(
                            VideoInteractionActorType.ANONYMOUS, bad, VID,
                            VideoInteractionAction.PLAY, REQUEST_KEY)));
            assertEquals(400, error.getCode(), "actorHash 应被拒绝: " + bad);
        }
        verifyNoInteractions(interactionMapper);
    }

    @Test
    void nullActorTypeNullActionAndNonPositiveVidAreRejected() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.record(
                new VideoInteractionCommand(null, ACTOR_HASH, VID,
                        VideoInteractionAction.PLAY, REQUEST_KEY))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, ACTOR_HASH, VID,
                        null, REQUEST_KEY))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, ACTOR_HASH, 0,
                        VideoInteractionAction.PLAY, REQUEST_KEY))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, ACTOR_HASH, null,
                        VideoInteractionAction.PLAY, REQUEST_KEY))).getCode());
        verifyNoInteractions(interactionMapper);
    }

    @Test
    void nullCommandIsRejected() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.record(null)).getCode());
        verifyNoInteractions(interactionMapper);
    }

    @Test
    void recordRefusesToRunInsideAnEnclosingTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.record(playCommand()));
            assertTrue(error.getMessage().contains("不能在已有事务中调用"));
            verifyNoInteractions(interactionMapper);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // ------------------------------------------------------- T15-2 lookupReplay

    @Test
    void lookupReplayReturnsEmptyForUnknownKeyWithoutAnyWrite() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY)).thenReturn(null);

        assertTrue(service.lookupReplay(playCommand()).isEmpty());

        // 只读：不得占位、不得碰窗口、不得开事务。
        verify(interactionMapper).selectRequestByKey(REQUEST_KEY);
        verifyNoMoreInteractions(interactionMapper);
        verifyNoInteractions(transactionManager);
    }

    @Test
    void lookupReplayReturnsStoredCountedResultAsDuplicate() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY))
                .thenReturn(existing(1, "evt-1"));

        VideoInteractionResult replay = service.lookupReplay(playCommand()).orElseThrow();

        assertTrue(replay.counted());
        assertTrue(replay.duplicate());
        assertEquals("evt-1", replay.eventId());
        verifyNoInteractions(transactionManager);
    }

    @Test
    void lookupReplayReturnsStoredSuppressedResultAfterWindowExpired() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY))
                .thenReturn(existing(2, null));

        VideoInteractionResult replay = service.lookupReplay(playCommand()).orElseThrow();

        assertFalse(replay.counted(), "旧请求必须重放历史结果，不能因为窗口过期重新判定");
        assertTrue(replay.duplicate());
        assertEquals(VideoInteractionService.STATE_SUPPRESSED, replay.state());
        verifyNoInteractions(transactionManager);
    }

    @Test
    void lookupReplayRejectsShapeConflictWithSameCodeAsRecord() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY))
                .thenReturn(existing(1, "evt-1"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.lookupReplay(shareCommand()));

        assertEquals(409, error.getCode());
        verifyNoInteractions(transactionManager);
    }

    @Test
    void lookupReplayRejectsCommittedPendingRowInsteadOfGuessing() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY))
                .thenReturn(existing(0, null));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.lookupReplay(playCommand())).getCode());
    }

    @Test
    void lookupReplayPropagatesDatabaseFailureInsteadOfReturningEmpty() {
        when(interactionMapper.selectRequestByKey(REQUEST_KEY))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        DataAccessResourceFailureException error = assertThrows(
                DataAccessResourceFailureException.class,
                () -> service.lookupReplay(playCommand()));

        assertEquals("db down", error.getMessage(),
                "数据库异常不得被当成“未命中”，否则会在限流旁路上继续写库");
        verifyNoInteractions(transactionManager);
    }

    @Test
    void lookupReplayValidatesFieldsBeforeReading() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.lookupReplay(new VideoInteractionCommand(
                        VideoInteractionActorType.USER, ACTOR_HASH, VID,
                        VideoInteractionAction.PLAY, "bad key"))).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.lookupReplay(null)).getCode());
        verifyNoInteractions(interactionMapper);
    }

    // -------------------------------------------------------------------- helpers

    private VideoInteractionCommand playCommand() {
        return new VideoInteractionCommand(VideoInteractionActorType.USER, ACTOR_HASH, VID,
                VideoInteractionAction.PLAY, REQUEST_KEY);
    }

    private VideoInteractionCommand shareCommand() {
        return new VideoInteractionCommand(VideoInteractionActorType.USER, ACTOR_HASH, VID,
                VideoInteractionAction.SHARE, REQUEST_KEY);
    }

    private void givenPlaceholderRequestInserted() {
        when(interactionMapper.insertRequest(any())).thenAnswer(invocation -> {
            VideoInteractionRequest request = invocation.getArgument(0);
            request.setId(500L);
            return 1;
        });
    }

    private void givenWindow(LocalDateTime lastCountedAt) {
        VideoInteractionWindow window = new VideoInteractionWindow();
        window.setId(77L);
        window.setActorType("USER");
        window.setActorHash(ACTOR_HASH);
        window.setVid(VID);
        window.setAction("PLAY");
        window.setLastCountedAt(lastCountedAt);
        when(interactionMapper.selectWindowForUpdate(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(window);
    }

    private VideoInteractionRequest existing(int state, String eventId) {
        VideoInteractionRequest request = new VideoInteractionRequest();
        request.setId(500L);
        request.setRequestKey(REQUEST_KEY);
        request.setActorType("USER");
        request.setActorHash(ACTOR_HASH);
        request.setVid(VID);
        request.setAction("PLAY");
        request.setState(state);
        request.setEventId(eventId);
        return request;
    }

    private VideoStatusChangedEvent event(String eventId) {
        return new VideoStatusChangedEvent(eventId, VID, VideoStatusEventType.PLAY, 1L,
                VideoStatusEventType.PLAY.calculateHotScore(1L), LocalDateTime.now(), 2, null);
    }
}
