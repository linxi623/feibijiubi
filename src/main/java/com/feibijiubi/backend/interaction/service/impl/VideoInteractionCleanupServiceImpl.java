package com.feibijiubi.backend.interaction.service.impl;

import com.feibijiubi.backend.interaction.config.VideoInteractionProperties;
import com.feibijiubi.backend.interaction.mapper.VideoInteractionMapper;
import com.feibijiubi.backend.interaction.service.VideoInteractionCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * T15 有界清理的实现：两阶段（非锁定选 id → 按主键带守卫删）、无显式事务、无持久状态。
 *
 * <h2>为什么不开显式事务</h2>
 * <ul>
 *   <li>单条 DELETE 本身是原子的（InnoDB 语句级回滚），跨语句原子性不是需求：删除守卫会在
 *       DELETE 里重新判定，所以“候选来自旧快照”不会造成误删；</li>
 *   <li>开事务反而会把一批记录的锁一直持有到提交，把 {@code record} 线程的等待窗口放大到
 *       “整轮工作量”；</li>
 *   <li>因此这里<b>拒绝</b>在已有事务里运行（{@link #cleanupOnce()} 入口断言），保证每个 mapper
 *       操作各自独立提交。</li>
 * </ul>
 *
 * <h2>为什么先选 id 再按主键删</h2>
 * <p>这是<b>设计意图</b>，不是已验证结论：单条范围 DELETE（{@code WHERE state IN (1,2) AND completed_at <= ?
 * ORDER BY id LIMIT n}）在 REPEATABLE READ 下可能取 next-key 锁，从而可能阻挡 {@code completeRequest}
 * 把索引项从 {@code state=0} 搬到 {@code state=1/2}，以及新窗口行插入；两阶段形态把删除收窄成主键等值匹配。
 * 但<b>不保证优化器一定选择主键等值访问路径，也不承诺锁足迹</b>（是否取间隙锁/next-key 锁、等待多久）——
 * 那必须用真实 MySQL 的 {@code EXPLAIN} 与锁等待观测实测，本轮<b>没有</b>运行该隔离用例，仓库里也没有任何
 * 实测数据。</p>
 *
 * <h2>为什么守卫必须重复</h2>
 * <p>候选读与 DELETE 之间没有事务。窗口行可能在两者之间被 {@code advanceWindowIfDue} 推进到
 * {@code NOW(3)}，此时只按 id 删就会删掉一个 1 ms 前刚计数的窗口行——下一次请求会重新计数，
 * 30 分钟限计被静默绕过。同一条时间守卫就是唯一防线，所以它写在 DELETE 里而不是只在 SELECT 条件里。</p>
 *
 * <p>隔离用例 {@code VideoInteractionCleanupIsolatedTests} 写了真实 MySQL 上的守卫/锁交错用例，但<b>本轮
 * 只编译、未运行</b>，所以这里不声明这些行为“已钉住/已验证”。已执行的证据只有：纯单测证明调用顺序、参数与
 * 开关（Mockito 证明不了锁）、真实 MyBatis 渲染证明 SQL 形态、以及轻量 Spring 上下文证明调度 bean 装配。
 * 锁足迹与真实访问路径仍待实测。</p>
 */
@Slf4j
@Service
public class VideoInteractionCleanupServiceImpl implements VideoInteractionCleanupService {

    private static final String REQUEST_TABLE = "video_interaction_request";
    private static final String WINDOW_TABLE = "video_interaction_window";

    private final VideoInteractionMapper interactionMapper;
    private final VideoInteractionProperties properties;

    public VideoInteractionCleanupServiceImpl(VideoInteractionMapper interactionMapper,
                                              VideoInteractionProperties properties) {
        this.interactionMapper = interactionMapper;
        this.properties = properties;
    }

    @Override
    public RoundResult cleanupOnce() {
        if (!properties.isCleanupEnabled()) {
            // 双重开关：调度器已经先看过一次，这里再判一次，避免任何代码路径在默认关闭时误删。
            return RoundResult.skipped();
        }
        requireNoEnclosingTransaction();

        int batchSize = properties.getCleanupBatchSize();
        Batch counted = deleteExpiredRequests(VideoInteractionServiceImpl.REQUEST_STATE_COUNTED,
                batchSize);
        Batch suppressed = deleteExpiredRequests(
                VideoInteractionServiceImpl.REQUEST_STATE_SUPPRESSED, batchSize);
        Batch windows = deleteIdleWindows(batchSize);

        return new RoundResult(counted.deleted(), suppressed.deleted(), windows.deleted(),
                counted.batchFull() || suppressed.batchFull(), windows.batchFull());
    }

    // ------------------------------------------------------------------ requests

    /** 一个 state 一批：选 id → 校验候选 → 按主键带守卫删。 */
    private Batch deleteExpiredRequests(int state, int batchSize) {
        List<Long> selected = interactionMapper.selectExpiredRequestIds(
                state, REQUEST_RETENTION_DAYS, batchSize);
        List<Long> candidates = requireValidCandidates(selected, batchSize, REQUEST_TABLE);
        if (candidates.isEmpty()) {
            return Batch.EMPTY;
        }
        boolean batchFull = candidates.size() == batchSize;
        int deleted = interactionMapper.deleteExpiredRequestsByIds(
                candidates, REQUEST_RETENTION_DAYS);
        logGuardSkips(REQUEST_TABLE, candidates.size(), deleted);
        return new Batch(batchFull, deleted);
    }

    // ------------------------------------------------------------------- windows

    private Batch deleteIdleWindows(int batchSize) {
        List<Long> selected = interactionMapper.selectIdleWindowIds(
                WINDOW_RETENTION_DAYS, batchSize);
        List<Long> candidates = requireValidCandidates(selected, batchSize, WINDOW_TABLE);
        if (candidates.isEmpty()) {
            return Batch.EMPTY;
        }
        boolean batchFull = candidates.size() == batchSize;
        int deleted = interactionMapper.deleteIdleWindowsByIds(
                candidates, WINDOW_RETENTION_DAYS);
        logGuardSkips(WINDOW_TABLE, candidates.size(), deleted);
        return new Batch(batchFull, deleted);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 候选校验 fail closed：候选多于批大小、出现 null 或非正 id 时<b>直接抛异常、一条都不删</b>。
     * 这些情况只能来自 mapper/DB 契约被破坏，猜着删比不删更糟。
     */
    private List<Long> requireValidCandidates(List<Long> selected, int batchSize, String table) {
        if (selected == null) {
            throw new IllegalStateException(table + " 清理候选查询返回 null，本轮不做删除");
        }
        if (selected.size() > batchSize) {
            throw new IllegalStateException(table + " 清理候选数 " + selected.size()
                    + " 超过批大小 " + batchSize + "，本轮不做删除");
        }
        for (Long id : selected) {
            if (id == null || id <= 0L) {
                throw new IllegalStateException(table + " 清理候选包含非法 id，本轮不做删除");
            }
        }
        return selected;
    }

    /**
     * 删除影响行数少于候选数说明守卫在 DELETE 里拦下了“刚被推进/改状态”的行——这正是守卫的作用，
     * 不是错误。只记 debug，不打 id，避免日志里出现可关联的行标识。
     */
    private void logGuardSkips(String table, int candidateCount, int deleted) {
        if (deleted < candidateCount) {
            log.debug("video_interaction_cleanup_guard_skip table={} candidates={} deleted={}",
                    table, candidateCount, deleted);
        }
    }

    private void requireNoEnclosingTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "VideoInteractionCleanupService#cleanupOnce 不能在已有事务中调用："
                            + "清理必须让每条 mapper 语句各自提交，否则一条 DELETE 的锁会持有到整轮结束，"
                            + "把交互计数线程的等待窗口放大到整批工作量");
        }
    }

    /** 单个批次的执行结果。 */
    private record Batch(boolean batchFull, int deleted) {
        private static final Batch EMPTY = new Batch(false, 0);
    }
}
