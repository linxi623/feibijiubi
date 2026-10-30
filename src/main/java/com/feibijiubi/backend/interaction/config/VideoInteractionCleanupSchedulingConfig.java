package com.feibijiubi.backend.interaction.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ErrorHandler;

/**
 * T15 交互表清理的调度资源隔离。
 *
 * <p>清理线程<b>不</b>复用 {@link com.feibijiubi.backend.config.VideoStatusSchedulingConfig} 的四条视频统计调度器，也不复用 Spring 默认的
 * {@code taskScheduler}：清理是纯维护任务（一条 DELETE 可能在等交互事务的行锁），把它和投递/落库/恢复/补偿
 * 放进同一条线程，就等于让维护任务的动作时间出现在统计链路的心跳上。一条独立单线程的代价固定为 1，
 * 且不随视频数或请求数增长。</p>
 *
 * <p>为什么只有 1 条线程：{@code fixedDelay} 语义是“上一轮结束后才安排下一轮”，单线程即可保证同一职责永不
 * 重叠执行；多线程只会让同一批候选被多个 worker 争抢，没有正确性收益。跨实例并发由“按主键等值删除 + 守卫”
 * 自然吸收（另一个实例已经删掉的行影响行数为 0），所以这里<b>不</b>引入分布式锁——那只会多一个
 * “锁泄漏导致清理永久停摆”的故障模式。</p>
 */
@Slf4j
@Configuration
public class VideoInteractionCleanupSchedulingConfig {

    /**
     * 清理专用<b>线程池</b> bean 名（{@code @Scheduled(scheduler = ...)} 引用它）。
     *
     * <p><b>必须与调度组件 {@code VideoInteractionCleanupScheduler} 的 bean 名区分开</b>：组件是
     * {@code @Component}，默认 bean 名就是 {@code videoInteractionCleanupScheduler}。两者同名会得到两个
     * 同名 bean 定义，而 Spring Boot 默认关闭 bean 覆盖（{@code allow-bean-definition-overriding=false}），
     * 结果是应用启动失败（{@code BeanDefinitionOverrideException}）。这里用 {@code ...TaskScheduler} 后缀，
     * 并由 {@code VideoInteractionCleanupSchedulingWiringTests} 在真实 Spring 上下文里钉住这个区分。</p>
     */
    public static final String CLEANUP_TASK_SCHEDULER_BEAN = "videoInteractionCleanupTaskScheduler";
    /** 固定线程名前缀：线程 dump / 日志能直接看出是清理线程。 */
    public static final String CLEANUP_THREAD_NAME = "video-interaction-cleanup";

    /** 只有 1 条线程：同一职责不重叠，也不做无界并发。 */
    public static final int SCHEDULER_POOL_SIZE = 1;

    /** 停机时等待运行中任务退出的上限（秒）。 */
    public static final int SHUTDOWN_AWAIT_SECONDS = 5;

    /** 方法名与 {@link #CLEANUP_TASK_SCHEDULER_BEAN} 保持同步：bean 名以注解为准，方法名只是可读性。 */
    @Bean(name = CLEANUP_TASK_SCHEDULER_BEAN)
    public ThreadPoolTaskScheduler videoInteractionCleanupTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_POOL_SIZE);
        scheduler.setThreadNamePrefix(CLEANUP_THREAD_NAME + "-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        scheduler.setErrorHandler(new CleanupErrorHandler());
        return scheduler;
    }

    /**
     * 错误处理：单轮异常只记日志，靠 {@code fixedDelay} 重新排期。
     *
     * <p>只记录异常类型，不打印异常文本：SQL 异常消息里可能带语句片段，清理语句里带的是保留期阈值，
     * 没有诊断价值，却会把日志变成“看起来很严重”的噪声源。</p>
     */
    static final class CleanupErrorHandler implements ErrorHandler {

        @Override
        public void handleError(Throwable throwable) {
            log.error("交互表清理调度任务异常，本轮中止但后续轮次继续: thread={} class={}",
                    Thread.currentThread().getName(), throwable.getClass().getSimpleName());
        }
    }
}
