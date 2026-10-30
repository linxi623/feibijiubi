package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.interaction.config.VideoInteractionProperties;
import com.feibijiubi.backend.interaction.mapper.VideoInteractionMapper;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionCleanupScheduler;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionCleanupServiceImpl;
import com.feibijiubi.backend.interaction.service.VideoInteractionCleanupService.RoundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T15 有界清理的纯单元测试：不连 MySQL / Redis / MQ，不启动 Spring。
 *
 * <p>这里只能证明<b>调用顺序、参数、批大小、开关与失败传播</b>：Mockito 证明不了行锁、守卫命中或
 * “删掉刚推进的窗口行会绕过 30 分钟限计”。那些必须由隔离用例
 * {@code VideoInteractionCleanupIsolatedTests} 在真实 MySQL 上验证（本轮只编译、未运行）。</p>
 */
class VideoInteractionCleanupServiceTests {

    private static final int BATCH_SIZE = 3;

    private VideoInteractionMapper mapper;
    private VideoInteractionProperties properties;
    private VideoInteractionCleanupServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(VideoInteractionMapper.class);
        properties = new VideoInteractionProperties();
        properties.setCleanupEnabled(true);
        properties.setCleanupBatchSize(BATCH_SIZE);
        properties.validate();
        service = new VideoInteractionCleanupServiceImpl(mapper, properties);
        // 空候选的默认桩：多数用例只关心某一条分支，其余批次保持“没有候选”。
        when(mapper.selectExpiredRequestIds(anyInt(), anyInt(), anyInt())).thenReturn(List.of());
        when(mapper.selectIdleWindowIds(anyInt(), anyInt())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------- 开关

    @Test
    void disabledPerformsNoMapperCall() {
        properties.setCleanupEnabled(false);

        RoundResult result = service.cleanupOnce();

        assertEquals(0, result.totalDeleted());
        assertFalse(result.requestBatchFull());
        assertFalse(result.windowBatchFull());
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsCallInsideEnclosingTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.cleanupOnce());
            assertTrue(error.getMessage().contains("不能在已有事务中调用"),
                    "外层事务必须被明确拒绝，而不是让锁持有到整轮结束");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(mapper);
    }

    // --------------------------------------------------------------- 正常一轮

    @Test
    void emptyCandidatesDeleteNothing() {
        RoundResult result = service.cleanupOnce();

        assertEquals(0, result.totalDeleted());
        assertFalse(result.requestBatchFull());
        assertFalse(result.windowBatchFull());
        verify(mapper, never()).deleteExpiredRequestsByIds(anyList(), anyInt());
        verify(mapper, never()).deleteIdleWindowsByIds(anyList(), anyInt());
    }

    @Test
    void processesBothTerminalStatesAndNeverPending() {
        service.cleanupOnce();

        ArgumentCaptor<Integer> states = ArgumentCaptor.forClass(Integer.class);
        verify(mapper, times(2)).selectExpiredRequestIds(states.capture(), eq(30), eq(BATCH_SIZE));
        assertEquals(List.of(1, 2), states.getAllValues(),
                "只允许按 state=1 COUNTED / state=2 SUPPRESSED 各取一批；state=0 PENDING 永不参与清理");
        assertFalse(states.getAllValues().contains(0));
    }

    @Test
    void deletesExactlyTheSelectedIdsWithFixedRetention() {
        when(mapper.selectExpiredRequestIds(1, 30, BATCH_SIZE)).thenReturn(List.of(11L, 12L));
        when(mapper.selectExpiredRequestIds(2, 30, BATCH_SIZE)).thenReturn(List.of(21L));
        when(mapper.selectIdleWindowIds(30, BATCH_SIZE)).thenReturn(List.of(31L));
        when(mapper.deleteExpiredRequestsByIds(anyList(), eq(30)))
                .thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
        when(mapper.deleteIdleWindowsByIds(anyList(), eq(30)))
                .thenAnswer(call -> ((List<?>) call.getArgument(0)).size());

        RoundResult result = service.cleanupOnce();

        assertEquals(2, result.countedRequestsDeleted());
        assertEquals(1, result.suppressedRequestsDeleted());
        assertEquals(1, result.windowsDeleted());
        assertEquals(4, result.totalDeleted());
        verify(mapper).deleteExpiredRequestsByIds(List.of(11L, 12L), 30);
        verify(mapper).deleteExpiredRequestsByIds(List.of(21L), 30);
        verify(mapper).deleteIdleWindowsByIds(List.of(31L), 30);
    }

    @Test
    void affectedRowsFewerThanCandidatesIsNotAnError() {
        // 守卫在 DELETE 里拦下“刚被推进/改状态”的行：影响行数少于候选数是正常结果，不是失败。
        when(mapper.selectExpiredRequestIds(1, 30, BATCH_SIZE)).thenReturn(List.of(11L, 12L));
        when(mapper.deleteExpiredRequestsByIds(List.of(11L, 12L), 30)).thenReturn(1);

        RoundResult result = service.cleanupOnce();

        assertEquals(1, result.countedRequestsDeleted());
        assertEquals(0, result.suppressedRequestsDeleted());
        assertEquals(0, result.windowsDeleted());
    }

    @Test
    void batchFullFlagsReportThatMoreWorkMayRemain() {
        when(mapper.selectExpiredRequestIds(2, 30, BATCH_SIZE))
                .thenReturn(List.of(21L, 22L, 23L));
        when(mapper.selectIdleWindowIds(30, BATCH_SIZE))
                .thenReturn(List.of(31L, 32L, 33L));
        when(mapper.deleteExpiredRequestsByIds(anyList(), eq(30))).thenReturn(3);
        when(mapper.deleteIdleWindowsByIds(anyList(), eq(30))).thenReturn(3);

        RoundResult result = service.cleanupOnce();

        assertTrue(result.requestBatchFull(), "取满批大小说明可能还有剩余候选");
        assertTrue(result.windowBatchFull());
        assertEquals(6, result.totalDeleted());
    }

    // --------------------------------------------------------- 候选 fail closed

    @Test
    void candidateLargerThanBatchSizeFailsClosedWithoutDelete() {
        properties.setCleanupBatchSize(2);
        when(mapper.selectIdleWindowIds(30, 2))
                .thenReturn(List.of(31L, 32L, 33L));

        assertThrows(IllegalStateException.class, () -> service.cleanupOnce());

        verify(mapper, never()).deleteIdleWindowsByIds(anyList(), anyInt());
    }

    @Test
    void invalidCandidateIdFailsClosedWithoutDelete() {
        when(mapper.selectExpiredRequestIds(1, 30, BATCH_SIZE))
                .thenReturn(Arrays.asList(11L, null));

        assertThrows(IllegalStateException.class, () -> service.cleanupOnce());

        verify(mapper, never()).deleteExpiredRequestsByIds(anyList(), anyInt());
    }

    @Test
    void nonPositiveCandidateIdFailsClosedWithoutDelete() {
        when(mapper.selectIdleWindowIds(30, BATCH_SIZE)).thenReturn(List.of(0L));

        assertThrows(IllegalStateException.class, () -> service.cleanupOnce());

        verify(mapper, never()).deleteIdleWindowsByIds(anyList(), anyInt());
    }

    @Test
    void nullCandidateListFailsClosedWithoutDelete() {
        when(mapper.selectIdleWindowIds(30, BATCH_SIZE)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.cleanupOnce());

        verify(mapper, never()).deleteIdleWindowsByIds(anyList(), anyInt());
    }

    // ------------------------------------------------------------------ 失败路径

    @Test
    void selectFailureAbortsRoundBeforeAnyDelete() {
        when(mapper.selectExpiredRequestIds(anyInt(), anyInt(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThrows(DataAccessResourceFailureException.class, () -> service.cleanupOnce());

        verify(mapper, never()).deleteExpiredRequestsByIds(anyList(), anyInt());
        verify(mapper, never()).deleteIdleWindowsByIds(anyList(), anyInt());
    }

    @Test
    void deleteFailureRetriesFromCandidateQueryOnNextRound() {
        when(mapper.selectExpiredRequestIds(1, 30, BATCH_SIZE)).thenReturn(List.of(11L));
        when(mapper.deleteExpiredRequestsByIds(List.of(11L), 30))
                .thenThrow(new DataAccessResourceFailureException("lock wait timeout"))
                .thenReturn(1);

        assertThrows(DataAccessResourceFailureException.class, () -> service.cleanupOnce());

        RoundResult retried = service.cleanupOnce();

        assertEquals(1, retried.countedRequestsDeleted());
        // 清理不持有游标/水位：第二轮必须重新从候选查询开始，而不是记住上次选到的 id。
        verify(mapper, times(2)).selectExpiredRequestIds(1, 30, BATCH_SIZE);
    }

    // ---------------------------------------------------------------- 属性校验

    @Test
    void defaultsAreDisabledAndBounded() {
        VideoInteractionProperties defaults = new VideoInteractionProperties();

        assertFalse(defaults.isCleanupEnabled(), "清理默认必须关闭");
        assertEquals(500, defaults.getCleanupBatchSize());
        assertEquals(60_000L, defaults.getCleanupFixedDelayMs());
        assertDoesNotThrow(defaults::validate);
    }

    @Test
    void propertiesValidationAcceptsBoundaryValues() {
        VideoInteractionProperties low = new VideoInteractionProperties();
        low.setCleanupBatchSize(1);
        low.setCleanupFixedDelayMs(1L);
        assertDoesNotThrow(low::validate);

        VideoInteractionProperties high = new VideoInteractionProperties();
        high.setCleanupBatchSize(5_000);
        assertDoesNotThrow(high::validate);
    }

    @Test
    void propertiesValidationRejectsOutOfRangeBatchSize() {
        for (int bad : new int[]{0, -1, 5_001}) {
            VideoInteractionProperties props = new VideoInteractionProperties();
            props.setCleanupBatchSize(bad);
            assertThrows(IllegalStateException.class, props::validate,
                    "cleanup-batch-size=" + bad + " 必须启动失败");
        }
    }

    @Test
    void propertiesValidationRejectsNonPositiveFixedDelay() {
        for (long bad : new long[]{0L, -1L}) {
            VideoInteractionProperties props = new VideoInteractionProperties();
            props.setCleanupFixedDelayMs(bad);
            assertThrows(IllegalStateException.class, props::validate,
                    "cleanup-fixed-delay-ms=" + bad + " 必须启动失败");
        }
    }

    // ------------------------------------------------------------------ 调度器

    @Test
    void schedulerSkipsWhenDisabled() {
        VideoInteractionCleanupService cleanupService = mock(VideoInteractionCleanupService.class);
        VideoInteractionProperties disabled = new VideoInteractionProperties();
        VideoInteractionCleanupScheduler scheduler =
                new VideoInteractionCleanupScheduler(cleanupService, disabled);

        scheduler.cleanupInteractionTables();

        verifyNoInteractions(cleanupService);
    }

    @Test
    void schedulerSwallowsFailureAndRunsNextRound() {
        VideoInteractionCleanupService cleanupService = mock(VideoInteractionCleanupService.class);
        VideoInteractionProperties enabled = new VideoInteractionProperties();
        enabled.setCleanupEnabled(true);
        VideoInteractionCleanupScheduler scheduler =
                new VideoInteractionCleanupScheduler(cleanupService, enabled);
        when(cleanupService.cleanupOnce())
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(RoundResult.skipped());

        assertDoesNotThrow(scheduler::cleanupInteractionTables,
                "单轮异常不能冒泡成调度任务失败");
        assertDoesNotThrow(scheduler::cleanupInteractionTables);
        verify(cleanupService, times(2)).cleanupOnce();
    }
}
