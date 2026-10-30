package com.feibijiubi.backend.videosource.integration.transport;

import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.client.CloseableHttpClient;

/**
 * E2-C2：受限客户端的<b>构造 seam</b> —— 每次请求用一个新客户端，工厂拿到的
 * {@link DnsResolver} 必须是"已注入连接管理器"的那个解析钩子。
 *
 * <p>存在的唯一理由是离线单测：真实实现（{@link RestrictedHttpClientFactory}）会建
 * {@code BasicHttpClientConnectionManager} 并注册 HTTPS socket 工厂；单测注入一个返回 Mockito mock
 * {@code CloseableHttpClient} 的工厂，就不需要任何真实 socket/DNS。</p>
 *
 * <p>它是<b>包私有</b>的：{@link RestrictedCmsHttpClient} 的公开构造器只用
 * {@link RestrictedHttpClientFactory#INSTANCE}，所以生产路径无法通过这个 seam 绕过 DNS 校验或 TLS 配置；
 * 同包代码可以注入假实现，这一点是"离线可测"与"生产不可绕"之间的明确取舍。</p>
 */
@FunctionalInterface
interface CmsHttpClientFactory {

    /**
     * @param apacheDnsResolver 已包装 {@link ValidatedDnsResolver} 的解析钩子；实现<b>必须</b>把它交给
     *                          真正建连接的连接管理器，而不是只挂在 builder 上
     * @return 一个新的、由调用方负责关闭的客户端
     */
    CloseableHttpClient newClient(DnsResolver apacheDnsResolver);
}
