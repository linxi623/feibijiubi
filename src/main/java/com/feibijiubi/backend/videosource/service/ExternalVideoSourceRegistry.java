package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M3-A：外部视频来源的<b>只读注册表</b> —— 把部署批准配置
 * （{@link ExternalVideoSourceProperties}）固化成一份不可变快照，回答两个问题：
 * "现在允许预览哪些来源"和"这个 code 对应的已批准配置是什么"。
 *
 * <h2>静态快照</h2>
 * <ul>
 *   <li>所有内容在构造期一次性算好：校验配置、构造每个来源的描述符（含两套地址策略），之后
 *       <b>不再读取</b>配置对象。因此构造之后调用方再改 properties 的 list/set/条目，都不会影响已经
 *       批准的注册表内容（深拷贝，见 {@link #ExternalVideoSourceRegistry(ExternalVideoSourceProperties)}）。</li>
 *   <li>没有热更新：{@code application.yml} 不热加载，改配置必须重启进程。这里没有 {@code refresh()}
 *       之类的入口，也不订阅任何事件。</li>
 * </ul>
 *
 * <h2>本片不做的事</h2>
 * <ul>
 *   <li><b>不是 Spring bean</b>：没有 {@code @Component}/{@code @Service}，本片也不加
 *       {@code @Bean}。它是纯对象，只能手工 {@code new}（或由下一片接线时装配）。</li>
 *   <li><b>不发网络请求</b>：不解析 DNS、不建 HTTP 客户端、不探测媒体、不建线程或定时任务。构造一个
 *       注册表（哪怕 32 个来源全部 {@code enabled=true}）也不会产生任何外部调用。</li>
 *   <li>不建 controller / mapper / Redis key / 读取接口；这里没有"预览成功"的假接口，只有
 *       "配置已批准 / 未启用 / 不可用"三种判定。</li>
 *   <li>{@code max-concurrent-requests} 与 {@code per-admin-requests-per-minute} 只被校验保存，
 *       本类<b>不</b>实现并发闸门或限流。</li>
 * </ul>
 *
 * <h2>失败语义</h2>
 * <p>这里用固定的 {@link IllegalStateException} 而不是业务异常：本片还没有 HTTP 入口，复用
 * {@code BusinessException} 的 code（400/404/503）会把"业务层语义"提前钉在一个还没有接口的东西上。
 * 文案只有固定机器码，<b>不含</b> code 取值、base、host 或名称，因此"某个 code 存在但未启用"和
 * "某个 code 根本没配置"对调用方是<b>同一个</b>答案，不会泄露其它配置。</p>
 */
public final class ExternalVideoSourceRegistry {

    /** 预览总开关关闭：对任何来源都返回这个码。 */
    public static final String PREVIEW_DISABLED_MESSAGE =
            "PREVIEW_DISABLED：外部来源预览未启用（app.external-video-source.preview-enabled=false）";

    /** 来源未配置或未启用：两种情况共用同一个码，避免区分"存在但禁用"与"不存在"。 */
    public static final String SOURCE_UNAVAILABLE_MESSAGE =
            "SOURCE_UNAVAILABLE：来源未配置或未启用";

    private final boolean previewEnabled;
    private final Map<String, ExternalVideoSourceDescriptor> descriptorsByCode;
    private final List<AvailableSource> availableSources;

    /**
     * 从部署批准配置构造只读快照。
     *
     * <p>先<b>主动调用</b> {@link ExternalVideoSourceProperties#validate()}：这样手工 {@code new} 出来的
     * properties（没经过 Spring 的 {@code @PostConstruct}）也无法把非法配置带进注册表。然后逐条构造
     * 描述符，地址策略由 {@link ExternalVideoSourceProperties#requireApprovedApiPolicy} /
     * {@link ExternalVideoSourceProperties#mediaPolicy} 生成 —— 校验与"真正被批准使用的策略"是同一份
     * 实现，不存在两套规则。</p>
     *
     * <p>深拷贝边界：描述符里的 {@link String} 不可变，{@link ExternalVideoUrlPolicy} 在构造期
     * 用 {@code Set.copyOf} 复制了允许列表，媒体策略只在非空时才创建。因此调用方之后修改配置对象的
     * 列表/集合/字段，都不会改变这里已经批准的内容。</p>
     *
     * @param properties 部署批准配置，不能为 null
     * @throws IllegalArgumentException properties 为 null
     * @throws IllegalStateException    配置不合法（文案只有字段路径与固定原因码）
     */
    public ExternalVideoSourceRegistry(ExternalVideoSourceProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("外部来源配置不能为空");
        }
        properties.validate();

        this.previewEnabled = properties.isPreviewEnabled();
        Map<String, ExternalVideoSourceDescriptor> byCode = new LinkedHashMap<>();
        List<AvailableSource> enabled = new ArrayList<>();
        List<ExternalVideoSourceProperties.Source> configured = properties.getSources();
        for (int index = 0; index < configured.size(); index++) {
            ExternalVideoSourceProperties.Source source = configured.get(index);
            // 字段路径只用于报错：索引是位置，不是配置取值。
            String field = ExternalVideoSourceProperties.PREFIX + ".sources[" + index + "]";
            ExternalVideoUrlPolicy apiPolicy = ExternalVideoSourceProperties.requireApprovedApiPolicy(
                    field + ".api-base", source.getApiBase(), source.getApiAllowedEndpoints());
            Optional<ExternalVideoUrlPolicy> mediaPolicy = ExternalVideoSourceProperties.mediaPolicy(
                    field + ".media-allowed-endpoints", source.getMediaAllowedEndpoints());
            ExternalVideoSourceDescriptor descriptor = new ExternalVideoSourceDescriptor(
                    source.getCode(), source.isEnabled(), source.getName(),
                    source.getApiBase(), apiPolicy, mediaPolicy);
            // code 唯一性已由 validate() 保证，这里不会覆盖已有条目。
            byCode.put(descriptor.code(), descriptor);
            if (descriptor.enabled()) {
                enabled.add(new AvailableSource(descriptor.code(), descriptor.name()));
            }
        }
        this.descriptorsByCode = Collections.unmodifiableMap(byCode);
        // 预览关闭时对外摘要为空：连"有哪些来源被配置过"都不提供。
        this.availableSources = previewEnabled ? List.copyOf(enabled) : List.of();
    }

    /** 预览总开关当前是否打开（注册表构造时的快照值）。 */
    public boolean previewEnabled() {
        return previewEnabled;
    }

    /**
     * 当前可预览来源的<b>安全摘要</b>（只有 code 与名称，没有 base、没有允许列表）。
     *
     * <p>预览关闭时返回空列表（不是"全部来源"、也不是抛异常）：关闭状态下连"配置了哪些来源"都不该
     * 出现在响应里。返回的列表不可修改。</p>
     */
    public List<AvailableSource> listAvailableSources() {
        return availableSources;
    }

    /**
     * 取出一个来源的已批准配置，用于服务端预览。
     *
     * <p>判定顺序固定：预览总开关 → 来源存在且已启用。未配置、已禁用、code 为 null 一律得到同一个
     * {@link #SOURCE_UNAVAILABLE_MESSAGE}，因此调用方无法通过错误文案区分"这个 code 存在但被禁用"和
     * "这个 code 不存在"，也就无法枚举部署里配置了哪些来源。</p>
     *
     * <p>本方法只做内存查找：不读配置、不读数据库、不发网络请求、不启动任何任务。</p>
     *
     * @param sourceCode 部署白名单引用键（字符串，不是数据库主键）
     * @return 该来源的不可变描述符（服务端对象，<b>不要</b>序列化给客户端）
     * @throws IllegalStateException 预览未启用，或来源未配置/未启用
     */
    public ExternalVideoSourceDescriptor requirePreviewSource(String sourceCode) {
        if (!previewEnabled) {
            throw new IllegalStateException(PREVIEW_DISABLED_MESSAGE);
        }
        ExternalVideoSourceDescriptor descriptor =
                sourceCode == null ? null : descriptorsByCode.get(sourceCode);
        if (descriptor == null || !descriptor.enabled()) {
            throw new IllegalStateException(SOURCE_UNAVAILABLE_MESSAGE);
        }
        return descriptor;
    }

    /**
     * 给管理员界面用的来源摘要：<b>只有</b> code 与名称。
     *
     * <p>刻意不包含 base、允许列表、媒体支持情况或任何探测结果 —— 这些都属于服务端细节。</p>
     */
    public record AvailableSource(String code, String name) {
    }
}
