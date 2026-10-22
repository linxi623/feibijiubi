package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.entity.VideoStatusReconciliationEventRow;
import com.feibijiubi.backend.entity.VideoStatusReconciliationRelationAggregate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * T10 事件对账只读 Mapper。
 *
 * <p>安全边界：</p>
 * <ul>
 *   <li>本接口只有 SELECT，没有 insert/update/delete，对账不会修复任何数据；</li>
 *   <li>所有查询都带 MyBatis {@code timeout}，并且最多读取 {@code limit + 1} 行；</li>
 *   <li>WHERE 条件都以 vid 开头或使用现有 {@code (process_status, vid, id)} 索引，
 *       事件窗口还需要 {@code (vid, id)} 索引，
 *       见 {@code database/migrations/t10-video-status-reconciliation-indexes.sql}；</li>
 *   <li>不选择 payload、last_error、租约 token 等敏感列。</li>
 * </ul>
 */
@Mapper
public interface VideoStatusReconciliationMapper {

    /** DB 侧八项计数；没有统计行时返回 null，调用方必须保留“未知”而不是当 0。 */
    VideoStatus selectDbCounts(@Param("vid") Integer vid);

    /**
     * 某个 process_status 的账本数量，最多扫描 {@code cap} 行。
     * 返回 {@code cap} 表示“至少 cap 条”，不是精确值。
     */
    long countLedgerByStatus(
            @Param("vid") Integer vid,
            @Param("processStatus") int processStatus,
            @Param("cap") int cap
    );

    /**
     * 安全事件投影窗口：按 id 倒序取最多 {@code limit} 行，只返回类型与增量。
     * 返回 {@code limit} 行且实际还有更早事件时，调用方必须标记截断。
     */
    List<VideoStatusReconciliationEventRow> selectEventProjection(
            @Param("vid") Integer vid,
            @Param("limit") int limit
    );

    /**
     * 业务关系真值的有界聚合：先 LIMIT {@code cap} 行，再聚合。
     * 结果可能只是下界，是否可作结论由服务层根据是否截断决定。
     */
    VideoStatusReconciliationRelationAggregate selectRelationAggregate(
            @Param("vid") Integer vid,
            @Param("cap") int cap
    );
}
