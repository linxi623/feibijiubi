package com.feibijiubi.backend.videosource.service.impl;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourcePreviewQuotaService;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * M3-B：复用既有 {@code lua/fixed-window-rate-limit.lua} 的<b>每管理员</b>固定窗口配额。
 *
 * <h2>窗口与上限</h2>
 * <p>窗口固定 {@value #WINDOW_SECONDS} 秒，上限取 {@code app.external-video-source.per-admin-requests-per-minute}
 * （默认 30，启动期校验 1..120）。窗口没有做成配置项：那个参数名本身就是"每分钟"，
 * 多一个窗口旋钮只会让"每分钟"变成一句不准确的描述。</p>
 *
 * <h2>键只含登录 uid</h2>
 * <p>键是 {@code rate:external-video-source:preview:admin:<uid>}，目录与详情<b>共用</b>同一个键，
 * 因此"每个管理员每窗口 N 次预览"是这两个端点的<b>合计</b>额度。键里不含 sourceCode、作品 ID、地址或
 * 任何上游内容，键空间只有管理员数量级。计数在 Redis 里，所以同一部署的多个实例共享它；
 * 这与"进程内并发信号量只限本实例"是两件事，不能合并成"全局并发上限"。</p>
 *
 * <h2>fail-closed 与不退回</h2>
 * <p>脚本返回 {@code null} 或负数一律 {@link RedisOperationException}（全局处理器映射 503），
 * 绝不"Redis 挂了就放行"；Redis 访问失败由 {@link RedisUtils} 转成同一个异常。额度在真正发上游请求之前
 * 消耗，之后上游失败也<b>不退回</b>：退回需要两阶段补偿，而补偿本身也可能失败，这里选择"宁可少放行"。</p>
 *
 * <h2>没有动的部分</h2>
 * <p>本类不改视频互动（T15）的限流维度、键或上限，只是新增一套独立前缀；也不做并发控制（那是预览服务的
 * 进程内信号量）。</p>
 */
@Service
public class ExternalVideoSourcePreviewQuotaServiceImpl implements ExternalVideoSourcePreviewQuotaService {

    /** 固定窗口长度（秒）：与 {@code per-admin-requests-per-minute} 的"每分钟"语义一致。 */
    static final long WINDOW_SECONDS = 60L;

    /** 统一的超限文案：不暴露剩余额度，也不区分是哪个端点消耗的。 */
    static final String OVER_LIMIT_MESSAGE = "外部来源预览请求过于频繁，请稍后再试";

    private final DefaultRedisScript<Long> fixedWindowRateLimitScript;
    private final RedisUtils redisUtils;
    private final ExternalVideoSourceProperties properties;

    public ExternalVideoSourcePreviewQuotaServiceImpl(
            @Qualifier("fixedWindowRateLimitScript") DefaultRedisScript<Long> fixedWindowRateLimitScript,
            RedisUtils redisUtils,
            ExternalVideoSourceProperties properties
    ) {
        this.fixedWindowRateLimitScript = fixedWindowRateLimitScript;
        this.redisUtils = redisUtils;
        this.properties = properties;
    }

    @Override
    public void checkAndConsume(Integer adminUserId) {
        if (adminUserId == null) {
            throw new BusinessException(401, "未登录：预览配额只按登录态管理员统计");
        }

        Long current = redisUtils.executeScript(
                fixedWindowRateLimitScript,
                Collections.singletonList(RedisKeyUtils.rateExternalVideoSourcePreview(adminUserId)),
                String.valueOf(WINDOW_SECONDS)
        );

        if (current == null || current < 0) {
            // 脚本被改名/被 ACL 拒绝/返回了别的东西：这是"无法准入"，不是"计数为 0"。
            throw new RedisOperationException(
                    "外部来源预览配额返回非法值：" + current + "（本次请求已被拒绝）");
        }

        if (current > properties.getPerAdminRequestsPerMinute()) {
            throw new BusinessException(429, OVER_LIMIT_MESSAGE);
        }
    }
}
