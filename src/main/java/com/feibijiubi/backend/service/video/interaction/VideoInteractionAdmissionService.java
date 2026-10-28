package com.feibijiubi.backend.service.video.interaction;

import com.feibijiubi.backend.vo.VideoInteractionVO;

/**
 * T15 第二阶段：播放/分享计数的 HTTP 入口编排（“准入 seam”）。
 *
 * <p>它自己<b>不</b>开事务、也<b>不</b>加 {@code @Transactional}：真正的写事务在
 * {@link VideoInteractionService#record} 内部（{@code TransactionTemplate}），并且 {@code record}
 * 要求调用方不在事务里（并发同键冲突需要先整体回滚再读赢家）。给本类加外层事务会让每次调用都直接
 * 抛 {@link IllegalStateException}。</p>
 *
 * <h2>固定顺序（每一步都有理由，不能重排）</h2>
 * <ol>
 *   <li><b>参数校验</b>（{@code requestKey} / {@code vid} / 动作，以及身份解析）：
 *       必须早于任何 Redis 计数，否则一个非法请求会先吃掉一份准入额度；</li>
 *   <li><b>视频公开有效性校验</b>：保持原有 {@code validateVideo} 约束（400 / 404 / 500）；</li>
 *   <li><b>已提交请求查重放</b>（只读 MySQL）：命中就直接返回历史结果，<b>不访问 Redis</b>。
 *       否则客户端每重试一次就要再消耗一份额度，还会把“上次被窗口抑制”的结果变成一次新判定；</li>
 *   <li><b>Redis 准入限流</b>（仅对新请求）：两维固定窗口，超过即 429；Redis 异常 fail-closed 503；</li>
 *   <li><b>record</b>：同一事务完成占位 → 窗口判定 → 计数 → 终态。</li>
 * </ol>
 */
public interface VideoInteractionAdmissionService {

    /**
     * 记录一次播放/分享计数请求。
     *
     * @param action        只能传 {@link VideoInteractionAction#PLAY} 或 {@code SHARE}
     * @param currentUserId {@code LoginInterceptor} 写入的登录 uid；{@code null} = 匿名
     * @param vid           目标视频
     * @param requestKey    {@code Idempotency-Key} 请求头（1..64 个安全字符，大小写敏感）
     * @param visitorId     {@code X-Visitor-Id} 请求头；登录态下完全忽略
     * @throws com.feibijiubi.backend.common.BusinessException 400 参数/身份头非法；
     *         404 视频不存在；409 {@code requestKey} 形状冲突；
     *         429 准入超限；500 计数或写入异常
     * @throws com.feibijiubi.backend.common.RedisOperationException 准入 Redis 异常（503，可重试）
     */
    VideoInteractionVO record(VideoInteractionAction action,
                              Integer currentUserId,
                              Integer vid,
                              String requestKey,
                              String visitorId);
}
