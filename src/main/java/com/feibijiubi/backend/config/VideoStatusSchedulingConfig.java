package com.feibijiubi.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ErrorHandler;

/**
 * T06 视频统计链路的调度资源隔离。
 *
 * <p>三条调度职责各自拥有一条<b>独立的单线程</b>调度器：</p>
 * <ul>
 *   <li>{@value #RELAY_SCHEDULER_BEAN}：Outbox → RabbitMQ 投递，最容易被 publish confirm 卡住；</li>
 *   <li>{@value #FLUSH_SCHEDULER_BEAN}：批量落库，持有 vid 锁并开数据库事务；</li>
 *   <li>{@value #RECOVERY_SCHEDULER_BEAN}：dirty vid 恢复，只读数据库 + 写 Redis Set。</li>
 * </ul>
 *
 * <p>为什么必须分开而不是共用 Spring 默认的 {@code taskScheduler}：默认调度器只有一条线程，
 * 投递 confirm 阻塞 30 秒时，落库与恢复的心跳也会一起停摆——已经消费成功的事件无法在预算内
 * 落库，监控还会误判“整条链路已死”。分开之后，三条线程互不影响，总线程数为 3（外加 T05 的
 * 单条 metrics 采样线程），不随视频数量增长。</p>
 *
 * <p>为什么每条池子只有 1 条线程：{@code @Scheduled(fixedDelay)} 语义是“上一轮结束后才安排下一轮”，
 * 配合单线程即可保证同一职责永不重叠执行；多线程只会让两个实例内的两个 worker 争同一批
 * 租约/vid 锁，收益小于“重叠任务失控”的风险。跨实例的互斥仍由 Outbox 租约 token 与 vid 锁负责。</p>
 *
 * <p>关于队列：{@link java.util.concurrent.ScheduledThreadPoolExecutor} 的内部队列按 JDK 设计是
 * <b>无界</b>的，本类<b>没有</b>把它改成有界队列，也不声称这是通用有界队列方案。这里能接受它的原因
 * 是：本类创建的调度器只承载 3 个固定的重复任务（每个池子 1 个），业务代码不会向它逐条提交任务，
 * 因此队列长度不随消息/视频数量增长；{@code fixedDelay} 也不会在上一轮未结束时堆积下一轮。
 * 真正的“每轮工作量”由 {@code app.video-status.round-budget-ms} 与各批量上限约束。</p>
 */
@Slf4j
@Configuration
public class VideoStatusSchedulingConfig {

    public static final String RELAY_SCHEDULER_BEAN = "videoStatusRelayScheduler";
    public static final String FLUSH_SCHEDULER_BEAN = "videoStatusFlushScheduler";
    public static final String RECOVERY_SCHEDULER_BEAN = "videoStatusRecoveryScheduler";
    /** T09：status0 补偿独立调度器，不与投递/落库/恢复共享线程。 */
    public static final String COMPENSATION_SCHEDULER_BEAN =
            "videoStatusCompensationScheduler";

    public static final String RELAY_THREAD_NAME = "video-status-relay";
    public static final String FLUSH_THREAD_NAME = "video-status-flush";
    public static final String RECOVERY_THREAD_NAME = "video-status-recovery";
    public static final String COMPENSATION_THREAD_NAME = "video-status-compensation";

    /** 每条调度器只有 1 条线程：同一职责不重叠，也不做无界并发。 */
    public static final int SCHEDULER_POOL_SIZE = 1;

    /** 停机时等待运行中任务退出的上限（秒）。 */
    public static final int SHUTDOWN_AWAIT_SECONDS = 5;

    @Bean(name = RELAY_SCHEDULER_BEAN)
    public ThreadPoolTaskScheduler videoStatusRelayScheduler() {
        return scheduler(RELAY_THREAD_NAME);
    }

    @Bean(name = FLUSH_SCHEDULER_BEAN)
    public ThreadPoolTaskScheduler videoStatusFlushScheduler() {
        return scheduler(FLUSH_THREAD_NAME);
    }

    @Bean(name = RECOVERY_SCHEDULER_BEAN)
    public ThreadPoolTaskScheduler videoStatusRecoveryScheduler() {
        return scheduler(RECOVERY_THREAD_NAME);
    }

    @Bean(name = COMPENSATION_SCHEDULER_BEAN)
    public ThreadPoolTaskScheduler videoStatusCompensationScheduler() {
        return scheduler(COMPENSATION_THREAD_NAME);
    }

    private ThreadPoolTaskScheduler scheduler(String threadName) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_POOL_SIZE);
        // 固定线程名前缀：线程 dump / 日志能直接看出是哪条调度卡住。
        scheduler.setThreadNamePrefix(threadName + "-");
        // 取消/停机时立刻从队列移除已取消的任务，避免 shutdown 后残留引用。
        scheduler.setRemoveOnCancelPolicy(true);
        // 优雅停机：先停止安排新一轮，再最多等待 SHUTDOWN_AWAIT_SECONDS 秒让运行中的一轮结束。
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        // 任务异常隔离：单轮抛异常只记日志，不静默杀死后续轮次，也不影响其它两条调度器。
        scheduler.setErrorHandler(new SchedulerErrorHandler(threadName));
        return scheduler;
    }

    /**
     * 自定义 ErrorHandler：默认实现同样只打日志，这里显式化是为了让日志带上“哪条调度器 +
     * 哪条线程”，并明确<b>吞掉</b>异常——调度任务靠 {@code fixedDelay} 重新排期，
     * 抛给线程池不会让后续轮次更好，只会丢掉日志上下文。
     */
    static final class SchedulerErrorHandler implements ErrorHandler {

        private final String role;

        SchedulerErrorHandler(String role) {
            this.role = role;
        }

        @Override
        public void handleError(Throwable throwable) {
            log.error(
                    "视频统计调度任务异常，本轮中止但后续轮次继续: scheduler={}, thread={}",
                    role,
                    Thread.currentThread().getName(),
                    throwable
            );
        }
    }
}
