package com.feibijiubi.backend.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.config.VideoStatusMetricsConfig;
import com.feibijiubi.backend.config.VideoStatusProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T05：两个 Component 都只有多个显式构造器，没有无参构造。
 *
 * <p>用 ApplicationContextRunner 真实走一遍 Spring 构造器注入：如果生产构造器
 * 漏标 {@code @Autowired}，上下文会以 “No default constructor found” 启动失败。
 * mapper 与调度器都是 mock，不连接任何真实基础设施；调度器 mock 也保证
 * 不会真的启动自动采样线程。</p>
 */
class VideoStatusMetricsComponentWiringTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestInfrastructure.class);

    @Test
    void componentsAutowireThroughAnnotatedProductionConstructors() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(VideoStatusTelemetry.class);
            assertThat(context).hasSingleBean(VideoStatusBacklogSampler.class);

            VideoStatusBacklogSampler sampler = context.getBean(VideoStatusBacklogSampler.class);
            // 未发生过采样时也不能伪造健康数值。
            assertThat(sampler.snapshot().getState())
                    .isEqualTo(VideoStatusBacklogSampler.STATE_UNKNOWN);
        });
    }

    @Test
    void startupSchedulesSamplingOnInjectedMetricsExecutorOnly() {
        runner.run(context -> {
            ScheduledExecutorService executor = context.getBean(
                    VideoStatusMetricsConfig.METRICS_SCHEDULER_BEAN,
                    ScheduledExecutorService.class);

            verify(executor).scheduleWithFixedDelay(
                    any(Runnable.class),
                    eq(VideoStatusBacklogSampler.INITIAL_DELAY_MILLIS),
                    eq(VideoStatusBacklogSampler.SAMPLE_INTERVAL_MILLIS),
                    eq(TimeUnit.MILLISECONDS)
            );
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({VideoStatusTelemetry.class, VideoStatusBacklogSampler.class})
    static class TestInfrastructure {

        @Bean
        VideoStatusMetricsMapper videoStatusMetricsMapper() {
            return mock(VideoStatusMetricsMapper.class);
        }

        @Bean
        VideoStatusProperties videoStatusProperties() {
            return new VideoStatusProperties();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean(name = VideoStatusMetricsConfig.METRICS_SCHEDULER_BEAN)
        ScheduledExecutorService videoStatusMetricsScheduler() {
            ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
            when(executor.isShutdown()).thenReturn(false);
            when(executor.scheduleWithFixedDelay(
                    any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(mock(ScheduledFuture.class));
            return executor;
        }
    }
}
