package com.feibijiubi.backend.service.impl.video.interaction;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.config.VideoInteractionProperties;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionAction;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionRateLimitService;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * T15 第二阶段：复用既有 {@code lua/fixed-window-rate-limit.lua} 的固定窗口准入。
 *
 * <p>每个维度一次脚本调用：{@code INCR} + 首帧/无 TTL 时 {@code EXPIRE}，返回窗口内当前计数。
 * 窗口长度取 {@code app.video-interaction.window-seconds}（默认 60），上限取
 * {@code max-requests-per-action} / {@code max-requests-per-actor}。</p>
 *
 * <h2>为什么不是“全站”或“按 vid”的额度</h2>
 * <p>按 actor 分配额度：热门视频的所有观众各算各的，不会被互相挤掉；全站或按 vid 共享一个额度
 * 会把正常流量判成超限，这是明确的误伤，不做。</p>
 *
 * <h2>fail-closed，以及它诚实的代价</h2>
 * <p>脚本异常、返回 {@code null} 或负数一律抛
 * {@link RedisOperationException}（全局异常处理器映射为 503，客户端可重试），绝不“Redis 挂了
 * 就放行”。要接受的代价有两条：</p>
 * <ol>
 *   <li><b>两个维度不是原子的</b>：先检查单动作维度、再检查合计维度。若第二个维度失败，
 *       第一个维度的计数可能已经 +1 但本次请求被拒绝。这只会让额度略微提前用完，不会多计业务
 *       次数（业务计数在 MySQL，本次请求根本没进去）。把两个维度合成一条新 Lua 会改动共享脚本
 *       的语义，超出本阶段范围。</li>
 *   <li><b>额度在业务成功之前消耗</b>：准入通过后如果数据库事务失败（例如视频刚被删除、
 *       {@code Long.MAX} 上界拒绝），额度不会退回。退回需要“先记后补偿”的两阶段协议，
 *       而补偿本身可能失败，反而更难解释；这里选择“宁可少放行几次”的保守方向。</li>
 * </ol>
 */
@Service
public class VideoInteractionRateLimitServiceImpl implements VideoInteractionRateLimitService {

    /** 统一的超限文案：两个维度共用，避免对外暴露“是哪个维度/还剩多少”这类探测信息。 */
    static final String OVER_LIMIT_MESSAGE = "操作过于频繁，请稍后再试";

    private final DefaultRedisScript<Long> fixedWindowRateLimitScript;
    private final RedisUtils redisUtils;
    private final VideoInteractionProperties properties;

    public VideoInteractionRateLimitServiceImpl(
            @Qualifier("fixedWindowRateLimitScript")
            DefaultRedisScript<Long> fixedWindowRateLimitScript,
            RedisUtils redisUtils,
            VideoInteractionProperties properties
    ) {
        this.fixedWindowRateLimitScript = fixedWindowRateLimitScript;
        this.redisUtils = redisUtils;
        this.properties = properties;
    }

    @Override
    public void check(String actorHash, VideoInteractionAction action) {
        enforce(RedisKeyUtils.rateVideoInteractionAction(actorHash, action.name()),
                properties.getMaxRequestsPerAction());
        enforce(RedisKeyUtils.rateVideoInteractionActor(actorHash),
                properties.getMaxRequestsPerActor());
    }

    /**
     * 消耗一次额度并判定。
     *
     * <p>返回值为空/负数说明脚本没有按契约返回计数（例如被改名、被 ACL 拒绝后返回了别的东西），
     * 这种情况必须当成“无法准入”，不能当成 0。</p>
     */
    private void enforce(String key, int maxRequests) {
        Long current = redisUtils.executeScript(
                fixedWindowRateLimitScript,
                Collections.singletonList(key),
                String.valueOf(properties.getWindowSeconds())
        );
        if (current == null || current < 0) {
            throw new RedisOperationException(
                    "交互准入限流返回非法值：" + current + "（本次计数已被拒绝）");
        }
        if (current > maxRequests) {
            throw new BusinessException(429, OVER_LIMIT_MESSAGE);
        }
    }
}
