package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.infrastructure.redis.RedisOperationException;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionActorResolver;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionAdmissionServiceImpl;
import com.feibijiubi.backend.interaction.vo.VideoInteractionVO;
import com.feibijiubi.backend.video.service.PublishedVideoGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T15 第二阶段入口编排（HTTP seam）的纯单元测试：不启动 Spring、不连 MySQL/Redis。
 *
 * <p>本类固定的是<b>顺序契约</b>与<b>故障语义</b>，不是端到端并发行为：</p>
 * <ol>
 *   <li>参数校验与视频有效性校验必须早于任何 Redis 计数（否则非法请求会先吃掉额度）；</li>
 *   <li>已提交请求的重放（含被窗口抑制过的旧键）命中时<b>完全不碰 Redis</b>；</li>
 *   <li>Redis 准入失败（异常 / 429）时<b>不产生任何数据库写</b>，异常原样上抛；</li>
 *   <li>查重放抛数据库异常时不能被当成“未命中”。</li>
 * </ol>
 */
class VideoInteractionAdmissionServiceTests {

    private static final int VID = 512;
    private static final String REQUEST_KEY = "req-2026-10-24-1000";
    private static final String VISITOR_ID = "3f2a9c74-0f39-4ccc-9f24-08a4c1f3f2a1";
    private static final int LOGIN_UID = 77;

    private PublishedVideoGuard publishedVideoGuard;
    private VideoInteractionRateLimitService rateLimitService;
    private VideoInteractionService interactionService;
    private VideoInteractionActorResolver actorResolver;
    private VideoInteractionAdmissionServiceImpl service;

    @BeforeEach
    void setUp() {
        publishedVideoGuard = mock(PublishedVideoGuard.class);
        rateLimitService = mock(VideoInteractionRateLimitService.class);
        interactionService = mock(VideoInteractionService.class);
        actorResolver = new VideoInteractionActorResolver();
        service = new VideoInteractionAdmissionServiceImpl(
                actorResolver, publishedVideoGuard, rateLimitService, interactionService);
    }

    // ------------------------------------------------------- validation before redis

    @Test
    void invalidRequestKeyIsRejectedBeforeVideoLookupAndRedis() {
        for (String bad : new String[]{null, "", "  ", "a".repeat(65), "req key", "req/key"}) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID, bad, null));
            assertEquals(400, error.getCode(), "requestKey 应被拒绝: " + bad);
        }
        verifyNoInteractions(publishedVideoGuard, rateLimitService, interactionService);
    }

    @Test
    void invalidVidIsRejectedBeforeVideoLookupAndRedis() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.record(VideoInteractionAction.PLAY, LOGIN_UID, 0,
                        REQUEST_KEY, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.record(VideoInteractionAction.PLAY, LOGIN_UID, null,
                        REQUEST_KEY, null)).getCode());
        verifyNoInteractions(publishedVideoGuard, rateLimitService, interactionService);
    }

    @Test
    void missingOrMalformedVisitorIdIsRejectedBeforeVideoLookupAndRedis() {
        for (String bad : new String[]{null, " ", "not-a-uuid", "1-1-1-1-1"}) {
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> service.record(VideoInteractionAction.PLAY, null, VID,
                            REQUEST_KEY, bad)).getCode());
        }
        verifyNoInteractions(publishedVideoGuard, rateLimitService, interactionService);
    }

    @Test
    void invalidVideoFailsBeforeReplayLookupAndRedis() {
        when(publishedVideoGuard.requirePublishedPlayable(VID))
                .thenThrow(new BusinessException(404, "视频不存在"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID,
                        REQUEST_KEY, null));

        assertEquals(404, error.getCode());
        verifyNoInteractions(rateLimitService, interactionService);
    }

    // ------------------------------------------------------------- replay bypasses redis

    @Test
    void replayOfCountedRequestReturnsOriginalResultWithoutTouchingRedis() {
        givenReplay(result("COUNTED", true, true));

        VideoInteractionVO vo = service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID,
                REQUEST_KEY, null);

        assertTrue(vo.counted());
        assertTrue(vo.duplicate(), "命中已存在的 requestKey 必须是重放");
        assertEquals(VideoInteractionVO.REASON_COUNTED, vo.reason());
        verifyNoInteractions(rateLimitService);
        verify(interactionService, never()).record(any());
    }

    @Test
    void replayOfSuppressedRequestStaysSuppressedWithoutTouchingRedis() {
        givenReplay(result("SUPPRESSED", false, true));

        VideoInteractionVO vo = service.record(VideoInteractionAction.PLAY, null, VID,
                REQUEST_KEY, VISITOR_ID);

        assertFalse(vo.counted());
        assertTrue(vo.duplicate());
        assertEquals(VideoInteractionVO.REASON_WINDOW_SUPPRESSED, vo.reason());
        verifyNoInteractions(rateLimitService);
        verify(interactionService, never()).record(any());
    }

    @Test
    void replayLookupFailureIsNotTreatedAsMiss() {
        when(interactionService.lookupReplay(any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID,
                        REQUEST_KEY, null));

        verifyNoInteractions(rateLimitService);
        verify(interactionService, never()).record(any());
    }

    @Test
    void shapeConflictFromReplayLookupPropagatesWithoutRedis() {
        when(interactionService.lookupReplay(any()))
                .thenThrow(new BusinessException(409, "requestKey 已被不同形状的请求占用"));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID,
                        REQUEST_KEY, null)).getCode());
        verifyNoInteractions(rateLimitService);
    }

    // ------------------------------------------------------- new request: limit then record

    @Test
    void newRequestIsRateLimitedThenRecordedWithResolvedIdentity() {
        when(publishedVideoGuard.requirePublishedPlayable(VID)).thenReturn(null);
        when(interactionService.lookupReplay(any())).thenReturn(Optional.empty());
        when(interactionService.record(any()))
                .thenReturn(result("COUNTED", true, false));

        VideoInteractionVO vo = service.record(VideoInteractionAction.SHARE, LOGIN_UID, VID,
                REQUEST_KEY, null);

        assertTrue(vo.counted());
        assertFalse(vo.duplicate());
        assertEquals(VideoInteractionVO.REASON_COUNTED, vo.reason());

        ArgumentCaptor<VideoInteractionCommand> command =
                ArgumentCaptor.forClass(VideoInteractionCommand.class);
        verify(interactionService).record(command.capture());
        assertEquals(VideoInteractionActorType.USER, command.getValue().actorType());
        assertEquals(VID, command.getValue().vid());
        assertEquals(VideoInteractionAction.SHARE, command.getValue().action());
        assertEquals(REQUEST_KEY, command.getValue().requestKey());
        assertEquals(actorResolver.resolve(LOGIN_UID, null).hash(),
                command.getValue().actorHash());

        verify(rateLimitService).check(command.getValue().actorHash(),
                VideoInteractionAction.SHARE);
    }

    @Test
    void anonymousRequestUsesVisitorDerivedIdentity() {
        when(interactionService.lookupReplay(any())).thenReturn(Optional.empty());
        when(interactionService.record(any()))
                .thenReturn(result("SUPPRESSED", false, false));

        VideoInteractionVO vo = service.record(VideoInteractionAction.PLAY, null, VID,
                REQUEST_KEY, VISITOR_ID);

        assertFalse(vo.counted());
        assertFalse(vo.duplicate());
        assertEquals(VideoInteractionVO.REASON_WINDOW_SUPPRESSED, vo.reason());

        ArgumentCaptor<VideoInteractionCommand> command =
                ArgumentCaptor.forClass(VideoInteractionCommand.class);
        verify(interactionService).record(command.capture());
        assertEquals(VideoInteractionActorType.ANONYMOUS, command.getValue().actorType());
        assertEquals(actorResolver.resolve(null, VISITOR_ID).hash(),
                command.getValue().actorHash());
    }

    @Test
    void loggedInIdentityWinsOverVisitorHeader() {
        when(interactionService.lookupReplay(any())).thenReturn(Optional.empty());
        when(interactionService.record(any()))
                .thenReturn(result("COUNTED", true, false));

        service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID, REQUEST_KEY,
                UUID.randomUUID().toString());

        ArgumentCaptor<VideoInteractionCommand> command =
                ArgumentCaptor.forClass(VideoInteractionCommand.class);
        verify(interactionService).record(command.capture());
        assertEquals(VideoInteractionActorType.USER, command.getValue().actorType());
        assertEquals(actorResolver.resolve(LOGIN_UID, null).hash(),
                command.getValue().actorHash(),
                "登录态必须忽略 X-Visitor-Id，否则同一用户可以拥有第二个身份");
    }

    // -------------------------------------------------------------- fail-closed semantics

    @Test
    void overLimitRejectionWritesNothingToDatabase() {
        when(interactionService.lookupReplay(any())).thenReturn(Optional.empty());
        doThrow(new BusinessException(429, "操作过于频繁，请稍后再试"))
                .when(rateLimitService).check(anyString(), any());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID,
                        REQUEST_KEY, null));

        assertEquals(429, error.getCode());
        verify(interactionService, never()).record(any());
    }

    @Test
    void redisFailureRejectsRequestWithZeroDatabaseWrites() {
        when(interactionService.lookupReplay(any())).thenReturn(Optional.empty());
        doThrow(new RedisOperationException("Redis 操作失败"))
                .when(rateLimitService).check(anyString(), any());

        RedisOperationException error = assertThrows(RedisOperationException.class,
                () -> service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID,
                        REQUEST_KEY, null));

        assertEquals("Redis 操作失败", error.getMessage(), "异常不得被吞掉");
        verify(interactionService, never()).record(any());
    }

    @Test
    void recordFailurePropagatesWithoutBeingSwallowed() {
        when(interactionService.lookupReplay(any())).thenReturn(Optional.empty());
        when(interactionService.record(any()))
                .thenThrow(new BusinessException(500, "同步统计更新失败"));

        assertEquals(500, assertThrows(BusinessException.class,
                () -> service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID,
                        REQUEST_KEY, null)).getCode());
    }

    // ---------------------------------------------------------------- response surface

    @Test
    void responseExposesOnlyCountedDuplicateAndReason() {
        List<String> fields = java.util.Arrays.stream(VideoInteractionVO.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertEquals(List.of("counted", "duplicate", "reason"), fields,
                "不得对外返回 requestId/eventId/actorHash/窗口时间/剩余额度");
    }

    @Test
    void requestKeyLimitIsSixtyFourCharacters() {
        givenReplay(result("COUNTED", true, true));
        service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID, "a".repeat(64), null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.record(VideoInteractionAction.PLAY, LOGIN_UID, VID,
                        "a".repeat(VideoInteractionValidation.MAX_REQUEST_KEY_LENGTH + 1), null));
        assertEquals(400, error.getCode());
    }

    @Test
    void visitorIdIsNotPassedToDatabaseOrRateLimit() {
        when(interactionService.lookupReplay(any())).thenReturn(Optional.empty());
        when(interactionService.record(any()))
                .thenReturn(result("COUNTED", true, false));

        service.record(VideoInteractionAction.PLAY, null, VID, REQUEST_KEY, VISITOR_ID);

        ArgumentCaptor<VideoInteractionCommand> command =
                ArgumentCaptor.forClass(VideoInteractionCommand.class);
        verify(interactionService).record(command.capture());
        assertFalse(command.getValue().actorHash().contains(VISITOR_ID));
        assertTrue(command.getValue().actorHash().matches("[0-9a-f]{64}"));
        verify(rateLimitService).check(eq(command.getValue().actorHash()), any());
    }

    // -------------------------------------------------------------------- helpers

    private VideoInteractionResult result(String state, boolean counted, boolean duplicate) {
        return new VideoInteractionResult(REQUEST_KEY, 900L, state, counted, duplicate, null);
    }

    private void givenReplay(VideoInteractionResult result) {
        when(publishedVideoGuard.requirePublishedPlayable(VID)).thenReturn(null);
        when(interactionService.lookupReplay(any())).thenReturn(Optional.of(result));
    }
}
