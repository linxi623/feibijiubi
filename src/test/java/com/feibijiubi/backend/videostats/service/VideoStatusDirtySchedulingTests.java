package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.videostats.mq.VideoStatusBatchFlushScheduler;
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
        doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return null; })
                .when(mutex).withLock(eq(7), anyLong(), any(Runnable.class));
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
