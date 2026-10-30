package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.videostats.entity.VideoStatus;
import com.feibijiubi.backend.videostats.entity.VideoStatusReconciliationEventRow;
import com.feibijiubi.backend.videostats.entity.VideoStatusReconciliationRelationAggregate;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.videostats.mapper.VideoStatusReconciliationMapper;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusReconciliationServiceImpl;
import com.feibijiubi.backend.utils.redis.operation.RedisHashOperations;
import com.feibijiubi.backend.videostats.vo.VideoStatusReconciliationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T10 只读对账单元测试：不依赖 MySQL/Redis。
 *
 * <p>重点覆盖：截断标记、事件类型 delta 的 BigInteger 汇总、Redis 计数溢出/
 * 非 canonical 值、Redis 读取异常，以及“未知不当作 0、绝不返回 SAFE”。</p>
 */
class VideoStatusReconciliationTests {

    private static final int VID = 42;

    private VideoStatusReconciliationMapper mapper;
    private RedisHashOperations redisHashOperations;
    private VideoStatusReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(VideoStatusReconciliationMapper.class);
        redisHashOperations = mock(RedisHashOperations.class);
        service = new VideoStatusReconciliationServiceImpl(mapper, redisHashOperations);

        when(mapper.selectDbCounts(anyInt())).thenReturn(dbCounts());
        when(mapper.countEventProjectionIndexPrefix()).thenReturn(1);
        when(mapper.countLedgerByStatus(anyInt(), anyInt(), anyInt())).thenReturn(0L);
        when(mapper.selectEventProjection(anyInt(), anyInt())).thenReturn(List.of());
        when(mapper.selectRelationAggregate(anyInt(), anyInt()))
                .thenReturn(relationAggregate(0L, 0L, 0L, 0L, 0L));
        when(redisHashOperations.multiGet(anyString(), anyList()))
                .thenReturn(redisValues("10", "20", "0", "0", "0", "0", "1", "0"));
    }

    @Test
    void returnsExplicitNonAtomicObservationAndNeverClaimsSafe() {
        when(mapper.selectEventProjection(anyInt(), anyInt())).thenReturn(List.of(
                eventRow(2L, 1, "LIKE", 5L),
                eventRow(1L, 2, "PLAY", 3L)
        ));
        when(mapper.selectRelationAggregate(anyInt(), anyInt()))
                .thenReturn(relationAggregate(2L, 1L, 0L, 2L, 1L));

        VideoStatusReconciliationVO result = service.reconcile(VID, 100);

        assertThat(result.getMode()).isEqualTo(VideoStatusReconciliationVO.MODE);
        assertThat(result.isReadOnly()).isTrue();
        assertThat(result.isWritePerformed()).isFalse();
        assertThat(result.isAutomaticRepair()).isFalse();
        assertThat(result.getObservationBoundary())
                .isEqualTo(VideoStatusReconciliationVO.CONCURRENT_OBSERVATION_NOT_ATOMIC);
        assertThat(result.getUncertainties())
                .contains(VideoStatusReconciliationVO.CONCURRENT_OBSERVATION_NOT_ATOMIC);
        assertThat(result.getVerdict()).isEqualTo(
                VideoStatusReconciliationVO.VERDICT_NO_SAFE_CONCLUSION);
        assertThat(result.getVerdict()).isNotEqualTo("SAFE");
        assertThat(result.getVerdict()).startsWith("NO_SAFE");
        assertThat(result.isIncomplete()).isFalse();

        assertThat(result.getDb().isRowPresent()).isTrue();
        assertThat(result.getDb().getPlayTimes()).isEqualTo(11L);
        assertThat(result.getDb().getDanmuTimes()).isEqualTo(18L);

        assertThat(result.getLedger().isTruncated()).isFalse();
        assertThat(result.getLedger().getStatus0()).isZero();

        assertThat(result.getEventProjection().getRowCount()).isEqualTo(2);
        assertThat(result.getEventProjection().isTruncated()).isFalse();
        assertThat(result.getEventProjection().getDeltaByType().get("LIKE"))
                .isEqualTo(BigInteger.valueOf(5L));
        assertThat(result.getEventProjection().getDeltaByType().get("PLAY"))
                .isEqualTo(BigInteger.valueOf(3L));
        assertThat(result.getEventProjection().getMinEventId()).isEqualTo(1L);
        assertThat(result.getEventProjection().getMaxEventId()).isEqualTo(2L);
        assertThat(result.getEventProjection().getDeltaByType().keySet())
                .containsExactlyElementsOf(Arrays.stream(VideoStatusEventType.values())
                        .map(Enum::name)
                        .toList());

        assertThat(result.getRelations().isSampled()).isTrue();
        assertThat(result.getRelations().isUsableForConclusion()).isTrue();
        assertThat(result.getRelations().getLikedRows()).isEqualTo(1L);
        assertThat(result.getRelations().getCoinSum())
                .isEqualTo(BigInteger.valueOf(2L));

        assertThat(result.getRedis().getStatus()).isEqualTo("SAMPLED");
        assertThat(result.getRedis().getPlayTimes()).isEqualTo(10L);
        assertThat(result.getRedis().getCollectTimes()).isEqualTo(1L);
        verify(redisHashOperations).multiGet(anyString(), anyList());
    }

    @Test
    void truncationAtLimitPlusOneIsReportedAsLowerBoundForLedgerEventsAndRelations() {
        when(mapper.countLedgerByStatus(anyInt(), anyInt(), anyInt())).thenReturn(0L);
        when(mapper.countLedgerByStatus(VID, 1, 3)).thenReturn(3L);
        when(mapper.selectEventProjection(VID, 3)).thenReturn(List.of(
                eventRow(3L, 1, "LIKE", 1L),
                eventRow(2L, 1, "LIKE", 1L),
                eventRow(1L, 1, "LIKE", 1L)
        ));
        when(mapper.selectRelationAggregate(VID, 3))
                .thenReturn(relationAggregate(3L, 3L, 0L, 3L, 0L));

        VideoStatusReconciliationVO result = service.reconcile(VID, 2);

        assertThat(result.getLedger().isTruncated()).isTrue();
        assertThat(result.getLedger().getStatus1()).isEqualTo(3L);
        assertThat(result.getLedger().isStatus0Capped()).isFalse();
        assertThat(result.getLedger().isStatus1Capped()).isTrue();
        assertThat(result.getLedger().isStatus2Capped()).isFalse();
        assertThat(result.getLedger().isStatus3Capped()).isFalse();
        assertThat(result.getEventProjection().isTruncated()).isTrue();
        assertThat(result.getEventProjection().getRowCount()).isEqualTo(2);
        assertThat(result.getEventProjection().getDeltaByType().get("LIKE"))
                .isEqualTo(BigInteger.valueOf(2L));
        assertThat(result.getEventProjection().getMinEventId()).isEqualTo(2L);
        assertThat(result.getEventProjection().getMaxEventId()).isEqualTo(3L);
        assertThat(result.getRelations().isTruncated()).isTrue();
        assertThat(result.getRelations().isLowerBound()).isTrue();
        assertThat(result.getRelations().isUsableForConclusion()).isFalse();
        assertThat(result.isIncomplete()).isTrue();
        assertThat(result.getUncertainties()).contains(
                "LEDGER_COUNTS_TRUNCATED",
                "EVENT_PROJECTION_TRUNCATED",
                "RELATION_SAMPLE_TRUNCATED"
        );
        assertThat(result.getLedger().getNote()).contains("下界");
    }

    @Test
    void unknownEventTypeAndNullDeltaAreExplicitUncertaintiesAndIncomplete() {
        VideoStatusReconciliationEventRow nullDelta = eventRow(3L, 1, "PLAY", 1L);
        nullDelta.setDelta(null);
        when(mapper.selectEventProjection(anyInt(), anyInt())).thenReturn(List.of(
                eventRow(2L, 1, "MYSTERY_EVENT", 7L),
                nullDelta
        ));

        VideoStatusReconciliationVO result = service.reconcile(VID, 100);

        VideoStatusReconciliationVO.EventProjection projection = result.getEventProjection();
        assertThat(projection.getUnknownTypeCount()).isEqualTo(1);
        assertThat(projection.getNullDeltaCount()).isEqualTo(1);
        assertThat(projection.getUnknownTypeDelta()).isEqualTo(BigInteger.valueOf(7L));
        assertThat(result.getUncertainties()).contains(
                "EVENT_PROJECTION_UNKNOWN_TYPE",
                "EVENT_PROJECTION_NULL_DELTA"
        );
        assertThat(result.isIncomplete()).isTrue();
    }

    @Test
    void missingEventProjectionIndexSkipsExpensiveQueryAndIsIncomplete() {
        when(mapper.countEventProjectionIndexPrefix()).thenReturn(0);

        VideoStatusReconciliationVO result = service.reconcile(VID, 100);

        VideoStatusReconciliationVO.EventProjection projection = result.getEventProjection();
        assertThat(projection.isIndexAvailable()).isFalse();
        assertThat(projection.getRowCount()).isZero();
        assertThat(projection.isTruncated()).isFalse();
        assertThat(projection.getDeltaByType().values())
                .containsOnly(BigInteger.ZERO);
        assertThat(result.getUncertainties()).contains("INDEX_MISSING");
        assertThat(result.isIncomplete()).isTrue();
        verify(mapper, never()).selectEventProjection(anyInt(), anyInt());
    }

    @Test
    void eventDeltaSumUsesBigIntegerBeyondLongRange() {
        when(mapper.selectEventProjection(anyInt(), anyInt())).thenReturn(List.of(
                eventRow(2L, 2, "LIKE", Long.MAX_VALUE),
                eventRow(1L, 2, "LIKE", Long.MAX_VALUE)
        ));

        VideoStatusReconciliationVO result = service.reconcile(VID, 100);

        BigInteger expected = BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.TWO);
        assertThat(result.getEventProjection().getDeltaByType().get("LIKE"))
                .isEqualTo(expected);
        assertThat(result.getEventProjection().getDeltaByType().get("LIKE"))
                .isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));
    }

    @Test
    void redisCounterOverflowIsInvalidAndNeverBecomesZero() {
        when(redisHashOperations.multiGet(anyString(), anyList()))
                .thenReturn(redisValues(
                        "9223372036854775808", "0", "0", "0", "0", "0", "0", "0"
                ));

        VideoStatusReconciliationVO result = service.reconcile(VID, 100);

        assertThat(result.getRedis().getStatus()).isEqualTo("INVALID_COUNTER");
        assertThat(result.getRedis().getInvalidFields()).containsExactly("playTimes");
        assertThat(result.getRedis().getPlayTimes()).isNull();
        assertThat(result.getRedis().getLikeTimes()).isNull();
        assertThat(result.getRedis().getDanmuTimes()).isNull();
        assertThat(result.getUncertainties()).contains("REDIS_SAMPLE_INVALID");
        assertThat(result.isIncomplete()).isTrue();
    }

    @Test
    void nonCanonicalRedisCounterIsRejectedInsteadOfSilentlyNormalized() {
        when(redisHashOperations.multiGet(anyString(), anyList()))
                .thenReturn(redisValues(
                        "01", "+2", "0", "0", "0", "0", "0", "0"
                ));

        VideoStatusReconciliationVO result = service.reconcile(VID, 100);

        assertThat(result.getRedis().getStatus()).isEqualTo("INVALID_COUNTER");
        assertThat(result.getRedis().getInvalidFields())
                .containsExactly("playTimes", "likeTimes");
        assertThat(result.getRedis().getPlayTimes()).isNull();
        assertThat(result.getRedis().getLikeTimes()).isNull();
    }

    @Test
    void redisReadFailureIsExplicitAndDoesNotBecomeZero() {
        when(redisHashOperations.multiGet(anyString(), anyList()))
                .thenThrow(new RedisOperationException(
                        "Redis 操作失败",
                        new IllegalStateException("sensitive-detail-should-not-leak")
                ));

        VideoStatusReconciliationVO result = service.reconcile(VID, 100);

        assertThat(result.getRedis().getStatus()).isEqualTo("READ_FAILED");
        assertThat(result.getRedis().getPlayTimes()).isNull();
        assertThat(result.getRedis().getLikeTimes()).isNull();
        assertThat(result.getUncertainties()).contains("REDIS_READ_FAILED");
        assertThat(result.getRedis().getNote()).doesNotContain("sensitive-detail");
        assertThat(result.isIncomplete()).isTrue();
    }

    @Test
    void missingRedisFieldsAreIncompleteNotZero() {
        when(redisHashOperations.multiGet(anyString(), anyList()))
                .thenReturn(redisValues(null, "2", "0", "0", "0", "0", "0", "0"));

        VideoStatusReconciliationVO result = service.reconcile(VID, 100);

        assertThat(result.getRedis().getStatus()).isEqualTo("INCOMPLETE_FIELDS");
        assertThat(result.getRedis().getInvalidFields()).containsExactly("playTimes");
        assertThat(result.getRedis().getPlayTimes()).isNull();
        assertThat(result.getUncertainties()).contains("REDIS_SAMPLE_INCOMPLETE");
    }

    @Test
    void missingDbStatusRowKeepsCountsUnknownInsteadOfZero() {
        when(mapper.selectDbCounts(VID)).thenReturn(null);

        VideoStatusReconciliationVO result = service.reconcile(VID, 100);

        assertThat(result.getDb().isRowPresent()).isFalse();
        assertThat(result.getDb().getPlayTimes()).isNull();
        assertThat(result.getDb().getDanmuTimes()).isNull();
        assertThat(result.getUncertainties()).contains("DB_STATUS_ROW_MISSING");
        assertThat(result.isIncomplete()).isTrue();
    }

    private VideoStatus dbCounts() {
        VideoStatus status = new VideoStatus();
        status.setVid(VID);
        status.setPlayTimes(11L);
        status.setLikeTimes(12L);
        status.setUnlikeTimes(13L);
        status.setCommentTimes(14L);
        status.setCoinTimes(15L);
        status.setShareTimes(16L);
        status.setCollectTimes(17L);
        status.setDanmuTimes(18L);
        return status;
    }

    private VideoStatusReconciliationEventRow eventRow(
            long id,
            int processStatus,
            String eventType,
            Long delta
    ) {
        VideoStatusReconciliationEventRow row = new VideoStatusReconciliationEventRow();
        row.setId(id);
        row.setProcessStatus(processStatus);
        row.setEventType(eventType);
        row.setDelta(delta);
        return row;
    }

    private VideoStatusReconciliationRelationAggregate relationAggregate(
            long sampledRows,
            long likedRows,
            long unlikedRows,
            long coinSum,
            long collectRows
    ) {
        VideoStatusReconciliationRelationAggregate aggregate =
                new VideoStatusReconciliationRelationAggregate();
        aggregate.setSampledRows(sampledRows);
        aggregate.setLikedRows(likedRows);
        aggregate.setUnlikedRows(unlikedRows);
        aggregate.setCoinSum(coinSum);
        aggregate.setCollectRows(collectRows);
        return aggregate;
    }

    private List<String> redisValues(
            String play,
            String like,
            String unlike,
            String comment,
            String coin,
            String share,
            String collect,
            String danmu
    ) {
        return java.util.Arrays.asList(
                play, like, unlike, comment, coin, share, collect, danmu
        );
    }
}
