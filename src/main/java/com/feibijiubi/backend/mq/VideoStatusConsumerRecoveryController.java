package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T08：消费转发失败后的受控暂停与异步恢复。
 *
 * <p><b>为什么不能在 listener 线程直接 {@code container.stop()}</b>：Spring AMQP 3.2.11
 * 没有 {@code pause()} API；{@code stop()} 会等待消费者线程退出，而当前正在执行的 listener
 * 就是被等待的线程，容易出现阻塞到 shutdown timeout 甚至死锁。因此这里只<b>登记</b>一次恢复请求，
 * 真正的 stop/start 放在专用单线程调度器上执行。</p>
 *
 * <p><b>为什么不 requeue</b>：接续投递失败通常是 broker/网络故障。立即
 * {@code basicNack(requeue=true)} 会让同一条消息立刻回到队列并被再次投递，形成无界热循环。
 * 本类配合 {@link VideoStatusEventConsumer}：转发失败时不 ACK、不 NACK，保留在途消息；
 * 异步 {@code stop()} 关闭 channel 后，broker 会重新投递未确认消息（至少一次语义）。</p>
 *
 * <p><b>并发边界</b>：{@link #recoveryInProgress} 是原子 gate。恢复周期内后续失败只记日志，
 * 不再排队；因此调度器里最多只有一项停止/恢复任务。单线程 + 固定 recovery delay 限制了尝试率。
 * JDK 的延时队列本身不提供容量参数，这里通过“固定任务形态 + 原子 gate”保证业务上不会增长，
 * 不把它冒充成通用有界线程池。停机时 {@link #destroy()} 关闭调度器并置 closed，恢复任务不会再
 * 启动已经随 Spring 容器关闭的 listener。</p>
 */
@Slf4j
@Component
public class VideoStatusConsumerRecoveryController implements DisposableBean {

    static final String THREAD_NAME = "video-status-consumer-recovery";
    private static final long SHUTDOWN_AWAIT_SECONDS = 5L;

    private final RabbitListenerEndpointRegistry listenerRegistry;
    private final VideoStatusProperties properties;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean recoveryInProgress = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Autowired
    public VideoStatusConsumerRecoveryController(
            RabbitListenerEndpointRegistry listenerRegistry,
            VideoStatusProperties properties
    ) {
        this(
                listenerRegistry,
                properties,
                Executors.newSingleThreadScheduledExecutor(new RecoveryThreadFactory())
        );
    }

    /**
     * 测试构造：时钟/调度器可控，不需要启动 RabbitMQ 或 Spring 容器。
     */
    VideoStatusConsumerRecoveryController(
            RabbitListenerEndpointRegistry listenerRegistry,
            VideoStatusProperties properties,
            ScheduledExecutorService scheduler
    ) {
        this.listenerRegistry = listenerRegistry;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    /**
     * 请求一次受控恢复。返回 false 表示已有恢复在进行或容器已关闭，本次请求被合并。
     */
    public boolean requestRecovery(String reason) {
        if (closed.get()) {
            log.warn("消费者恢复控制器已关闭，忽略恢复请求: {}", safeReason(reason));
            return false;
        }
        if (!recoveryInProgress.compareAndSet(false, true)) {
            log.warn(
                    "已有视频统计消费者恢复任务在进行，合并本次请求而不重复 stop/start: {}",
                    safeReason(reason)
            );
            return false;
        }

        MessageListenerContainer container;
        try {
            container = listenerRegistry.getListenerContainer(
                    RabbitConstants.CONSUMER_LISTENER_ID
            );
        } catch (RuntimeException e) {
            recoveryInProgress.set(false);
            log.error("查找视频统计消费者容器失败，无法恢复", e);
            return false;
        }

        if (container == null || !container.isRunning()) {
            recoveryInProgress.set(false);
            log.warn(
                    "视频统计消费者容器不存在或未运行，跳过恢复请求: listenerId={}, reason={}",
                    RabbitConstants.CONSUMER_LISTENER_ID,
                    safeReason(reason)
            );
            return false;
        }

        try {
            scheduler.schedule(
                    () -> stopAndScheduleRestart(container, reason),
                    0L,
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException e) {
            recoveryInProgress.set(false);
            log.error("提交视频统计消费者恢复任务被拒绝", e);
            return false;
        }

        log.warn(
                "已请求异步暂停视频统计消费者，避免转发失败后的立即 requeue 热循环: listenerId={}, reason={}",
                RabbitConstants.CONSUMER_LISTENER_ID,
                safeReason(reason)
        );
        return true;
    }

    /**
     * 只允许在专用调度线程执行。{@code stop()} 会等待 listener 线程退出，但这里不是 listener 线程，
     * 因此不会出现“自己等自己”的死锁。
     */
    private void stopAndScheduleRestart(
            MessageListenerContainer container,
            String reason
    ) {
        if (closed.get()) {
            recoveryInProgress.set(false);
            return;
        }

        try {
            container.stop();
            log.warn(
                    "视频统计消费者已异步停止，等待 {}ms 后恢复: reason={}",
                    properties.getConsumerRecoveryDelayMs(),
                    safeReason(reason)
            );
        } catch (Throwable stopError) {
            // stop 失败也要尝试恢复；如果容器仍在运行，restartTask 会通过 isRunning 判断并释放 gate。
            log.error("异步停止视频统计消费者失败，将按计划尝试恢复", stopError);
        }

        scheduleRestart(container, reason);
    }

    private void scheduleRestart(
            MessageListenerContainer container,
            String reason
    ) {
        if (closed.get()) {
            recoveryInProgress.set(false);
            return;
        }
        try {
            scheduler.schedule(
                    () -> restart(container, reason),
                    properties.getConsumerRecoveryDelayMs(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException e) {
            recoveryInProgress.set(false);
            log.error("提交视频统计消费者恢复任务被拒绝", e);
        }
    }

    private void restart(
            MessageListenerContainer container,
            String reason
    ) {
        if (closed.get()) {
            recoveryInProgress.set(false);
            return;
        }

        try {
            if (!container.isRunning() && !closed.get()) {
                container.start();
                log.info(
                        "视频统计消费者已恢复: listenerId={}, reason={}",
                        RabbitConstants.CONSUMER_LISTENER_ID,
                        safeReason(reason)
                );
            }
            recoveryInProgress.set(false);
        } catch (Throwable startError) {
            // 启动仍失败：保持 gate，只重新安排下一项恢复任务，永不并发多个恢复周期。
            log.error(
                    "恢复视频统计消费者失败，{}ms 后重试: listenerId={}",
                    properties.getConsumerRecoveryDelayMs(),
                    RabbitConstants.CONSUMER_LISTENER_ID,
                    startError
            );
            scheduleRestart(container, reason);
        }
    }

    boolean isRecoveryInProgress() {
        return recoveryInProgress.get();
    }

    @Override
    public void destroy() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        recoveryInProgress.set(false);
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(
                    SHUTDOWN_AWAIT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                log.warn("视频统计消费者恢复调度器未在 {} 秒内退出",
                        SHUTDOWN_AWAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    private static final class RecoveryThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        }
    }
}
