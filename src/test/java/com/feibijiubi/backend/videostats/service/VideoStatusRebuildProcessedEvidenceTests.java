package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.videostats.exception.RetryableMessageException;
import com.feibijiubi.backend.videostats.enums.VideoStatusConsumeProcessStatus;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusRebuildServiceImpl;
import com.feibijiubi.backend.utils.redis.RedisConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T12-A 单测：重建分类路径按固定有界批次一次 pipeline 查询 processed 证据，
 * 只查状态 0 候选，逐 key 结果按快照顺序回填，任何未知/异常结果都 fail closed。
 *
 * <p>纯 mock：不启动 Spring、不连 MySQL/Redis。断言同时覆盖“现有重建协议不退化”：
 * 先写无 TTL 门闩、再读快照、最后同 token 释放，初始化脚本的 keys/args 形状不变。</p>
 */
class VideoStatusRebuildProcessedEvidenceTests {

    private static final int VID = 7;
    private static final String CURRENT_KEY = RedisKeyUtils.videoStatus(VID);
    private static final String GATE_KEY = RedisKeyUtils.videoStatusRebuildGate(VID);
    private static final long BASELINE_PLAY = 100L;

    private VideoStatusRebuildSnapshotService snapshots;
    private RedisUtils redisUtils;
    private DefaultRedisScript<String> initScript;
    private DefaultRedisScript<Long> releaseGateScript;
    private VideoStatusRebuildServiceImpl service;

    @BeforeEach
    void setUp() {
        snapshots = mock(VideoStatusRebuildSnapshotService.class);
        redisUtils = mock(RedisUtils.class);
        VideoStatusVidMutex vidMutex = mock(VideoStatusVidMutex.class);
        // ensureInitialized 传入的 lambda 是 void 表达式，匹配 Runnable 重载。
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(vidMutex).withLock(anyInt(), any(Runnable.class));

        service = new VideoStatusRebuildServiceImpl(
                snapshots,
                vidMutex,
                redisUtils
        );
        initScript = new DefaultRedisScript<>();
        releaseGateScript = new DefaultRedisScript<>();
        ReflectionTestUtils.setField(service, "videoStatusInitScript", initScript);
        ReflectionTestUtils.setField(
                service,
                "videoStatusReleaseGateScript",
                releaseGateScript
        );
        // 默认场景：current 尚不存在，重建继续走到快照与初始化。
        when(redisUtils.hasKey(CURRENT_KEY)).thenReturn(false);
    }

    @Test
    void candidatesWithoutProcessedEvidenceNeedNoRedisQuery() {
        when(snapshots.load(VID)).thenReturn(snapshot(List.of()));
        stubInitialized();

        service.ensureInitialized(VID);

        verify(redisUtils, never()).hasKeys(anyList());
        verify(redisUtils, never()).hasKey(
                startsWith(RedisConstants.PROCESSED_PREFIX)
        );
        List<Object> args = initArgs();
        assertEquals(String.valueOf(BASELINE_PLAY), args.get(2));
        assertEquals("false", args.get(10), "没有待落库事件时 pending 标志为 false");
    }

    @Test
    void statusOneCandidatesAreConfirmedWithoutRedisEvidence() {
        when(snapshots.load(VID)).thenReturn(snapshot(List.of(
                applied("e-1", 5L),
                applied("e-2", 7L)
        )));
        stubInitialized();

        service.ensureInitialized(VID);

        verify(redisUtils, never()).hasKeys(anyList());
        List<Object> args = initArgs();
        assertEquals(String.valueOf(BASELINE_PLAY + 12L), args.get(2));
        assertEquals("true", args.get(10));
    }

    @Test
    void onlyReceivedCandidatesAreQueriedInSnapshotOrder() {
        when(snapshots.load(VID)).thenReturn(snapshot(List.of(
                applied("e-1", 1L),
                received("r-1", 2L),
                received("r-2", 4L),
                applied("e-2", 8L)
        )));
        when(redisUtils.hasKeys(List.of(
                RedisKeyUtils.processedKey("r-1"),
                RedisKeyUtils.processedKey("r-2")
        ))).thenReturn(List.of(true, false));
        stubInitialized();

        service.ensureInitialized(VID);

        // 状态 1 不查询；状态 0 的 key 顺序与快照一致。
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisUtils, times(1)).hasKeys(keysCaptor.capture());
        assertEquals(List.of(
                RedisKeyUtils.processedKey("r-1"),
                RedisKeyUtils.processedKey("r-2")
        ), keysCaptor.getValue());

        List<Object> args = initArgs();
        // 100 + 1(状态1) + 2(r-1 有证据) + 8(状态1)；r-2 无证据不计入。
        assertEquals(String.valueOf(BASELINE_PLAY + 11L), args.get(2));
        assertEquals("true", args.get(10));
    }

    @Test
    void batchesAreBoundedAndKeepPerKeyOrderAcrossBoundaries() {
        int candidates = 600;
        List<VideoStatusRebuildSnapshot.Candidate> list = new ArrayList<>(candidates);
        for (int i = 0; i < candidates; i++) {
            list.add(received("e-" + i, 1L));
        }
        when(snapshots.load(VID)).thenReturn(snapshot(list));
        // 偶数序号的 eventId 有 processed 证据；按 key 内容逐条判定，顺序敏感。
        when(redisUtils.hasKeys(anyList())).thenAnswer(call -> {
            List<String> keys = call.getArgument(0);
            List<Boolean> results = new ArrayList<>(keys.size());
            for (String key : keys) {
                int index = Integer.parseInt(key.substring(
                        RedisKeyUtils.processedKey("e-").length()
                ));
                results.add(index % 2 == 0);
            }
            return results;
        });
        stubInitialized();

        service.ensureInitialized(VID);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisUtils, times(3)).hasKeys(keysCaptor.capture());
        List<List<String>> batches = keysCaptor.getAllValues();
        assertEquals(List.of(256, 256, 88), batchSizes(batches));
        assertEquals(RedisKeyUtils.processedKey("e-0"), batches.get(0).get(0));
        assertEquals(RedisKeyUtils.processedKey("e-255"), batches.get(0).get(255));
        assertEquals(RedisKeyUtils.processedKey("e-256"), batches.get(1).get(0));
        assertEquals(RedisKeyUtils.processedKey("e-599"), batches.get(2).get(87));

        List<Object> args = initArgs();
        assertEquals(String.valueOf(BASELINE_PLAY + 300L), args.get(2));
        assertEquals("true", args.get(10));
    }

    @Test
    void duplicateEventIdsAreCountedPerOccurrence() {
        int candidates = 257;
        List<VideoStatusRebuildSnapshot.Candidate> list = new ArrayList<>(candidates);
        for (int i = 0; i < candidates; i++) {
            list.add(received("dup", 1L));
        }
        when(snapshots.load(VID)).thenReturn(snapshot(list));
        // 同一 eventId 跨批重复：每处出现都必须拿到自己的逐 key 结果。
        when(redisUtils.hasKeys(anyList())).thenAnswer(call -> {
            List<String> keys = call.getArgument(0);
            List<Boolean> results = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                results.add(true);
            }
            return results;
        });
        stubInitialized();

        service.ensureInitialized(VID);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisUtils, times(2)).hasKeys(keysCaptor.capture());
        assertEquals(List.of(256, 1), batchSizes(keysCaptor.getAllValues()));
        assertEquals(
                List.of(RedisKeyUtils.processedKey("dup")),
                keysCaptor.getAllValues().get(1)
        );

        List<Object> args = initArgs();
        assertEquals(String.valueOf(BASELINE_PLAY + 257L), args.get(2));
    }

    @Test
    void redisEvidenceFailurePublishesNoInitializationAndReleasesGate() {
        when(snapshots.load(VID)).thenReturn(snapshot(List.of(
                received("r-1", 1L)
        )));
        when(redisUtils.hasKeys(anyList()))
                .thenThrow(new RedisOperationException("redis down"));

        assertThrows(RedisOperationException.class,
                () -> service.ensureInitialized(VID));

        verify(redisUtils, never()).executeScript(
                eq(initScript), anyList(), any(Object[].class)
        );
        // 除 current 存在性检查外，不得再出现逐事件的 processed 单次查询。
        verify(redisUtils, times(1)).hasKey(anyString());
        verify(redisUtils, never()).hasKey(
                startsWith(RedisConstants.PROCESSED_PREFIX)
        );
        // 门闩仍然用同一 token 释放：失败不改变 token-owned release 协议。
        String token = gateToken();
        ArgumentCaptor<Object[]> releaseArgs = ArgumentCaptor.forClass(Object[].class);
        verify(redisUtils).executeScript(
                eq(releaseGateScript),
                eq(List.of(GATE_KEY)),
                releaseArgs.capture()
        );
        assertEquals(token, releaseArgs.getValue()[0]);
    }

    @Test
    void evidenceLengthMismatchPublishesNoInitialization() {
        when(snapshots.load(VID)).thenReturn(snapshot(List.of(
                received("r-1", 1L),
                received("r-2", 1L)
        )));
        when(redisUtils.hasKeys(anyList())).thenReturn(List.of(true));

        assertThrows(RedisOperationException.class,
                () -> service.ensureInitialized(VID));

        verify(redisUtils, never()).executeScript(
                eq(initScript), anyList(), any(Object[].class)
        );
    }

    @Test
    void nullEvidenceEntryPublishesNoInitialization() {
        when(snapshots.load(VID)).thenReturn(snapshot(List.of(
                received("r-1", 1L),
                received("r-2", 1L)
        )));
        when(redisUtils.hasKeys(anyList()))
                .thenReturn(Arrays.asList(true, null));

        assertThrows(RedisOperationException.class,
                () -> service.ensureInitialized(VID));

        verify(redisUtils, never()).executeScript(
                eq(initScript), anyList(), any(Object[].class)
        );
    }

    @Test
    void snapshotReadFailurePublishesNoInitializationAndNextAttemptTakesOver() {
        // T12-B：分页超预算/超候选上限都以 RetryableMessageException 失败，
        // 此时还没有任何初始化写入；门闩仍由 finally 用同一 token 释放。
        doThrow(new RetryableMessageException(
                "Redis 重建候选数超过上限，vid=" + VID))
                .when(snapshots).load(VID);

        assertThrows(RetryableMessageException.class,
                () -> service.ensureInitialized(VID));

        verify(redisUtils, never()).executeScript(
                eq(initScript), anyList(), any(Object[].class)
        );

        // 下一次尝试：读取恢复正常，同一门闩键由新的 owner token 重新关闭并完成初始化。
        doReturn(snapshot(List.of(applied("e-1", 5L))))
                .when(snapshots).load(VID);
        stubInitialized();

        service.ensureInitialized(VID);

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(redisUtils, times(2)).setString(eq(GATE_KEY), tokens.capture());
        assertNotEquals(tokens.getAllValues().get(0), tokens.getAllValues().get(1),
                "每次重建都必须用新的 owner token，旧失败不得复用");

        ArgumentCaptor<Object[]> initArgs = ArgumentCaptor.forClass(Object[].class);
        verify(redisUtils).executeScript(
                eq(initScript), anyList(), initArgs.capture()
        );
        assertEquals(tokens.getAllValues().get(1), initArgs.getValue()[11],
                "初始化必须绑定当前 owner token");
        assertEquals(String.valueOf(BASELINE_PLAY + 5L), initArgs.getValue()[2]);
    }

    @Test
    void rebuildProtocolOrderAndInitShapeAreUnchanged() {
        when(snapshots.load(VID)).thenReturn(snapshot(List.of(
                received("r-1", 2L)
        )));
        when(redisUtils.hasKeys(anyList())).thenReturn(List.of(true));
        stubInitialized();

        service.ensureInitialized(VID);

        // 先写无 TTL 门闩 → 读快照 → 一次初始化脚本 → 同 token 释放。
        InOrder order = inOrder(redisUtils, snapshots);
        order.verify(redisUtils).setString(eq(GATE_KEY), anyString());
        order.verify(redisUtils).hasKey(CURRENT_KEY);
        order.verify(snapshots).load(VID);
        order.verify(redisUtils).hasKeys(anyList());
        order.verify(redisUtils).executeScript(
                eq(initScript), anyList(), any(Object[].class)
        );
        order.verify(redisUtils).executeScript(
                eq(releaseGateScript), eq(List.of(GATE_KEY)), any(Object[].class)
        );

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisUtils).executeScript(
                eq(initScript), keysCaptor.capture(), argsCaptor.capture()
        );
        assertEquals(List.of(
                CURRENT_KEY,
                RedisKeyUtils.dirtyVideo(),
                RedisKeyUtils.feedHotVideos(),
                GATE_KEY
        ), keysCaptor.getValue());
        assertEquals(12, argsCaptor.getValue().length,
                "初始化参数形状（vid/hotScore/8 计数/pending/token）不得变化");
        assertEquals(String.valueOf(VID), argsCaptor.getValue()[0]);
        assertEquals(gateToken(), argsCaptor.getValue()[11]);
    }

    // ------------------------------------------------------------------ 工具

    private void stubInitialized() {
        when(redisUtils.executeScript(
                eq(initScript),
                anyList(),
                any(Object[].class)
        )).thenReturn("INITIALIZED");
    }

    private List<Object> initArgs() {
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(redisUtils).executeScript(
                eq(initScript), anyList(), captor.capture()
        );
        return Arrays.asList(captor.getValue());
    }

    private String gateToken() {
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisUtils).setString(eq(GATE_KEY), tokenCaptor.capture());
        return tokenCaptor.getValue();
    }

    private static List<Integer> batchSizes(List<List<String>> batches) {
        List<Integer> sizes = new ArrayList<>(batches.size());
        for (List<String> batch : batches) {
            sizes.add(batch.size());
        }
        return sizes;
    }

    private static VideoStatusRebuildSnapshot snapshot(
            List<VideoStatusRebuildSnapshot.Candidate> candidates
    ) {
        return new VideoStatusRebuildSnapshot(
                VID,
                BASELINE_PLAY,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                candidates
        );
    }

    private static VideoStatusRebuildSnapshot.Candidate received(
            String eventId,
            long playDelta
    ) {
        return candidate(
                eventId,
                VideoStatusConsumeProcessStatus.RECEIVED,
                playDelta
        );
    }

    private static VideoStatusRebuildSnapshot.Candidate applied(
            String eventId,
            long playDelta
    ) {
        return candidate(
                eventId,
                VideoStatusConsumeProcessStatus.REDIS_APPLIED_PENDING_FLUSH,
                playDelta
        );
    }

    private static VideoStatusRebuildSnapshot.Candidate candidate(
            String eventId,
            VideoStatusConsumeProcessStatus status,
            long playDelta
    ) {
        return new VideoStatusRebuildSnapshot.Candidate(
                eventId,
                status.getCode(),
                new VideoStatusDelta(playDelta, 0, 0, 0, 0, 0, 0, 0),
                playDelta
        );
    }
}
