package com.feibijiubi.backend.service.video.videostatus;

import com.feibijiubi.backend.vo.VideoStatusReconciliationVO;

/**
 * T10 单视频只读事件对账。
 *
 * <p>只读取 DB 八项计数、账本状态数量、安全事件投影、业务关系有界真值和 Redis
 * current Hash 采样；不写 Redis/MySQL、不修复事件、不改变任何状态，也不返回
 * payload/error/token。所有查询有界，结果必须携带并发非原子与截断/未知标记。</p>
 */
public interface VideoStatusReconciliationService {

    int MIN_LIMIT = 1;
    int MAX_LIMIT = 1000;

    /**
     * @param vid   正整数视频 ID
     * @param limit 1..1000，账本/事件/关系查询最多读取 {@code limit + 1} 行
     */
    VideoStatusReconciliationVO reconcile(int vid, int limit);
}
