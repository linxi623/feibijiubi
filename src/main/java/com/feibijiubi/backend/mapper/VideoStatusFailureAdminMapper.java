package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoStatusConsumptionRepairLog;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminRow;
import com.feibijiubi.backend.service.admin.VideoStatusStatus3ReplayRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * T10 失败诊断与处置 mapper。
 *
 * <p>第一阶段（诊断/确认已阅）只有安全查询与审计插入；T10-B1 追加了 consumed status3
 * 受控重放所需的语句，共三条写语句，全部带精确 CAS 条件：</p>
 * <ul>
 *   <li>{@code resetConsumedRepairToReceivedForReplay}：仅 {@code process_status=3} 且
 *       {@code redis_applied_at IS NULL} 且 payload_hash 与本次校验值一致时 3→0；</li>
 *   <li>{@code resetOutboxSentToPendingForReplay}：仅 {@code status=2 SENT} 时退回 PENDING，
 *       让既有 relay 负责可靠投递；</li>
 *   <li>{@code insertCompletedRepairLog}：与上面两条同一事务写 COMPLETED 审计。</li>
 * </ul>
 * <p>没有改计数、改 Redis、改 payload 或新增 eventId 的语句。</p>
 */
@Mapper
public interface VideoStatusFailureAdminMapper {

    List<VideoStatusFailureAdminRow.OutboxRow> selectFailedOutbox(
            @Param("afterId") long afterId, @Param("limit") int limit);

    VideoStatusFailureAdminRow.OutboxRow selectFailedOutboxByEventId(
            @Param("eventId") String eventId);

    VideoStatusFailureAdminRow.OutboxRow selectFailedOutboxByEventIdForUpdate(
            @Param("eventId") String eventId);

    Integer selectConsumedProcessStatus(@Param("eventId") String eventId);

    List<VideoStatusFailureAdminRow.ConsumedRow> selectDiagnosableConsumed(
            @Param("afterId") long afterId, @Param("limit") int limit);

    VideoStatusFailureAdminRow.ConsumedRow selectDiagnosableConsumedByEventId(
            @Param("eventId") String eventId);

    VideoStatusFailureAdminRow.ConsumedRow selectConsumedByEventIdForUpdate(
            @Param("eventId") String eventId);

    VideoStatusStatus3ReplayRow.ConsumedTarget selectStatus3ReplayTarget(
            @Param("eventId") String eventId);

    VideoStatusStatus3ReplayRow.ConsumedTarget selectStatus3ReplayTargetForUpdate(
            @Param("eventId") String eventId);

    VideoStatusStatus3ReplayRow.OutboxSource selectOutboxReplaySource(
            @Param("eventId") String eventId);

    VideoStatusStatus3ReplayRow.OutboxSource selectOutboxReplaySourceForUpdate(
            @Param("eventId") String eventId);

    int countReplayAttempts(@Param("eventId") String eventId,
                            @Param("action") String action,
                            @Param("cap") int cap);

    int resetConsumedRepairToReceivedForReplay(@Param("eventId") String eventId,
                                               @Param("payloadHash") String payloadHash);

    int resetOutboxSentToPendingForReplay(@Param("eventId") String eventId);

    VideoStatusConsumptionRepairLog selectRepairLogByOperationId(
            @Param("operationId") String operationId);

    VideoStatusConsumptionRepairLog selectRepairLogByOperationIdForUpdate(
            @Param("operationId") String operationId);

    List<String> selectIndexColumns(@Param("tableName") String tableName,
                                    @Param("indexName") String indexName);

    int insertCompletedRepairLog(VideoStatusConsumptionRepairLog log);
}