package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.metrics.VideoStatusBacklogSampler;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.vo.VideoStatusGateVO;
import com.feibijiubi.backend.vo.VideoStatusMetricsVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T05 只读指标接口（管理员）。
 *
 * <p>安全与只读边界：</p>
 * <ul>
 *   <li>路径在 {@code /api/admin/**} 下，由既有 {@code LoginInterceptor} +
 *       {@code AdminInterceptor} 拦截，并逐个方法标注 {@link AdminOnly}；
 *       不新增 actuator 或未鉴权端口；</li>
 *   <li>指标 GET 只返回<b>采样缓存</b>：不会在请求线程里查库、不写 Redis、
 *       不改事件状态，抓取频率再高也不增加数据库压力；</li>
 *   <li>门闩查询只做一次 Redis GET（无 TTL 读取/改写）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/video-status")
public class VideoStatusMetricsController {

    private static final String SCOPE_NOTE =
            "instanceTelemetry 仅代表本实例 operation 计数；backlog 为共享 MySQL 的全局积压采样值。"
                    + "两者不可相加；stale=true 时数字来自上次成功采样。";

    private final VideoStatusTelemetry telemetry;
    private final VideoStatusBacklogSampler backlogSampler;
    private final RedisUtils redisUtils;

    public VideoStatusMetricsController(VideoStatusTelemetry telemetry,
                                        VideoStatusBacklogSampler backlogSampler,
                                        RedisUtils redisUtils) {
        this.telemetry = telemetry;
        this.backlogSampler = backlogSampler;
        this.redisUtils = redisUtils;
    }

    /** 链路指标 + 积压告警。只读采样缓存，不触发数据库查询。 */
    @AdminOnly
    @GetMapping("/metrics")
    public ApiResponse<VideoStatusMetricsVO> getMetrics() {
        VideoStatusMetricsVO vo = new VideoStatusMetricsVO(
                SCOPE_NOTE,
                backlogSampler.snapshot(),
                telemetry.snapshot()
        );
        return ApiResponse.success(vo);
    }

    /**
     * 按需读取某个视频的重建门闩停留时间。
     *
     * <p>只读：不写、不删、不设置/刷新 TTL；老格式 token 的年龄返回 null
     * 并说明未知，不伪造数值。</p>
     */
    @AdminOnly
    @GetMapping("/gate")
    public ApiResponse<VideoStatusGateVO> getGate(@RequestParam("vid") Integer vid) {
        if (vid == null || vid <= 0) {
            throw new BusinessException(400, "vid 不合法");
        }
        String gateKey = RedisKeyUtils.videoStatusRebuildGate(vid);
        String token = redisUtils.getString(gateKey);
        long now = System.currentTimeMillis();
        if (token == null || token.isBlank()) {
            return ApiResponse.success(new VideoStatusGateVO(
                    vid,
                    false,
                    "ABSENT",
                    null,
                    "当前没有重建门闩"
            ));
        }
        Long createdAtMillis = parseEpochMillisToken(token);
        if (createdAtMillis == null) {
            return ApiResponse.success(new VideoStatusGateVO(
                    vid,
                    true,
                    "LEGACY_UNKNOWN",
                    null,
                    "老格式门闩 token 不含时间戳，停留时间未知；观测不加 TTL"
            ));
        }
        long ageMillis = Math.max(0L, now - createdAtMillis);
        return ApiResponse.success(new VideoStatusGateVO(
                vid,
                true,
                "EPOCH_MILLIS_TOKEN",
                ageMillis,
                "门闩无 TTL；崩溃后由下一位持锁者接管，不做自动过期"
        ));
    }

    /** 只解析 {@code <epochMillis>-<uuid>}；老格式一律返回 null（未知）。 */
    private Long parseEpochMillisToken(String token) {
        int separator = token.indexOf('-');
        if (separator <= 0 || separator == token.length() - 1) {
            return null;
        }
        String millisPart = token.substring(0, separator);
        if (millisPart.length() < 12 || millisPart.length() > 19
                || !millisPart.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            java.util.UUID.fromString(token.substring(separator + 1));
            long millis = Long.parseLong(millisPart);
            return millis > 0L ? millis : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
