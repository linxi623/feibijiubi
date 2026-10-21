package com.feibijiubi.backend.metrics;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * T05 观测用的<b>只读</b> mapper。
 *
 * <p>硬约束：</p>
 * <ul>
 *   <li>只有 SELECT，没有 update/insert/delete：采集不得改变事件状态、
 *       不得推进队列、不得写修复日志；</li>
 *   <li>计数一律封顶（{@code LIMIT cap + 1} 后计数），禁止对无限增长大表
 *       做无界 {@code COUNT(*)}；</li>
 *   <li>最老时间走 {@code (status, created_at, id)} /
 *       {@code (process_status, consumed_at, id)} 索引，年龄由数据库
 *       {@code NOW(3)} 计算，避免应用与数据库时钟/时区差异；</li>
 *   <li>每条语句都带 {@code timeout}，采样卡住时失败而不是拖住监控线程。</li>
 * </ul>
 */
@Mapper
public interface VideoStatusMetricsMapper {

    /** Outbox 某状态的事件数，最多数到 {@code cap}。 */
    Integer countOutboxStatusCapped(
            @Param("status") int status,
            @Param("cap") int cap
    );

    /** Outbox 某状态最老事件的年龄（毫秒）；没有行时返回 null。 */
    Long selectOldestOutboxAgeMillis(@Param("status") int status);

    /** 消费事件某 process_status 的数量，最多数到 {@code cap}。 */
    Integer countConsumedStatusCapped(
            @Param("processStatus") int processStatus,
            @Param("cap") int cap
    );

    /** 消费事件某 process_status 最老事件的年龄（毫秒）；没有行时返回 null。 */
    Long selectOldestConsumedAgeMillis(@Param("processStatus") int processStatus);

    /** 必备索引是否存在（1 存在 / 0 不存在）；不存在时采样只给诊断，不扫表。 */
    int indexExists(
            @Param("tableName") String tableName,
            @Param("indexName") String indexName
    );
}
