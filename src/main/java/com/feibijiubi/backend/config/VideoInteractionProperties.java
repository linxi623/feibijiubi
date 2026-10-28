package com.feibijiubi.backend.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T15：播放/分享交互的准入（限流）与 30 天有界清理的运行参数。
 *
 * <p>这里描述 <b>准入</b>与<b>清理</b>两类运行参数，不描述去重语义：滚动 30 分钟“同一 actor + vid + action
 * 计一次”是已批准的产品语义，写死在 {@code VideoInteractionServiceImpl#WINDOW_MINUTES}，不是配置项；
 * 两张表的 30 天保留期同样写死在 {@code VideoInteractionCleanupService}，也不是配置项。本类只管
 * “短时间窗口内允许发起多少次请求”（防止单个 actor 用大量不同的 {@code requestKey} 把数据库幂等表/窗口行
 * 刷爆）和“清理是否开启、每批多大、多久一轮”。</p>
 *
 * <h2>两个维度必须都在</h2>
 * <ul>
 *   <li>{@code max-requests-per-action}：同一 actor 对<b>同一动作</b>（PLAY / SHARE）的每窗口上限；</li>
 *   <li>{@code max-requests-per-actor}：同一 actor <b>所有动作合计</b>的每窗口上限，
 *       防止“每个动作各刷满一份额度”绕开单动作上限。</li>
 * </ul>
 * <p>额度是 <b>per actor</b> 的，不是全站或按 vid 共享：热门视频的所有观众共用一个额度会把正常
 * 流量判成超限，这是本设计明确要避免的误伤。</p>
 *
 * <p>取值在启动期校验（{@link #validate()}），不带着错误配置进入运行期；这里没有新增任何密钥，
 * 也没有对应的 {@code application.yml} 条目——默认值就是生效值，改配置是部署决定。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.video-interaction")
public class VideoInteractionProperties {

    /** 窗口长度上限（秒）：一小时已远超任何“短时间准入”语义。 */
    public static final int MAX_WINDOW_SECONDS = 3_600;
    /** 每窗口请求数上限的上限：拦截多写一个 0 的笔误，不是性能调优旋钮。 */
    public static final int MAX_REQUESTS_PER_WINDOW = 10_000;
    /**
     * 清理单批上限的上限：一条 DELETE 的影响行数直接决定它与 {@code record} 事务的锁等待时长，
     * 所以这不是容量旋钮，而是“单语句工作量”的硬上界。
     */
    public static final int MAX_CLEANUP_BATCH_SIZE = 5_000;

    /** 准入窗口长度（秒），默认 60。 */
    private int windowSeconds = 60;
    /** 同一 actor 对同一动作的每窗口请求上限，默认 60。 */
    private int maxRequestsPerAction = 60;
    /** 同一 actor 所有动作合计的每窗口请求上限，默认 120。 */
    private int maxRequestsPerActor = 120;

    /**
     * T15 清理开关，默认 <b>false</b>：关闭时调度器与服务都不执行任何删除，表结构与计数语义完全不变。
     *
     * <p>开启前必须先执行 {@code database/migrations/t15-video-interaction-cleanup-indexes.sql}：
     * 清理的候选查询带 {@code FORCE INDEX}，缺索引会直接报错而不是静默退化成全表扫描。</p>
     */
    private boolean cleanupEnabled;
    /** 清理每批（每个语句）最多删除的候选行数，默认 500。 */
    private int cleanupBatchSize = 500;
    /** 清理轮次间隔（毫秒），默认 60000（一分钟一轮）。 */
    private long cleanupFixedDelayMs = 60_000L;

    @PostConstruct
    public void validate() {
        requireRange("windowSeconds", windowSeconds, 1, MAX_WINDOW_SECONDS);
        requireRange("maxRequestsPerAction", maxRequestsPerAction,
                1, MAX_REQUESTS_PER_WINDOW);
        requireRange("maxRequestsPerActor", maxRequestsPerActor,
                1, MAX_REQUESTS_PER_WINDOW);
        requireRange("cleanupBatchSize", cleanupBatchSize, 1, MAX_CLEANUP_BATCH_SIZE);
        if (cleanupFixedDelayMs < 1L) {
            // 只校验“正数”：取值偏大只是清理变慢，取值 <= 0 却会让调度器陷入热循环。
            // 保留期本身不是配置项（见 VideoInteractionCleanupService），所以这里没有 retention-days 校验。
            throw new IllegalStateException(
                    "app.video-interaction.cleanup-fixed-delay-ms 必须是正数，当前值："
                            + cleanupFixedDelayMs
            );
        }
        if (maxRequestsPerActor < maxRequestsPerAction) {
            // 合计额度小于单动作额度时，单动作额度永远不可能先到，等于被静默关掉一个维度；
            // 这种配置一定是写错，直接启动失败而不是按“更严的那个”猜意图。
            throw new IllegalStateException(
                    "app.video-interaction.max-requests-per-actor 必须 >= "
                            + "max-requests-per-action（当前合计 = " + maxRequestsPerActor
                            + "，单动作 = " + maxRequestsPerAction + "）"
            );
        }
    }

    private void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalStateException(
                    "app.video-interaction." + kebab(name) + " 必须在 " + min
                            + " 到 " + max + " 之间，当前值：" + value
            );
        }
    }

    private String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }
}
