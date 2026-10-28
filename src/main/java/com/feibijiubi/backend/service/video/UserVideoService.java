package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.vo.VideoInteractionVO;

public interface UserVideoService {

    /**
     * T15 第二阶段：播放计数走“请求幂等 + 滚动 30 分钟窗口 + Redis 准入”的编排。
     *
     * <p>旧的无 actor/无幂等键签名已删除，没有保留重载：留着它就等于留一条完全绕过防刷的计数入口，
     * 客户端（或新代码）只要调旧方法就能绕开准入。依赖方必须一并迁移。</p>
     *
     * @param currentUserId 登录 uid（来自 {@code LoginInterceptor}），{@code null} = 匿名
     * @param vid           目标视频（必须已过审且未删除）
     * @param requestKey    {@code Idempotency-Key} 请求头，同一业务请求的重试必须复用同一个值
     * @param visitorId     {@code X-Visitor-Id} 请求头（匿名必填，登录态忽略）
     */
    VideoInteractionVO increasePlayCount(Integer currentUserId, Integer vid,
                                         String requestKey, String visitorId);

    void savePlayProgress(Integer vid, Integer currentUserId, Double playTime);

    void recordLike(Integer currentUserId, Integer vid, Boolean islike, Boolean isSet);

    void increaseCoin(Integer currentUserId, Integer vid, Byte coin);

    /**
     * 分享计数：与 {@link #increasePlayCount} 同样的幂等/窗口/准入语义，只是动作维度不同。
     */
    VideoInteractionVO increaseShare(Integer currentUserId, Integer vid,
                                     String requestKey, String visitorId);

    void Collect(Integer currentUserId, Integer vid, Boolean isCollect);
}
