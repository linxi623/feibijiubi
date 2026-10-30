package com.feibijiubi.backend.videosource.service;

/**
 * M3-B：按<b>已批准描述符</b>构造上游预览客户端的服务端 seam。
 *
 * <h2>为什么是接口</h2>
 * <p>生产实现（{@code AppleCmsExternalVideoSourceClientProvider}）要把描述符变成受限 HTTP 客户端 + 离线
 * 解析器，这条路径在单测里既不能联网也不能真解析。把它放在一个接口后面，测试可以注入假实现来验证
 * "预览服务在什么顺序上调用了什么"，而<b>不会</b>在服务里留下"解析失败就返回空快照"之类的降级分支 ——
 * 生产实现是唯一的实现，没有备用路径。</p>
 *
 * <h2>构造期不做 IO</h2>
 * <p>实现约定：{@link #createCatalogClient} / {@link #createDetailClient} 只做对象装配，
 * <b>不解析 DNS、不发请求、不探测媒体</b>。真正的地址查询发生在上游请求执行期间。</p>
 */
public interface ExternalVideoSourceClientProvider {

    /**
     * 构造只能取目录一页的客户端（使用描述符里已批准的 API base 与 API 地址策略）。
     *
     * @param descriptor 已批准来源快照，不允许为 {@code null}
     * @return 目录客户端；本方法不联网
     * @throws IllegalArgumentException 描述符为 {@code null}，或其中的 base 不被批准的策略接受
     */
    ExternalVideoSourceCatalogClient createCatalogClient(ExternalVideoSourceDescriptor descriptor);

    /**
     * 构造只能取单个作品详情的客户端。
     *
     * <p>详情响应里的播放地址由描述符的<b>媒体</b>策略校验。媒体策略为空时本方法<b>必须</b>失败：调用方
     * 应当先检查 {@link ExternalVideoSourceDescriptor#mediaPolicy()} 并给出固定原因码
     * {@code MEDIA_POLICY_REQUIRED}，这里只是拒绝执行，不提供任何"回退到 API 允许列表"的分支。</p>
     *
     * @param descriptor 已批准来源快照，不允许为 {@code null}，且必须带媒体策略
     * @return 详情客户端；本方法不联网
     * @throws IllegalArgumentException 描述符为 {@code null}、没有媒体策略，或 base 不被批准的策略接受
     */
    ExternalVideoSourceDetailClient createDetailClient(ExternalVideoSourceDescriptor descriptor);
}
