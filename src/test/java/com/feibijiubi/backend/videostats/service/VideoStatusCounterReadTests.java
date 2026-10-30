package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.videostats.exception.VideoStatusCounterRangeException;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.video.converter.VideoConverter;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.videostats.entity.VideoStatus;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import com.feibijiubi.backend.videostats.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusServiceImpl;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisHashOperations;
import com.feibijiubi.backend.video.vo.VideoDetailVO;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T02 只读入口 {@code VideoStatusServiceImpl#getByVid} 的纯 mock 回归。
 *
 * <p>不启动 Spring、不连接任何 Redis/MySQL/RabbitMQ：只替换 {@link RedisHashOperations}
 * 返回的 Hash 内容，然后走真实的生产入口 {@code getByVid}（内部真实的
 * {@code VideoStatusCounterRange.parse}），以及真实的 {@link VideoConverter}。
 * 因此这里断言的 3e9 / Long.MAX 精确性、坏字段/缺失字段/越界/vid 不匹配的显式异常，
 * 都是生产代码的行为，不是测试自己算出来的。</p>
 */
class VideoStatusCounterReadTests {

    private static final int VID = 4242;
    private static final long THREE_BILLION = 3_000_000_000L;

    private static final String[] COUNTER_FIELDS = {
            "playTimes", "likeTimes", "unlikeTimes", "commentTimes",
            "coinTimes", "shareTimes", "collectTimes", "danmuTimes"};

    private final RedisHashOperations hashes = mock(RedisHashOperations.class);
    private final VideoStatusMapper mapper = mock(VideoStatusMapper.class);

    private final VideoStatusServiceImpl service = new VideoStatusServiceImpl(
            mock(VideoStatusOutboxMapper.class),
            mapper,
            mock(JsonUtils.class),
            hashes,
            mock(RedisUtils.class),
            mock(VideoStatusProperties.class),
            mock(VideoStatusVidMutex.class),
            mock(VideoStatusRebuildService.class));

    /** A complete, canonical Redis Hash exactly as the init Lua writes it. */
    private static Map<String, String> canonicalCache(int vid, String playTimes) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("vid", String.valueOf(vid));
        for (String field : COUNTER_FIELDS) {
            values.put(field, "0");
        }
        values.put("playTimes", playTimes);
        return values;
    }

    private void givenCache(Map<String, String> values) {
        when(hashes.entries(RedisKeyUtils.videoStatus(VID))).thenReturn(values);
    }

    @Test
    void threeBillionGoesFromRedisThroughServiceToDetailVo() {
        givenCache(canonicalCache(VID, "3000000000"));

        VideoStatus status = service.getByVid(VID);
        assertNotNull(status);
        assertEquals(VID, status.getVid());
        assertEquals(THREE_BILLION, status.getPlayTimes());
        assertEquals(0L, status.getLikeTimes());

        Video video = new Video();
        video.setVid(VID);
        VideoDetailVO vo = VideoConverter.toVideoDetailVO(video, status, null, null, null, null, null);
        assertEquals(THREE_BILLION, vo.getPlayTimes());

        // 缓存命中时不得回落到数据库（否则 3e9 可能被另一条路径改写）。
        verify(mapper, never()).selectByVid(VID);
    }

    @Test
    void longMaxIsReturnedExactly() {
        givenCache(canonicalCache(VID, String.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.MAX_VALUE, service.getByVid(VID).getPlayTimes());
        verify(mapper, never()).selectByVid(VID);
    }

    @Test
    void malformedFieldIsAnExplicitRangeException() {
        givenCache(canonicalCache(VID, "not-a-number"));
        VideoStatusCounterRangeException e =
                assertThrows(VideoStatusCounterRangeException.class, () -> service.getByVid(VID));
        assertEquals(VideoStatusCounterRangeException.Reason.MALFORMED, e.getReason());
        assertTrue(e.getField().contains("playTimes"), e.getField());
        assertTrue(e.getMessage().contains("not-a-number"), e.getMessage());
    }

    @Test
    void missingFieldIsAnExplicitRangeExceptionNotZero() {
        Map<String, String> partial = canonicalCache(VID, "0");
        partial.remove("playTimes");
        givenCache(partial);

        VideoStatusCounterRangeException e =
                assertThrows(VideoStatusCounterRangeException.class, () -> service.getByVid(VID));
        assertEquals(VideoStatusCounterRangeException.Reason.MISSING, e.getReason());
        assertTrue(e.getField().contains("playTimes"), e.getField());
    }

    @Test
    void negativeAndOverflowFieldsAreExplicit() {
        givenCache(canonicalCache(VID, "-1"));
        assertEquals(VideoStatusCounterRangeException.Reason.NEGATIVE,
                assertThrows(VideoStatusCounterRangeException.class, () -> service.getByVid(VID)).getReason());

        givenCache(canonicalCache(VID, "9223372036854775808"));
        assertEquals(VideoStatusCounterRangeException.Reason.OVERFLOW,
                assertThrows(VideoStatusCounterRangeException.class, () -> service.getByVid(VID)).getReason());
    }

    @Test
    void cachedVidThatDoesNotMatchTheRequestIsRejected() {
        // The hash belongs to another video: returning it would report someone else's counters.
        givenCache(canonicalCache(VID + 1, "7"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.getByVid(VID));
        assertTrue(e.getMessage().contains("actual="), e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(VID)), e.getMessage());
        verify(mapper, never()).selectByVid(VID);
    }

    @Test
    void missingVidFieldIsAnExplicitRangeException() {
        Map<String, String> values = canonicalCache(VID, "0");
        values.remove("vid");
        givenCache(values);

        VideoStatusCounterRangeException e =
                assertThrows(VideoStatusCounterRangeException.class, () -> service.getByVid(VID));
        assertEquals(VideoStatusCounterRangeException.Reason.MISSING, e.getReason());
        assertTrue(e.getField().endsWith(".vid"), e.getField());
    }

    @Test
    void emptyCacheFallsBackToDatabase() {
        when(hashes.entries(RedisKeyUtils.videoStatus(VID))).thenReturn(Map.of());
        VideoStatus fromDb = new VideoStatus();
        fromDb.setVid(VID);
        fromDb.setPlayTimes(THREE_BILLION);
        when(mapper.selectByVid(VID)).thenReturn(fromDb);

        assertSame(fromDb, service.getByVid(VID));
    }
}
