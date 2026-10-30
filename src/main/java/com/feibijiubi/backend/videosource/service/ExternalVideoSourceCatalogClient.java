package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsListSnapshot;

/**
 * M3-B：<b>只能取目录一页</b>的上游客户端 seam。
 *
 * <h2>为什么单独一个接口，而不是"一个能取一切的客户端"</h2>
 * <p>目录预览与作品详情是两个不同的上游端点，也是两种不同的授权（详情里的播放地址要用<b>媒体</b>允许列表
 * 校验，目录不需要）。如果两类操作挂在同一个接口上，调用方就可能在"只批准了目录"的场景里顺手调到详情端点。
 * 这里让类型本身表达能力：拿到这个接口就<b>只能</b>取目录，取详情在编译期就不存在。</p>
 *
 * <h2>边界</h2>
 * <p>实现由 {@link ExternalVideoSourceClientProvider} 按已批准描述符构造；本接口不暴露 base、允许列表、
 * header 或任意 URL，也不允许调用方覆盖请求参数。返回值是上游<b>未验证</b>快照：一致性与脱敏由实现负责，
 * 但"字段一致"不等于内容已授权或可播放。</p>
 */
@FunctionalInterface
public interface ExternalVideoSourceCatalogClient {

    /**
     * 取目录的一页。
     *
     * @param page       页码 {@code 1..}（上界由传输层与预览服务的参数校验共同决定）
     * @param categoryId 分类 ID，{@code null} 表示不过滤；非 {@code null} 时必须非负
     * @return 上游目录快照，条目顺序即上游顺序
     * @throws com.feibijiubi.backend.videosource.integration.transport.CmsHttpRequestException 上游传输失败（固定码）
     * @throws com.feibijiubi.backend.videosource.integration.applecms.AppleCmsParseException  响应不符合解析契约（固定码）
     * @throws com.feibijiubi.backend.videosource.integration.applecms.AppleCmsResponseMismatchException
     *                                                                                         响应自报页码/分类与请求不一致
     */
    AppleCmsListSnapshot fetchPage(int page, Integer categoryId);
}
