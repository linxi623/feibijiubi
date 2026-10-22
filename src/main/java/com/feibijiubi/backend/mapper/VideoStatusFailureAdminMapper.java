package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoStatusConsumptionRepairLog;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** T10 第一阶段：只有安全查询与审计插入，没有改状态/计数/Redis 的语句。 */
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

    VideoStatusConsumptionRepairLog selectRepairLogByOperationId(
            @Param("operationId") String operationId);

    VideoStatusConsumptionRepairLog selectRepairLogByOperationIdForUpdate(
            @Param("operationId") String operationId);

    List<String> selectIndexColumns(@Param("tableName") String tableName,
                                    @Param("indexName") String indexName);

    int insertCompletedRepairLog(VideoStatusConsumptionRepairLog log);
}