package com.feibijiubi.backend.config;

import com.feibijiubi.backend.service.video.external.ExternalVideoSourceRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M3-B：外部视频来源预览的<b>最小 Spring 接线</b>。
 *
 * <h2>只做两件事</h2>
 * <ol>
 *   <li>把已存在的 {@link ExternalVideoSourceProperties} 注册成配置属性 bean（M3-A 时它刻意没有注册，
 *       因此应用启动时不会扫描到它）；</li>
 *   <li>用这份配置构造一个不可变的 {@link ExternalVideoSourceRegistry} 快照 bean。</li>
 * </ol>
 *
 * <h2>启动期不联网</h2>
 * <p>本配置类与它创建的 bean 都<b>不</b>做 DNS 解析、不建 HTTP 客户端、不发任何上游请求、不探测媒体、
 * 不起线程或定时任务。{@code sources} 里写着 {@code enabled=true} 也不会在启动期触达上游：
 * 地址查询只发生在上游请求真正执行时（见 {@code AppleCmsExternalVideoSourceClientProvider}）。</p>
 *
 * <h2>默认关闭</h2>
 * <p>{@code app.external-video-source.preview-enabled} 默认 {@code false}，且默认<b>零来源</b>。
 * 不写任何配置时，注册表拒绝一切预览请求 —— 本片没有"默认打开"的路径，也没有改
 * {@code application.yml} 或 {@code BackendApplication}。</p>
 */
@Configuration
@EnableConfigurationProperties(ExternalVideoSourceProperties.class)
public class ExternalVideoSourceConfig {

    /**
     * 只读注册表快照。
     *
     * <p>构造期会主动校验配置（非法配置让启动失败，而不是带着错配置运行）。配置对象在构造之后不再被读取，
     * 因此运行期改 properties 不会改变已批准的来源；配置也不热加载，改配置必须重启。</p>
     */
    @Bean
    public ExternalVideoSourceRegistry externalVideoSourceRegistry(
            ExternalVideoSourceProperties properties) {
        return new ExternalVideoSourceRegistry(properties);
    }
}
