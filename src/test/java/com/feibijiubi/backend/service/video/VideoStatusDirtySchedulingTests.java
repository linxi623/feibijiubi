package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoStatusDirtySchedulingTests {
    @Test
    void emptyPendingQueryMustNotRemoveConcurrentConsumersDirtyMarker() {
        var properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setSchedulingEnabled(true);
        var sets = mock(RedisSetOperations.class);
        var flush = mock(VideoStatusBatchFlushService.class);
        var mapper = mock(VideoStatusConsumedEventMapper.class);
        var mutex = mock(VideoStatusVidMutex.class);
        Set<String> dirty = new HashSet<>(Set.of("7"));
        when(sets.pop(anyString(), anyLong())).thenAnswer(call -> {
            var popped = Set.copyOf(dirty);
            dirty.clear();
            return popped;
        });
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(mutex).withLock(eq(7), any(Runnable.class));
        when(mapper.countPendingByVid(7)).thenAnswer(call -> {
            // SELECT returned 0, but a consumer now executes SADD before caller resumes.
            dirty.add("7");
            return 0;
        });
        new VideoStatusBatchFlushScheduler(properties, sets, flush, mapper, mutex).flushDirtyVideos();
        assertTrue(dirty.contains("7"));
        verify(sets, never()).remove(eq(RedisKeyUtils.dirtyVideo()), any(String[].class));
    }
}
