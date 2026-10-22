package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T08 恢复控制器纯单元测试：不启动 Spring、不连接 RabbitMQ，手工执行被调度任务。
 */
class VideoStatusConsumerRecoveryControllerTests {

    private RabbitListenerEndpointRegistry registry;
    private MessageListenerContainer container;
    private ScheduledExecutorService scheduler;
    private VideoStatusProperties properties;
    private VideoStatusConsumerRecoveryController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = mock(RabbitListenerEndpointRegistry.class);
        container = mock(MessageListenerContainer.class);
        scheduler = mock(ScheduledExecutorService.class);
        when(registry.getListenerContainer(RabbitConstants.CONSUMER_LISTENER_ID))
                .thenReturn(container);

        properties = new VideoStatusProperties();
        properties.setConsumerRecoveryDelayMs(5_000L);
        controller = new VideoStatusConsumerRecoveryController(
                registry,
                properties,
                scheduler
        );
        when(scheduler.schedule(any(Runnable.class), anyLong(),
                eq(TimeUnit.MILLISECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
    }

    @Test
    void onlyOneRecoveryTaskIsAcceptedWhileGateIsHeld() {
        when(container.isRunning()).thenReturn(true);

        assertTrue(controller.requestRecovery("first"));
        assertTrue(controller.isRecoveryInProgress());
        assertFalse(controller.requestRecovery("second"));
        assertFalse(controller.requestRecovery("third"));

        verify(scheduler, times(1)).schedule(any(Runnable.class), eq(0L),
                eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void stopRunsOffListenerThreadThenRestartIsScheduledAfterBoundedDelay() {
        when(container.isRunning()).thenReturn(true, false, false);

        controller.requestRecovery("forward failed");
        Runnable stopTask = firstScheduledTask(0L);
        clearInvocations(scheduler);

        stopTask.run();

        verify(container).stop();
        ArgumentCaptor<Runnable> restartCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(
                restartCaptor.capture(),
                eq(5_000L),
                eq(TimeUnit.MILLISECONDS)
        );
        assertTrue(controller.isRecoveryInProgress());

        restartCaptor.getValue().run();

        verify(container).start();
        assertFalse(controller.isRecoveryInProgress());
    }

    @Test
    void stopFailureStillSchedulesRestartAndReleasesGateWhenAlreadyRunning() {
        when(container.isRunning()).thenReturn(true, true, true);
        doThrow(new IllegalStateException("stop failed"))
                .when(container).stop();

        controller.requestRecovery("forward failed");
        Runnable stopTask = firstScheduledTask(0L);
        clearInvocations(scheduler);
        stopTask.run();

        ArgumentCaptor<Runnable> restartCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(
                restartCaptor.capture(),
                eq(5_000L),
                eq(TimeUnit.MILLISECONDS)
        );

        restartCaptor.getValue().run();

        verify(container, never()).start();
        assertFalse(controller.isRecoveryInProgress());
    }

    @Test
    void startFailureKeepsGateAndSchedulesOnlyOneNextRecovery() {
        when(container.isRunning()).thenReturn(true, false, false);
        doThrow(new IllegalStateException("broker down"))
                .when(container).start();

        controller.requestRecovery("forward failed");
        Runnable stopTask = firstScheduledTask(0L);
        clearInvocations(scheduler);
        stopTask.run();
        Runnable restartTask = firstScheduledTask(5_000L);
        clearInvocations(scheduler);

        restartTask.run();

        verify(scheduler, times(1)).schedule(any(Runnable.class), eq(5_000L),
                eq(TimeUnit.MILLISECONDS));
        assertTrue(controller.isRecoveryInProgress());
        assertFalse(controller.requestRecovery("still down"));
    }

    @Test
    void alreadyStoppedContainerDoesNotScheduleAStop() {
        when(container.isRunning()).thenReturn(false);

        assertFalse(controller.requestRecovery("late failure"));
        assertFalse(controller.isRecoveryInProgress());
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(),
                any(TimeUnit.class));
    }

    @Test
    void destroyReleasesGateAndRejectsNewRequests() throws Exception {
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(container.isRunning()).thenReturn(true);

        controller.destroy();

        assertFalse(controller.requestRecovery("after shutdown"));
        verify(scheduler).shutdownNow();
    }

    @Test
    void concurrentRecoveryRequestsAcceptExactlyOneCycle() throws Exception {
        when(container.isRunning()).thenReturn(true);
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                int caller = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return controller.requestRecovery("burst-" + caller);
                }));
            }
            start.countDown();

            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }

            assertEquals(1, accepted,
                    "only one concurrent recovery request may own the cycle");
            assertTrue(controller.isRecoveryInProgress());
            verify(scheduler, times(1)).schedule(any(Runnable.class), eq(0L),
                    eq(TimeUnit.MILLISECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void destroyDuringInFlightRecoveryMakesPendingTasksNoOps() throws Exception {
        when(container.isRunning()).thenReturn(true, false, false);
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        controller.requestRecovery("forward failed");
        Runnable stopTask = firstScheduledTask(0L);
        clearInvocations(scheduler);

        controller.destroy();
        stopTask.run();

        verify(container, never()).stop();
        verify(container, never()).start();
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(),
                any(TimeUnit.class));
        assertFalse(controller.isRecoveryInProgress());
        assertFalse(controller.requestRecovery("after shutdown"));
    }

    private Runnable firstScheduledTask(long delayMillis) {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(captor.capture(), eq(delayMillis),
                eq(TimeUnit.MILLISECONDS));
        return captor.getValue();
    }
}
