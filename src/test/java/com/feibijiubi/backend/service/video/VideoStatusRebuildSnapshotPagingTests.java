package com.feibijiubi.backend.service.video;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusRebuildSnapshotServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * T12-B 单测：MySQL 重建候选的 keyset 分页读取。
 *
 * <p>纯 mock，不启动 Spring、不连 MySQL：断言分页边界（0 条、恰好跨页、页大小整倍数）、
 * 游标严格前进、候选总数上限（等于上限成功、超过上限失败且不返回截断快照）、
 * 快照读取预算在“页间”和“逐条转换”两个位置都能失败，以及 load() 仍是同一个
 * REPEATABLE_READ 只读事务。</p>
 */
class VideoStatusRebuildSnapshotPagingTests {

    private static final int VID = 7;
    private static final long BASELINE_PLAY = 100L;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private VideoStatusMapper videoStatusMapper;
    private VideoStatusConsumedEventMapper consumedEventMapper;
    private VideoStatusProperties properties;
    private AtomicLong clock;
    private VideoStatusRebuildSnapshotServiceImpl service;

    @BeforeEach
    void setUp() {
        videoStatusMapper = mock(VideoStatusMapper.class);
        consumedEventMapper = mock(VideoStatusConsumedEventMapper.class);
        properties = new VideoStatusProperties();
        // 单调时钟：默认不前进，只有预算用例显式推进。
        clock = new AtomicLong();
        LongSupplier nanoTime = clock::get;
        service = new VideoStatusRebuildSnapshotServiceImpl(
                videoStatusMapper,
                consumedEventMapper,
                JSON,
                properties,
                nanoTime
        );
        VideoStatus baseline = new VideoStatus();
        baseline.setVid(VID);
        baseline.setPlayTimes(BASELINE_PLAY);
        when(videoStatusMapper.selectByVid(VID)).thenReturn(baseline);
    }

    @Test
    void emptyCandidateSetReadsExactlyOnePage() {
        stubPages(List.of());

        VideoStatusRebuildSnapshot snapshot = service.load(VID);

        assertEquals(BASELINE_PLAY, snapshot.playTimes());
        assertTrue(snapshot.candidates().isEmpty());
        verify(consumedEventMapper, times(1))
                .selectRebuildCandidatesAfter(eq(VID), isNull(),
                        eq(VideoStatusProperties.MAX_REBUILD_CANDIDATE_PAGE_SIZE));
        verifyNoMoreInteractions(consumedEventMapper);
    }

    @Test
    void candidatesSpanningPagesKeepEveryRowInOrderWithoutDuplicates() {
        properties.setRebuildCandidatePageSize(2);
        stubPages(rows(1, 2, 3, 4, 5));

        VideoStatusRebuildSnapshot snapshot = service.load(VID);

        assertEquals(5, snapshot.candidates().size());
        assertEquals(List.of("e-1", "e-2", "e-3", "e-4", "e-5"),
                snapshot.candidates().stream()
                        .map(VideoStatusRebuildSnapshot.Candidate::eventId)
                        .toList());
        // 页大小 2 读 5 条：第一页、第二页、最后一页（1 条，短页即结束）。
        verify(consumedEventMapper).selectRebuildCandidatesAfter(VID, null, 2);
        verify(consumedEventMapper).selectRebuildCandidatesAfter(VID, 2L, 2);
        verify(consumedEventMapper).selectRebuildCandidatesAfter(VID, 4L, 2);
        verifyNoMoreInteractions(consumedEventMapper);
    }

    @Test
    void exactPageMultipleEndsOnTheEmptyPage() {
        properties.setRebuildCandidatePageSize(2);
        stubPages(rows(1, 2, 3, 4));

        VideoStatusRebuildSnapshot snapshot = service.load(VID);

        assertEquals(4, snapshot.candidates().size());
        // 4 = 2 + 2：两页都“满”，必须再读一页空结果才能确认结束。
        verify(consumedEventMapper).selectRebuildCandidatesAfter(VID, null, 2);
        verify(consumedEventMapper).selectRebuildCandidatesAfter(VID, 2L, 2);
        verify(consumedEventMapper).selectRebuildCandidatesAfter(VID, 4L, 2);
        verifyNoMoreInteractions(consumedEventMapper);
    }

    @Test
    void cursorAdvancesStrictlyPageByPage() {
        properties.setRebuildCandidatePageSize(3);
        stubPages(rows(10, 20, 30, 40, 50, 60, 70));

        service.load(VID);

        List<Long> cursors = new ArrayList<>();
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(consumedEventMapper, times(3))
                .selectRebuildCandidatesAfter(eq(VID), captor.capture(), eq(3));
        cursors.addAll(captor.getAllValues());
        assertEquals(Arrays.asList(null, 30L, 60L), cursors,
                "游标必须取上一页最后一条 id，且严格递增");
    }

    @Test
    void candidateCountEqualToLimitIsAccepted() {
        properties.setRebuildCandidatePageSize(10);
        properties.setRebuildMaxCandidates(3);
        stubPages(rows(1, 2, 3));

        VideoStatusRebuildSnapshot snapshot = service.load(VID);

        assertEquals(3, snapshot.candidates().size());
    }

    @Test
    void candidateCountOverLimitFailsInsteadOfTruncating() {
        properties.setRebuildCandidatePageSize(10);
        properties.setRebuildMaxCandidates(3);
        stubPages(rows(1, 2, 3, 4));

        RetryableMessageException error = assertThrows(RetryableMessageException.class,
                () -> service.load(VID));

        assertTrue(error.getMessage().contains("上限=3"), error.getMessage());
        assertTrue(error.getMessage().contains("拒绝返回截断快照"), error.getMessage());
    }

    @Test
    void nonAdvancingCursorFailsInsteadOfLoopingForever() {
        properties.setRebuildCandidatePageSize(1);
        // 每页都返回同一条 id=5：满页会让循环继续，但游标没有前进。
        when(consumedEventMapper.selectRebuildCandidatesAfter(
                eq(VID), nullable(Long.class), eq(1)))
                .thenReturn(rows(5));

        RetryableMessageException error = assertThrows(RetryableMessageException.class,
                () -> service.load(VID));

        assertTrue(error.getMessage().contains("游标未前进"), error.getMessage());
        verify(consumedEventMapper, times(2))
                .selectRebuildCandidatesAfter(eq(VID), nullable(Long.class), eq(1));
    }

    @Test
    void pageLargerThanRequestedSizeFails() {
        properties.setRebuildCandidatePageSize(2);
        when(consumedEventMapper.selectRebuildCandidatesAfter(VID, null, 2))
                .thenReturn(rows(1, 2, 3));

        RetryableMessageException error = assertThrows(RetryableMessageException.class,
                () -> service.load(VID));

        assertTrue(error.getMessage().contains("超过页大小"), error.getMessage());
    }

    @Test
    void nullPageFailsInsteadOfBeingTreatedAsEndOfData() {
        when(consumedEventMapper.selectRebuildCandidatesAfter(VID, null, 1000))
                .thenReturn(null);

        RetryableMessageException error = assertThrows(RetryableMessageException.class,
                () -> service.load(VID));

        assertTrue(error.getMessage().contains("返回 null"), error.getMessage());
        // null 不能被当成“没有更多候选”的终止信号。
        verify(consumedEventMapper, times(1))
                .selectRebuildCandidatesAfter(eq(VID), nullable(Long.class), anyInt());
    }

    @Test
    void pageWhoseLastRowHasNoIdFails() {
        properties.setRebuildCandidatePageSize(2);
        // 末行 id 缺失：若被当成“已读完”，会静默漏掉后续候选；必须显式失败。
        when(consumedEventMapper.selectRebuildCandidatesAfter(VID, null, 2))
                .thenReturn(List.of(row(1L, "e-1", 1), row(null, "e-null", 1)));

        RetryableMessageException error = assertThrows(RetryableMessageException.class,
                () -> service.load(VID));

        assertTrue(error.getMessage().contains("缺少 id 游标"), error.getMessage());
        verify(consumedEventMapper, times(1))
                .selectRebuildCandidatesAfter(eq(VID), nullable(Long.class), anyInt());
    }

    @Test
    void secondPageLargerThanRequestedSizeFails() {
        properties.setRebuildCandidatePageSize(2);
        // 第一页正好满页，第二页返回 3 条：必须在第二页上失败，而不是把第一页当结果返回。
        when(consumedEventMapper.selectRebuildCandidatesAfter(VID, null, 2))
                .thenReturn(rows(1, 2));
        when(consumedEventMapper.selectRebuildCandidatesAfter(VID, 2L, 2))
                .thenReturn(rows(3, 4, 5));

        RetryableMessageException error = assertThrows(RetryableMessageException.class,
                () -> service.load(VID));

        assertTrue(error.getMessage().contains("超过页大小"), error.getMessage());
        assertTrue(error.getMessage().contains("actual=3"), error.getMessage());
        verify(consumedEventMapper).selectRebuildCandidatesAfter(VID, null, 2);
        verify(consumedEventMapper).selectRebuildCandidatesAfter(VID, 2L, 2);
        verifyNoMoreInteractions(consumedEventMapper);
    }

    @Test
    void nonIncreasingIdsInsideOnePageFail() {
        properties.setRebuildCandidatePageSize(2);
        // 页内乱序：最后一条 id 仍可能“大于游标”，但它前面的行已被重复/跳过，
        // 只看页尾无法发现，所以必须逐行校验严格递增。
        when(consumedEventMapper.selectRebuildCandidatesAfter(VID, null, 2))
                .thenReturn(List.of(row(5L, "e-5", 1), row(3L, "e-3", 1)));

        RetryableMessageException error = assertThrows(RetryableMessageException.class,
                () -> service.load(VID));

        assertTrue(error.getMessage().contains("id 未严格递增"), error.getMessage());
        assertTrue(error.getMessage().contains("previousId=5"), error.getMessage());
        assertTrue(error.getMessage().contains("currentId=3"), error.getMessage());
    }

    @Test
    void budgetExceededBetweenPagesStopsBeforeTheNextQuery() {
        properties.setRebuildCandidatePageSize(2);
        properties.setRebuildSnapshotBudgetMs(4L);
        stubPages(rows(1, 2, 3, 4));
        // 每次读时钟前进 1ms：取截止时间=0、页前检查=1、第 1 条=2、第 2 条=3 都还在
        // 4ms 预算内；第二轮循环的“取下一页之前”检查=4ms 越界，因此第二页不会发起。
        VideoStatusRebuildSnapshotServiceImpl stepping = serviceWithClock(1_000_000L);

        RetryableMessageException error = assertThrows(RetryableMessageException.class,
                () -> stepping.load(VID));

        assertTrue(error.getMessage().contains("超出预算"), error.getMessage());
        assertTrue(error.getMessage().contains("不会被中断"), error.getMessage());
        assertTrue(error.getMessage().contains("已转换候选数=2"), error.getMessage());
        verify(consumedEventMapper, times(1))
                .selectRebuildCandidatesAfter(eq(VID), nullable(Long.class), anyInt());
    }

    @Test
    void budgetExceededDuringConversionReportsHowManyRowsWereConverted() {
        properties.setRebuildCandidatePageSize(3);
        properties.setRebuildSnapshotBudgetMs(10L);
        stubPages(rows(1, 2, 3, 4));
        // 每次读时钟前进 4ms：截止时间=0、页前检查=4、第 1 条=8 都在预算内，
        // 第 2 条转换前=12ms 越界——失败发生在“转换期间”而不是页间。
        VideoStatusRebuildSnapshotServiceImpl stepping = serviceWithClock(4_000_000L);

        RetryableMessageException error = assertThrows(RetryableMessageException.class,
                () -> stepping.load(VID));

        assertTrue(error.getMessage().contains("已转换候选数=1"), error.getMessage());
        verify(consumedEventMapper, times(1))
                .selectRebuildCandidatesAfter(eq(VID), nullable(Long.class), anyInt());
    }

    @Test
    void loadStaysOneRepeatableReadReadOnlyTransaction() throws Exception {
        Transactional transactional = VideoStatusRebuildSnapshotServiceImpl.class
                .getMethod("load", Integer.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional, "load 必须在 Spring 事务内执行");
        assertTrue(transactional.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transactional.isolation());
    }

    @Test
    void candidateSnapshotIsAnImmutableCopy() {
        properties.setRebuildCandidatePageSize(1);
        stubPages(rows(1));

        VideoStatusRebuildSnapshot snapshot = service.load(VID);

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.candidates().clear());
        assertFalse(snapshot.candidates().isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    /** 每次读时钟前进固定步长的单调时钟：只用于确定性地制造“超预算”。 */
    private VideoStatusRebuildSnapshotServiceImpl serviceWithClock(long stepNanos) {
        LongSupplier steppingClock = new LongSupplier() {
            private final AtomicLong now = new AtomicLong();

            @Override
            public long getAsLong() {
                return now.getAndAdd(stepNanos);
            }
        };
        return new VideoStatusRebuildSnapshotServiceImpl(
                videoStatusMapper, consumedEventMapper, JSON, properties,
                steppingClock);
    }

    /** 按真实 keyset 语义提供页：id 严格大于 afterId，最多 limit 条。 */
    private void stubPages(List<VideoStatusConsumedEvent> all) {
        when(consumedEventMapper.selectRebuildCandidatesAfter(
                eq(VID), nullable(Long.class), anyInt()))
                .thenAnswer(call -> {
                    Long afterId = call.getArgument(1);
                    int limit = call.getArgument(2);
                    List<VideoStatusConsumedEvent> page = new ArrayList<>();
                    for (VideoStatusConsumedEvent row : all) {
                        if (afterId != null && row.getId() <= afterId) {
                            continue;
                        }
                        if (page.size() >= limit) {
                            break;
                        }
                        page.add(row);
                    }
                    return page;
                });
    }

    private static List<VideoStatusConsumedEvent> rows(long... ids) {
        List<VideoStatusConsumedEvent> rows = new ArrayList<>(ids.length);
        for (long id : ids) {
            rows.add(row(id, "e-" + id, 1));
        }
        return rows;
    }

    private static VideoStatusConsumedEvent row(
            Long id,
            String eventId,
            int processStatus
    ) {
        VideoStatusChangedEvent event = new VideoStatusChangedEvent(
                eventId,
                VID,
                VideoStatusEventType.PLAY,
                1L,
                VideoStatusEventType.PLAY.calculateHotScore(1L),
                LocalDateTime.now(),
                2,
                null
        );
        VideoStatusConsumedEvent row = new VideoStatusConsumedEvent();
        row.setId(id);
        row.setEventId(eventId);
        row.setVid(VID);
        row.setEventType(event.type().name());
        row.setDelta(event.delta());
        row.setProcessStatus(processStatus);
        try {
            row.setPayload(JSON.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return row;
    }
}
