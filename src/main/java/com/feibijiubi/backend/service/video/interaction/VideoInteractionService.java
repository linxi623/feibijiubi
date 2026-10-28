package com.feibijiubi.backend.service.video.interaction;

import java.util.Optional;

/**
 * T15：播放/分享交互的请求幂等 + 滚动窗口限计（DB 核心），以及给准入层用的只读重放查询。
 *
 * <p>第二阶段起它有两个调用者：{@code VideoInteractionAdmissionService}（HTTP 入口的编排：
 * 参数/视频校验 → 重放查询 → Redis 准入限流 → {@link #record}），以及隔离测试。它仍然是
 * <b>唯一</b>写 {@code video_interaction_request} / {@code video_interaction_window} 的地方。
 * 旧的无 actor/无幂等键的 {@code UserVideoService#increasePlayCount/increaseShare} 签名已删除，
 * 避免绕过本服务直接计数。</p>
 *
 * <h2>已批准的产品语义</h2>
 * <ul>
 *   <li>同一 actor 对同一 vid 的同一动作，滚动 30 分钟内最多计一次；抑制不推进窗口。</li>
 *   <li>同一 {@code requestKey} <b>在记录保留期内</b>（自 {@code completed_at} 起 30 天；清理默认关闭）
 *       只计一次：重复提交重放第一次的结果（含“被抑制”这个结果），不会因为过了 30 分钟就重新判定。
 *       </li>
 *   <li><b>记录被清理删除后，同一 {@code requestKey} 按新请求处理</b>，仍受 30 分钟滚动窗口约束，所以可能
 *       重新计数。清理是按批推进的维护任务、不是 30 天到点的 TTL，因此“过期”与“已删”不是同一件事；
 *       详见 {@code VideoInteractionCleanupService}。</li>
 *   <li>计数失败（同步守卫影响行数不为 1、异步事件插入失败、请求/窗口写失败）一律整体回滚，
 *       不留下“请求已占位但没计数”的行。</li>
 *   <li>Redis 失败一律拒绝计数：准入层在调用本服务之前已经 fail-closed，本服务自己不做 Redis
 *       读写（Redis 只做准入，不参与去重）。</li>
 * </ul>
 *
 * <h2>调用契约</h2>
 * <p>必须在<b>没有外层事务</b>的上下文里调用：同一 {@code requestKey} 的并发冲突要求本次尝试事务
 * 整体回滚后再读取赢家结果，如果外层已有事务，回滚会把它标记为 rollback-only。违反该契约会直接抛
 * {@link IllegalStateException}，不会静默继续。</p>
 */
public interface VideoInteractionService {

    /** 本次（或重放的原始请求）真的计了一次数。 */
    String STATE_COUNTED = "COUNTED";

    /** 本次（或重放的原始请求）落在窗口内，未计数。 */
    String STATE_SUPPRESSED = "SUPPRESSED";

    /**
     * 记录一次交互计数请求：幂等重放历史结果，或（首次）在单个 DB 事务内完成
     * 占位 → 窗口判定 → 计数 → 终态。
     *
     * @throws com.feibijiubi.backend.common.BusinessException 400 参数非法；
     *         409 {@code requestKey} 被不同形状的请求占用或并发冲突无法读取结果；
     *         500 计数/写入影响行数异常（整事务回滚）
     */
    VideoInteractionResult record(VideoInteractionCommand command);

    /**
     * T15 第二阶段：只读的“已提交请求”查询，供 HTTP 准入层在限流<b>之前</b>做幂等重放。
     *
     * <p>幂等重放必须发生在任何 Redis 准入计数之前：客户端重试同一个 {@code requestKey} 时
     * 结果应当来自 MySQL 里的终态快照，而不是“再消耗一份额度、再判一次窗口”。因此准入层先调本方法，
     * 命中就直接返回，Redis 完全不参与；未命中才继续限流并调用 {@link #record}。</p>
     *
     * <p>这是<b>优化提示，不是权威判定</b>：{@link #record} 自己会再查一次（两次读取之间可能有并发
     * 请求插入同一个 {@code requestKey}），最终结果始终以 {@code record} 为准。</p>
     *
     * <p>契约：</p>
     * <ul>
     *   <li>命中且形状一致 → 返回历史结果（{@code duplicate=true}）；</li>
     *   <li>命中但 actor/vid/action 不一致 → 409（与 {@code record} 同一个判定，同一个文案）；</li>
     *   <li>未命中 → {@link Optional#empty()}；</li>
     *   <li><b>查询异常不得当成未命中</b>：数据库异常原样抛出（500），否则一次数据库故障会被
     *       解释成“这是新请求”，从而在限流旁路上继续写库。</li>
     * </ul>
     *
     * <p>本方法只读，不写 MySQL，也不写 Redis；不检查视频可见性（那是调用方的前置校验）。</p>
     *
     * <p><b>重放以“请求行仍然存在”为界</b>：记录被 30 天清理删掉之后，本方法返回
     * {@link Optional#empty()}（未命中），调用方会把它当新请求处理——这与 {@link #record} 一致，
     * 不是漏重放。反过来说，本方法不是“这个 {@code requestKey} 永远不会再计数”的证明。</p>
     */
    Optional<VideoInteractionResult> lookupReplay(VideoInteractionCommand command);
}
