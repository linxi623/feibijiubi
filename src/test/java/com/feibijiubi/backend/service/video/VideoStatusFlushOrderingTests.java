package com.feibijiubi.backend.service.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T03 确定性回归：负增量落库顺序窗口。
 *
 * <p>复现顺序（与 MQ 到达先后无关，只与“状态提交”先后有关）：</p>
 * <ol>
 *   <li>+1 事件已成功写入 Redis，但 {@code markRedisApplied} 的状态提交暂停，
 *       事件仍为 RECEIVED(0)；</li>
 *   <li>-1 事件完成 Redis 与状态提交，变为 REDIS_APPLIED_PENDING_FLUSH(1)；</li>
 *   <li>触发 flush：{@code selectPendingForUpdate} 只能看到 -1，
 *       MySQL 基线为 0 时该批次的非负守卫不满足。</li>
 * </ol>
 *
 * <p>T03 之后的期望行为：-1 被<b>延后</b>（事件保持 status 1、dirty 保留、统计不变），
 * 而不是被隔离成 status 3；+1 状态提交完成后，延后的 -1 自动收敛，
 * 最终 DB=Redis=0、两个合法事件均 FLUSHED(2)、0 次误隔离。</p>
 *
 * <p>本类不再断言“当前缺陷”，也不再靠 sleep 或真实 MQ 碰运气：
 * 使用 {@link VideoStatusFlushTestFixture} 的可控时钟，重复 100 轮，每轮独立状态。</p>
 */
class VideoStatusFlushOrderingTests {

    private static final int VID = VideoStatusFlushTestFixture.DEFAULT_VID;
    private static final int ROUNDS = 100;

    /** 30 秒大于规范允许的最大常量退避（1s..30s），推进后必然到期。 */
    private static final long BEYOND_MAX_BACKOFF_MILLIS = 30_001L;

    @Test
    void pausedPlusOneCommitDefersLegalMinusOneThenConvergesInEveryRound() {
        int deferredRounds = 0;
        for (int round = 0; round < ROUNDS; round++) {
            VideoStatusFlushTestFixture fixture =
                    new VideoStatusFlushTestFixture();
            fixture.addRow(VID, 0);
            // +1：Redis 已成功，markRedisApplied 未提交（status 仍为 0，flush 看不到）
            fixture.addEvent(1L, "PLAY", 1L, 0);
            // -1：Redis 与状态提交均已完成（status 1，flush 可见）
            fixture.addEvent(2L, "PLAY", -1L, 1);
            fixture.markDirty(VID);

            fixture.runScheduler();

            assertEquals(1, fixture.statusOf(2L),
                    "round=" + round + "：合法 -1 只能保持待刷，不允许隔离");
            assertEquals(0, fixture.statusOf(1L),
                    "round=" + round + "：暂停中的 +1 不能被本批选中");
            assertEquals(0, fixture.playOf(VID),
                    "round=" + round + "：不能把 DB 写成负数或部分落库");
            assertTrue(fixture.repairedIds().isEmpty(),
                    "round=" + round + "：0 次误隔离");
            assertTrue(fixture.dirtyContains(VID),
                    "round=" + round + "：延后必须保留 dirty 标记");
            deferredRounds++;

            // 释放窗口：+1 状态提交完成，依赖事件变得可见
            fixture.setEventStatus(1L, 1);
            fixture.advance(BEYOND_MAX_BACKOFF_MILLIS);
            fixture.runScheduler();

            assertEquals(2, fixture.statusOf(2L),
                    "round=" + round + "：延后的 -1 必须自动收敛为 FLUSHED");
            assertEquals(2, fixture.statusOf(1L),
                    "round=" + round + "：+1 必须 FLUSHED");
            assertEquals(0, fixture.playOf(VID),
                    "round=" + round + "：最终 DB 必须为 0");
            assertTrue(fixture.repairedIds().isEmpty(),
                    "round=" + round + "：收敛过程中也不允许隔离");
            assertFalse(fixture.dirtyContains(VID),
                    "round=" + round + "：收敛后不应残留 dirty");
        }
        assertEquals(ROUNDS, deferredRounds);
    }

    @Test
    void minusOneWhoseDependencyNeverArrivesStaysPendingAcrossBudget() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, 0);
        // 依赖的 +1 永远停留在 RECEIVED(0)：status0 长期滞留
        fixture.addEvent(1L, "PLAY", 1L, 0);
        fixture.addEvent(2L, "PLAY", -1L, 1);
        fixture.markDirty(VID);

        for (int attempt = 1; attempt <= 40; attempt++) {
            fixture.runScheduler();
            assertEquals(1, fixture.statusOf(2L),
                    "attempt=" + attempt + "：仍不允许篡改为 0 或隔离");
            assertEquals(0, fixture.statusOf(1L));
            assertEquals(0, fixture.playOf(VID));
            assertTrue(fixture.dirtyContains(VID),
                    "attempt=" + attempt + "：dirty 必须一直保留");
            fixture.advance(BEYOND_MAX_BACKOFF_MILLIS);
        }

        assertTrue(fixture.repairedIds().isEmpty(),
                "超过延后预算只允许告警，不允许转人工修复");
        assertFalse(fixture.repairErrors().stream()
                .anyMatch(error -> error.contains("非负")));
    }
}
