package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy;

import java.util.Optional;

/**
 * M3-A：一条<b>已被批准</b>的外部来源的不可变快照 —— 只由
 * {@link ExternalVideoSourceRegistry} 在构造期创建并持有。
 *
 * <h2>它是什么、不是什么</h2>
 * <ul>
 *   <li>它是<b>服务端</b>对象：{@link #apiBase()} 与两个地址策略都只给服务端代码用（构造请求、校验
 *       上游/媒体地址）。它<b>不是</b> VO、<b>不是</b> controller 的返回体，也<b>不应</b>被 JSON
 *       序列化给客户端 —— 里面是运维批准的地址清单，泄露它等于把"我们信任哪些上游"抄给了调用方。
 *       要给管理员看的摘要只有 {@link ExternalVideoSourceRegistry.AvailableSource}（code + name）。</li>
 *   <li>{@code toString()} 只输出 {@code code} 与 {@code enabled}：日志/异常里最容易泄露配置的地方就是
 *       默认的字段拼接，所以这里显式收窄，不打印 base 或允许列表。</li>
 *   <li>不可变：所有字段都是不可变类型（{@link String}、{@link Boolean}、{@link ExternalVideoUrlPolicy}
 *       本身在构造期已深拷贝允许列表）。构造器是包私有 —— 外部无法绕过注册表的校验直接造一个"看起来
 *       已批准"的描述符。</li>
 * </ul>
 *
 * <h2>边界</h2>
 * <p>本类<b>不</b>发网络请求、不做 DNS 解析、不建 HTTP 客户端、不探测媒体。它只是一份"批准了什么"的
 * 只读快照；{@code enabled=false} 或媒体策略为空都不代表任何外部调用已经发生。</p>
 */
public final class ExternalVideoSourceDescriptor {

    private final String code;
    private final boolean enabled;
    private final String name;
    private final String apiBase;
    private final ExternalVideoUrlPolicy apiPolicy;
    private final Optional<ExternalVideoUrlPolicy> mediaPolicy;

    ExternalVideoSourceDescriptor(String code,
                                  boolean enabled,
                                  String name,
                                  String apiBase,
                                  ExternalVideoUrlPolicy apiPolicy,
                                  Optional<ExternalVideoUrlPolicy> mediaPolicy) {
        this.code = code;
        this.enabled = enabled;
        this.name = name;
        this.apiBase = apiBase;
        this.apiPolicy = apiPolicy;
        this.mediaPolicy = mediaPolicy;
    }

    /** 部署白名单引用键（字符串，不是数据库主键）；大小写敏感、不 trim。 */
    public String code() {
        return code;
    }

    /** 该来源当前是否允许预览。 */
    public boolean enabled() {
        return enabled;
    }

    /** 展示名；可以出现在管理员界面上。 */
    public String name() {
        return name;
    }

    /**
     * 已批准的 API base（HTTPS、无 query/片段/userinfo、显式单斜线路径）。
     *
     * <p><b>仅服务端使用</b>：不要放进 VO、不要写日志、不要返回给客户端。</p>
     */
    public String apiBase() {
        return apiBase;
    }

    /** API 地址策略：HTTPS + 精确 host:port 允许列表（允许列表非空）。 */
    public ExternalVideoUrlPolicy apiPolicy() {
        return apiPolicy;
    }

    /**
     * 媒体地址策略。
     *
     * <p>空表示该来源<b>不支持媒体探测</b>（配置里没有媒体允许列表）；此时<b>不会</b>回退到
     * {@link #apiPolicy()} 的允许列表 —— "批准了 API 端点"与"批准了媒体端点"是两个不同的授权。</p>
     */
    public Optional<ExternalVideoUrlPolicy> mediaPolicy() {
        return mediaPolicy;
    }

    /** 只输出 code 与 enabled：base、名称与允许列表不进入日志。 */
    @Override
    public String toString() {
        return "ExternalVideoSourceDescriptor[code=" + code + ", enabled=" + enabled + "]";
    }
}
