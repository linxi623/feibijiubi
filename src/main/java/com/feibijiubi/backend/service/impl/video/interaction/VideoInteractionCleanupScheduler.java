package com.feibijiubi.backend.service.impl.video.interaction;

import com.feibijiubi.backend.config.VideoInteractionCleanupSchedulingConfig;
import com.feibijiubi.backend.config.VideoInteractionProperties;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T15 交互表清理调度器：默认关闭，开启后固定 {@code cleanup-fixed-delay-ms}（默认 60000）跑一轮。
 *
 * <p>运行期开关（{@code app.video-interaction.cleanup-enabled}）而不是 {@code @ConditionalOnProperty}：
 * 关闭是<b>默认值</b>，属性本身仍要被 {@code VideoInteractionProperties#validate()} 校验，
 * 也不希望“关掉清理”变成“连 bean 都不存在、配置写错了也没人报”。</p>
 *
 * <p>日志行 {@code video_interaction_cleanup_round ...} 是 T16 的采样输入；只有真的删了行或某个批次取满时
 * 才打 INFO，避免每分钟一条无事发生的噪声。异常只记类型、下一轮重试（没有游标/水位需要修复）。</p>
 *
 * <p><b>bean 名边界</b>：本类（{@code @Component}）的 bean 名是 {@code videoInteractionCleanupScheduler}；
 * 它使用的线程池是另一个 bean {@code videoInteractionCleanupTaskScheduler}（见
 * {@link VideoInteractionCleanupSchedulingConfig}）。两者**不能同名**，否则 Spring Boot 默认关闭 bean 覆盖时
 * 启动直接失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoInteractionCleanupScheduler {

    private final VideoInteractionCleanupService cleanupService;
    private final VideoInteractionProperties properties;

    /**
     * 占位符里的 {@code :60000} 与 {@code VideoInteractionProperties#cleanupFixedDelayMs} 的字段默认值
     * {@code 60_000L} 是<b>两份独立的字面量</b>：{@code @Scheduled} 从 {@code Environment} 解析占位符，
     * 不读 {@code @ConfigurationProperties} bean，所以改字段默认值不会自动改调度间隔。两边必须一起改
     * （取值不一致时以属性/Environment 为准，不会启动报错）。
     */
    @Scheduled(
            fixedDelayString = "${app.video-interaction.cleanup-fixed-delay-ms:60000}",
            scheduler = VideoInteractionCleanupSchedulingConfig.CLEANUP_TASK_SCHEDULER_BEAN
    )
    public void cleanupInteractionTables() {
        if (!properties.isCleanupEnabled()) {
            return;
        }
        try {
            VideoInteractionCleanupService.RoundResult result = cleanupService.cleanupOnce();
            if (result.totalDeleted() > 0 || result.requestBatchFull() || result.windowBatchFull()) {
                log.info("video_interaction_cleanup_round requestsDeleted={} windowsDeleted={} "
                                + "requestBatchFull={} windowBatchFull={}",
                        result.countedRequestsDeleted() + result.suppressedRequestsDeleted(),
                        result.windowsDeleted(),
                        result.requestBatchFull(), result.windowBatchFull());
            }
        } catch (RuntimeException e) {
            // 下一轮重新从候选查询开始；不打印异常文本，避免把 SQL 片段/参数写进日志。
            log.error("交互表清理单轮异常，下一轮重试: class={}",
                    e.getClass().getSimpleName());
        }
    }
}
