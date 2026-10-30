package com.feibijiubi.backend.videosource.service;

/**
 * M3-B：管理员外部来源预览的<b>每管理员固定窗口配额</b>。
 *
 * <h2>它约束什么</h2>
 * <p>只约束"这个管理员在多长时间窗口内能发起多少次预览请求"，即"本进程愿意替这个管理员向上游发几次请求"。
 * 它<b>不</b>是上游站点的配额，也不代表上游同不同意；更不构成任何"内容已授权"的结论。</p>
 *
 * <h2>与并发闸门的分工（不要把两者混成一句话）</h2>
 * <ul>
 *   <li>本接口的计数在 <b>Redis</b> 里，因此同一部署的多个实例<b>共享</b>同一个管理员的配额；</li>
 *   <li>并发闸门是进程内的信号量，只限制<b>本实例</b>同时打开的上游请求数，
 *       <b>不能</b>被描述成"全局并发上限"。</li>
 * </ul>
 *
 * <h2>失败语义</h2>
 * <p>超限是 429；Redis 不可用、脚本返回 {@code null} 或负数一律 <b>fail-closed</b>（503），
 * 绝不"Redis 挂了就放行"。额度在真正发上游请求之前消耗，之后即使上游失败也<b>不退回</b>。</p>
 */
public interface ExternalVideoSourcePreviewQuotaService {

    /**
     * 消耗一次配额并判定是否放行。
     *
     * @param adminUserId 登录态管理员 uid（只能来自 {@code currentUserId}，不能由客户端传入）
     * @throws com.feibijiubi.backend.common.BusinessException 401 缺少 uid；429 超过本窗口配额
     * @throws com.feibijiubi.backend.common.RedisOperationException Redis 故障或脚本返回非法值（映射为 503）
     */
    void checkAndConsume(Integer adminUserId);
}
