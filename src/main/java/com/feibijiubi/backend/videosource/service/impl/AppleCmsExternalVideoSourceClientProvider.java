package com.feibijiubi.backend.videosource.service.impl;

import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsJsonAdapter;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsSourceClient;
import com.feibijiubi.backend.videosource.integration.transport.RestrictedCmsHttpClient;
import com.feibijiubi.backend.videosource.integration.transport.ValidatedDnsResolver;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceCatalogClient;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceClientProvider;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceDescriptor;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceDetailClient;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * M3-B：把已批准描述符装配成"只能取目录"和"只能取详情"两个上游客户端的<b>生产实现</b>。
 *
 * <h2>装配内容</h2>
 * <ul>
 *   <li>请求目标：{@link RestrictedCmsHttpClient}，base 与 API 地址策略都来自描述符，调用方无法覆盖；</li>
 *   <li>响应解析：{@link AppleCmsJsonAdapter}，详情用<b>媒体</b>策略校验播放地址；</li>
 *   <li>响应一致性：{@link AppleCmsSourceClient} 比对页码/分类/作品身份。</li>
 * </ul>
 *
 * <h2>为什么目录与详情各自构造，不共用一个解析器</h2>
 * <p>{@link AppleCmsJsonAdapter} 的构造参数就是"用哪套允许列表校验播放地址"。目录响应里<b>没有</b>播放地址
 * 字段（列表解析器明确忽略 {@code vod_play_from}/{@code vod_play_url}），所以目录侧传入 API 策略只是为了
 * 满足构造器；而详情侧<b>必须</b>用媒体策略，且媒体策略为空时这里直接拒绝构造，绝不拿 API 策略顶替。
 * 两个方向各自独立构造，就不会出现"目录的解析器被复用到详情"从而让播放地址按 API 允许列表放行的情况。</p>
 *
 * <h2>构造期不联网（这一条是契约，不是实现细节）</h2>
 * <p>本类只装配对象：{@link RestrictedCmsHttpClient} 的构造只校验 base 与策略，不建连接；
 * {@link AppleCmsJsonAdapter} 只建本地 Jackson factory。DNS 查询由 {@link ValidatedDnsResolver} 在
 * <b>请求执行期</b>调用注入的 {@link InetAddress#getAllByName(String)} 完成，因此创建 bean、构造客户端、
 * 列出目录来源都不会触发任何网络访问。</p>
 *
 * <h2>没有备用路径</h2>
 * <p>没有默认 base、没有默认允许列表、没有"解析失败返回空快照"的分支、没有跳过校验的开关。失败时
 * 原样抛出上游层的固定码异常，由预览服务统一折叠成 502。</p>
 */
@Component
public class AppleCmsExternalVideoSourceClientProvider implements ExternalVideoSourceClientProvider {

    /**
     * 生产地址查询：只在请求执行期被调用（由 {@link ValidatedDnsResolver#resolve(String)} 触发）。
     *
     * <p>这里刻意<b>不</b>在字段初始化/构造器里调用它 —— 写成方法引用只是把"用什么查"交给解析器，
     * 而不是现在就去查。</p>
     */
    private static final ValidatedDnsResolver.AddressLookup SYSTEM_ADDRESS_LOOKUP =
            InetAddress::getAllByName;

    /** 无状态（只持有一个查询实现），可被多个线程共享；不缓存任何解析结果。 */
    private final ValidatedDnsResolver dnsResolver =
            new ValidatedDnsResolver(SYSTEM_ADDRESS_LOOKUP);

    @Override
    public ExternalVideoSourceCatalogClient createCatalogClient(ExternalVideoSourceDescriptor descriptor) {
        ExternalVideoSourceDescriptor approved = requireDescriptor(descriptor);
        AppleCmsSourceClient client = new AppleCmsSourceClient(
                httpClient(approved),
                // 目录解析器：列表响应没有播放地址字段，这里的策略只为满足构造器；详情另有独立实例。
                new AppleCmsJsonAdapter(approved.apiPolicy()));
        return client::fetchList;
    }

    @Override
    public ExternalVideoSourceDetailClient createDetailClient(ExternalVideoSourceDescriptor descriptor) {
        ExternalVideoSourceDescriptor approved = requireDescriptor(descriptor);
        ExternalVideoUrlPolicy mediaPolicy = approved.mediaPolicy().orElseThrow(
                () -> new IllegalArgumentException(
                        "该来源没有已批准的媒体端点：详情预览必须先按 MEDIA_POLICY_REQUIRED 拒绝"));
        AppleCmsSourceClient client = new AppleCmsSourceClient(
                httpClient(approved),
                new AppleCmsJsonAdapter(mediaPolicy));
        return client::fetchDetail;
    }

    private RestrictedCmsHttpClient httpClient(ExternalVideoSourceDescriptor descriptor) {
        return new RestrictedCmsHttpClient(
                descriptor.apiBase(), descriptor.apiPolicy(), dnsResolver);
    }

    private static ExternalVideoSourceDescriptor requireDescriptor(ExternalVideoSourceDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("来源描述符不能为空");
        }
        return descriptor;
    }
}
