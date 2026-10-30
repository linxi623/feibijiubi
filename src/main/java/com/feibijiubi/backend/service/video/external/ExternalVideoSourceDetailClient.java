package com.feibijiubi.backend.service.video.external;

import com.feibijiubi.backend.integration.videosource.applecms.AppleCmsDetailSnapshot;

/**
 * M3-B：<b>只能取单个作品详情</b>的上游客户端 seam。
 *
 * <p>与 {@link ExternalVideoSourceCatalogClient} 成对存在但<b>不合并</b>：详情响应里的播放地址必须由
 * <b>媒体</b>允许列表校验，而目录不需要任何媒体授权。分开成两个类型，才能保证"只批准了目录的来源"不会
 * 通过同一个对象取到详情端点。</p>
 *
 * <p>构造这种客户端的前置条件是描述符<b>有</b>媒体策略；没有媒体策略时调用方必须直接拒绝（固定原因码
 * {@code MEDIA_POLICY_REQUIRED}），既不能用 API 允许列表顶替，也不存在"放行所有地址"的实现。</p>
 */
@FunctionalInterface
public interface ExternalVideoSourceDetailClient {

    /**
     * 取单个作品详情。
     *
     * @param workId 上游作品 ID（规范十进制正整数，无前导零；语法由预览服务先行校验）
     * @return 上游详情快照；播放地址仍是 {@code UNVERIFIED} 候选，不含剧集身份
     * @throws com.feibijiubi.backend.integration.videosource.transport.CmsHttpRequestException 上游传输失败（固定码）
     * @throws com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException  响应不符合解析契约（固定码）
     * @throws com.feibijiubi.backend.integration.videosource.applecms.AppleCmsResponseMismatchException
     *                                                                                         响应作品身份与请求不同
     */
    AppleCmsDetailSnapshot fetchWork(String workId);
}
