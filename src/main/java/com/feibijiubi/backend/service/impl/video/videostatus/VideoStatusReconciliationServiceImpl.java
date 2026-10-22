package com.feibijiubi.backend.service.impl.video.videostatus;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.common.VideoStatusCounterRange;
import com.feibijiubi.backend.common.VideoStatusCounterRangeException;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.entity.VideoStatusReconciliationEventRow;
import com.feibijiubi.backend.entity.VideoStatusReconciliationRelationAggregate;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusReconciliationMapper;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusReconciliationService;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisHashOperations;
import com.feibijiubi.backend.vo.VideoStatusReconciliationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T10 单视频只读对账实现。
 *
 * <p>严格边界：</p>
 * <ul>
 *   <li>只调用 SELECT/MGET，不写 Redis/MySQL，不领取租约，不修改事件状态；</li>
 *   <li>DB 八项计数、账本状态数量、事件窗口、关系真值和 Redis 采样分别读取，
 *       因此明确标记 {@code CONCURRENT_OBSERVATION_NOT_ATOMIC}；</li>
 *   <li>所有账本/事件/关系查询最多读取 {@code limit + 1} 行，达到上界即标记截断，
 *       截断结果只能当“至少/窗口下界”，不参与 SAFE 结论；</li>
 *   <li>历史 ledger 的 SUM 不是 DB 真值；本接口只展示窗口内事件类型 delta 的
 *       BigInteger 汇总，并保留截断标记；</li>
 *   <li>Redis 只读取八个计数字段并做 canonical 非负十进制 Long 校验；
 *       缺失、非 canonical、溢出或读取异常都返回明确状态和 null 计数，绝不当 0；</li>
 *   <li>响应不含 payload、错误原文、租约 token；接口永不自动修复，也不返回 SAFE。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class VideoStatusReconciliationServiceImpl
        implements VideoStatusReconciliationService {

    static final String LEDGER_COUNTS_TRUNCATED = "LEDGER_COUNTS_TRUNCATED";
    static final String EVENT_PROJECTION_TRUNCATED = "EVENT_PROJECTION_TRUNCATED";
    static final String RELATION_SAMPLE_TRUNCATED = "RELATION_SAMPLE_TRUNCATED";
    static final String RELATION_TRUTH_UNAVAILABLE = "RELATION_TRUTH_UNAVAILABLE";
    static final String DB_STATUS_ROW_MISSING = "DB_STATUS_ROW_MISSING";
    static final String REDIS_NOT_SAMPLED = "REDIS_NOT_SAMPLED";
    static final String REDIS_SAMPLE_INCOMPLETE = "REDIS_SAMPLE_INCOMPLETE";
    static final String REDIS_SAMPLE_INVALID = "REDIS_SAMPLE_INVALID";
    static final String REDIS_READ_FAILED = "REDIS_READ_FAILED";

    static final String REDIS_STATUS_SAMPLED = "SAMPLED";
    static final String REDIS_STATUS_NOT_SAMPLED = "NOT_SAMPLED_OR_MISSING";
    static final String REDIS_STATUS_INCOMPLETE = "INCOMPLETE_FIELDS";
    static final String REDIS_STATUS_INVALID = "INVALID_COUNTER";
    static final String REDIS_STATUS_READ_FAILED = "READ_FAILED";

    static final List<String> REDIS_COUNTER_FIELDS = List.of(
            "playTimes",
            "likeTimes",
            "unlikeTimes",
            "commentTimes",
            "coinTimes",
            "shareTimes",
            "collectTimes",
            "danmuTimes"
    );

    private final VideoStatusReconciliationMapper reconciliationMapper;
    private final RedisHashOperations redisHashOperations;

    @Override
    public VideoStatusReconciliationVO reconcile(int vid, int limit) {
        if (vid <= 0) {
            throw new BusinessException(400, "vid 必须是正整数");
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException(400,
                    "limit 必须在 " + MIN_LIMIT + " 到 " + MAX_LIMIT + " 之间");
        }

        int cap = limit + 1;
        List<String> uncertainties = new ArrayList<>();
        uncertainties.add(VideoStatusReconciliationVO.CONCURRENT_OBSERVATION_NOT_ATOMIC);

        VideoStatusReconciliationVO.DbCounts db = readDbCounts(vid, uncertainties);
        VideoStatusReconciliationVO.LedgerCounts ledger =
                readLedgerCounts(vid, cap, uncertainties);
        VideoStatusReconciliationVO.EventProjection eventProjection =
                readEventProjection(vid, limit, cap, uncertainties);
        VideoStatusReconciliationVO.RelationTruth relations =
                readRelations(vid, cap, uncertainties);
        VideoStatusReconciliationVO.RedisSample redis = readRedisSample(vid, uncertainties);

        boolean incomplete = !db.isRowPresent()
                || ledger.isTruncated()
                || eventProjection.isTruncated()
                || !relations.isSampled()
                || relations.isTruncated()
                || !REDIS_STATUS_SAMPLED.equals(redis.getStatus());

        return new VideoStatusReconciliationVO(
                vid,
                limit,
                VideoStatusReconciliationVO.MODE,
                true,
                false,
                false,
                VideoStatusReconciliationVO.CONCURRENT_OBSERVATION_NOT_ATOMIC,
                incomplete,
                List.copyOf(uncertainties),
                VideoStatusReconciliationVO.VERDICT_NO_SAFE_CONCLUSION,
                db,
                ledger,
                eventProjection,
                relations,
                redis,
                "只读诊断：各数据源不是同一事务/时刻的快照；截断或 Redis 未采样时不得据此下结论，"
                        + "接口不会自动修复，也不会返回 SAFE。"
        );
    }

    private VideoStatusReconciliationVO.DbCounts readDbCounts(
            int vid,
            List<String> uncertainties
    ) {
        VideoStatus status = reconciliationMapper.selectDbCounts(vid);
        if (status == null) {
            uncertainties.add(DB_STATUS_ROW_MISSING);
            return new VideoStatusReconciliationVO.DbCounts(
                    false,
                    null, null, null, null, null, null, null, null,
                    "video_status 没有该 vid 的统计行；八项计数未知，不按 0 处理"
            );
        }
        return new VideoStatusReconciliationVO.DbCounts(
                true,
                status.getPlayTimes(),
                status.getLikeTimes(),
                status.getUnlikeTimes(),
                status.getCommentTimes(),
                status.getCoinTimes(),
                status.getShareTimes(),
                status.getCollectTimes(),
                status.getDanmuTimes(),
                "DB 当前八项计数；只代表本次读取值，不是与账本/Redis 的原子快照"
        );
    }

    private VideoStatusReconciliationVO.LedgerCounts readLedgerCounts(
            int vid,
            int cap,
            List<String> uncertainties
    ) {
        long status0 = reconciliationMapper.countLedgerByStatus(vid, 0, cap);
        long status1 = reconciliationMapper.countLedgerByStatus(vid, 1, cap);
        long status2 = reconciliationMapper.countLedgerByStatus(vid, 2, cap);
        long status3 = reconciliationMapper.countLedgerByStatus(vid, 3, cap);
        boolean truncated = status0 >= cap
                || status1 >= cap
                || status2 >= cap
                || status3 >= cap;
        if (truncated) {
            uncertainties.add(LEDGER_COUNTS_TRUNCATED);
        }
        return new VideoStatusReconciliationVO.LedgerCounts(
                cap,
                status0,
                status1,
                status2,
                status3,
                truncated,
                truncated
                        ? "至少一个状态达到 cap=" + cap + "，数量只是下界；不能相加当作历史 ledger 总量或 DB 真值"
                        : "每个状态都低于 cap，数量为本次读取的精确值；仍不是 DB 真值"
        );
    }

    private VideoStatusReconciliationVO.EventProjection readEventProjection(
            int vid,
            int limit,
            int cap,
            List<String> uncertainties
    ) {
        List<VideoStatusReconciliationEventRow> rows =
                reconciliationMapper.selectEventProjection(vid, cap);
        boolean truncated = rows.size() > limit;
        if (truncated) {
            uncertainties.add(EVENT_PROJECTION_TRUNCATED);
        }

        Map<String, BigInteger> deltaByType = new LinkedHashMap<>();
        for (VideoStatusEventType type : VideoStatusEventType.values()) {
            deltaByType.put(type.name(), BigInteger.ZERO);
        }

        int unknownTypeCount = 0;
        BigInteger unknownTypeDelta = BigInteger.ZERO;
        Long minEventId = null;
        Long maxEventId = null;

        for (VideoStatusReconciliationEventRow row : rows) {
            if (row.getId() != null) {
                minEventId = minEventId == null
                        ? row.getId()
                        : Math.min(minEventId, row.getId());
                maxEventId = maxEventId == null
                        ? row.getId()
                        : Math.max(maxEventId, row.getId());
            }
            Long delta = row.getDelta();
            if (delta == null) {
                unknownTypeCount++;
                continue;
            }
            BigInteger value = BigInteger.valueOf(delta);
            VideoStatusEventType type = parseEventType(row.getEventType());
            if (type == null) {
                unknownTypeCount++;
                unknownTypeDelta = unknownTypeDelta.add(value);
            } else {
                deltaByType.merge(type.name(), value, BigInteger::add);
            }
        }

        return new VideoStatusReconciliationVO.EventProjection(
                limit,
                rows.size(),
                truncated,
                Map.copyOf(deltaByType),
                unknownTypeCount,
                unknownTypeDelta,
                minEventId,
                maxEventId,
                truncated
                        ? "只读取最近 " + cap + " 行事件（超过 limit），窗口不完整；类型 delta 不能代表全部历史"
                        : "窗口内事件已完整读取；类型 delta 仍只是该 vid 的事件投影，不等于 DB 八项计数真值"
        );
    }

    private VideoStatusEventType parseEventType(String eventType) {
        if (eventType == null) {
            return null;
        }
        try {
            return VideoStatusEventType.valueOf(eventType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private VideoStatusReconciliationVO.RelationTruth readRelations(
            int vid,
            int cap,
            List<String> uncertainties
    ) {
        VideoStatusReconciliationRelationAggregate aggregate =
                reconciliationMapper.selectRelationAggregate(vid, cap);
        if (aggregate == null) {
            uncertainties.add(RELATION_TRUTH_UNAVAILABLE);
            return new VideoStatusReconciliationVO.RelationTruth(
                    false, false, false, false, 0L, 0L, 0L,
                    BigInteger.ZERO, 0L,
                    "关系真值查询没有返回聚合行；不可用于结论"
            );
        }

        long sampledRows = nullToZero(aggregate.getSampledRows());
        boolean truncated = sampledRows >= cap;
        if (truncated) {
            uncertainties.add(RELATION_SAMPLE_TRUNCATED);
        }

        return new VideoStatusReconciliationVO.RelationTruth(
                true,
                truncated,
                truncated,
                !truncated,
                sampledRows,
                nullToZero(aggregate.getLikedRows()),
                nullToZero(aggregate.getUnlikedRows()),
                BigInteger.valueOf(nullToZero(aggregate.getCoinSum())),
                nullToZero(aggregate.getCollectRows()),
                truncated
                        ? "关系采样达到 cap=" + cap + "，liked/unliked/coin/collect 只是下界，明确不可用于结论"
                        : "关系采样未截断；仍是读取时刻的业务关系，不代表与事件账本同一事务"
        );
    }

    private VideoStatusReconciliationVO.RedisSample readRedisSample(
            int vid,
            List<String> uncertainties
    ) {
        List<String> values;
        try {
            values = redisHashOperations.multiGet(
                    RedisKeyUtils.videoStatus(vid),
                    REDIS_COUNTER_FIELDS
            );
        } catch (RedisOperationException e) {
            uncertainties.add(REDIS_READ_FAILED);
            return redisFailure(
                    REDIS_STATUS_READ_FAILED,
                    List.of(),
                    "Redis 读取异常；计数未知，不当作 0，也不推断收敛"
            );
        }

        if (values == null || values.isEmpty()) {
            uncertainties.add(REDIS_NOT_SAMPLED);
            return redisFailure(
                    REDIS_STATUS_NOT_SAMPLED,
                    List.of(),
                    "Redis 当前版本未采样到该 vid 的 Hash；计数未知，不当作 0"
            );
        }
        if (values.size() != REDIS_COUNTER_FIELDS.size()) {
            uncertainties.add(REDIS_SAMPLE_INCOMPLETE);
            return redisFailure(
                    REDIS_STATUS_INCOMPLETE,
                    REDIS_COUNTER_FIELDS,
                    "Redis 返回字段数量与八个计数不一致；计数未知，不当作 0"
            );
        }

        List<String> missingFields = new ArrayList<>();
        boolean anyPresent = false;
        for (int i = 0; i < values.size(); i++) {
            String raw = values.get(i);
            if (raw == null || raw.isBlank()) {
                missingFields.add(REDIS_COUNTER_FIELDS.get(i));
            } else {
                anyPresent = true;
            }
        }
        if (!anyPresent) {
            uncertainties.add(REDIS_NOT_SAMPLED);
            return redisFailure(
                    REDIS_STATUS_NOT_SAMPLED,
                    List.of(),
                    "Redis Hash 的八个计数字段都缺失；计数未知，不当作 0"
            );
        }
        if (!missingFields.isEmpty()) {
            uncertainties.add(REDIS_SAMPLE_INCOMPLETE);
            return redisFailure(
                    REDIS_STATUS_INCOMPLETE,
                    missingFields,
                    "Redis Hash 只有部分计数字段；计数未知，不当作 0"
            );
        }

        Long[] parsed = new Long[REDIS_COUNTER_FIELDS.size()];
        List<String> invalidFields = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            try {
                parsed[i] = parseCanonicalCounter(values.get(i));
            } catch (VideoStatusCounterRangeException e) {
                invalidFields.add(REDIS_COUNTER_FIELDS.get(i));
            }
        }
        if (!invalidFields.isEmpty()) {
            uncertainties.add(REDIS_SAMPLE_INVALID);
            return redisFailure(
                    REDIS_STATUS_INVALID,
                    invalidFields,
                    "Redis 计数不是 canonical 非负 Long（可能溢出/负数/前导零）；计数未知，不当作 0"
            );
        }

        return new VideoStatusReconciliationVO.RedisSample(
                REDIS_STATUS_SAMPLED,
                parsed[0], parsed[1], parsed[2], parsed[3],
                parsed[4], parsed[5], parsed[6], parsed[7],
                List.of(),
                "Redis current Hash 的只读采样；不是与 DB/账本同一时刻的快照，不能推断已收敛"
        );
    }

    /**
     * 只接受 canonical 非负十进制 Long：不允许空白、正号、前导零、小数或科学计数法。
     * 解析失败/负数/超过 Long.MAX_VALUE 抛 {@link VideoStatusCounterRangeException}。
     */
    private Long parseCanonicalCounter(String raw) {
        long value = VideoStatusCounterRange.parse(raw, "redisCounter");
        if (!raw.equals(Long.toString(value))) {
            throw new VideoStatusCounterRangeException(
                    "redisCounter",
                    VideoStatusCounterRangeException.Reason.MALFORMED,
                    "Redis 计数不是 canonical 十进制 Long"
            );
        }
        return value;
    }

    private VideoStatusReconciliationVO.RedisSample redisFailure(
            String status,
            List<String> invalidFields,
            String note
    ) {
        return new VideoStatusReconciliationVO.RedisSample(
                status,
                null, null, null, null, null, null, null, null,
                List.copyOf(invalidFields),
                note
        );
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
