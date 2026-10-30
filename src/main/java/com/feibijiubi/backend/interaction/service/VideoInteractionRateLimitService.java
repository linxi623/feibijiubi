package com.feibijiubi.backend.interaction.service;

/**
 * T15 第二阶段：播放/分享的 Redis <b>准入</b>（固定窗口限流）。
 *
 * <p>它只决定“这次请求还允不允许往下走”，<b>不参与去重</b>：滚动 30 分钟窗口和
 * {@code requestKey} 幂等全部由 MySQL（{@code VideoInteractionService}）负责。
 * 反之亦然——数据库幂等表不负责限流。两层职责不重叠，Redis 里的计数被清空/丢失也不会让同一个
 * 请求被计两次（最多是这段时间的准入额度被重置）。</p>
 *
 * <p>两个维度都必须检查：同一 actor 的同一动作每窗口上限，以及同一 actor 的所有动作合计上限。</p>
 *
 * <p><b>fail-closed</b>：Redis 异常、返回 {@code null} 或非法数值一律拒绝本次计数，
 * 抛可重试的 503，不吞异常、不降级放行。代价是 Redis 故障期间互动计数整体不可用（这是已批准
 * 的产品选择：宁可拒绝，也不要在无法准入时放开计数）。</p>
 */
public interface VideoInteractionRateLimitService {

    /**
     * 检查并消耗本次请求的准入额度。
     *
     * @param actorHash 规范化 64 hex actor 哈希（登录 uid 或匿名访客 UUID 的域分离哈希）
     * @param action    本次动作，决定“单动作”维度的键
     * @throws com.feibijiubi.backend.shared.BusinessException 429 超过任一维度上限
     * @throws com.feibijiubi.backend.infrastructure.redis.RedisOperationException Redis 异常或返回非法值（503）
     */
    void check(String actorHash, VideoInteractionAction action);
}
