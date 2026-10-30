package com.feibijiubi.backend.videostats.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T03 flush 单元测试：错误分类、混合字段、拆批/重复、status0 长期滞留、退避门闩。
 *
 * <p>分类原则（实现见 {@code VideoStatusBatchFlushServiceImpl}）：</p>
 * <ul>
 *   <li>未知事件类型 / 统计行缺失 → 受控修复(status 3)；</li>
 *   <li>不能立即证明非法（下界不足、上界不足、批次净增量超出 Long）→
 *       延后重试（status 1 + dirty 保留 + 常量退避）；</li>
 *   <li>其余 DB 未命中/访问故障 → 重试。</li>
 * </ul>
 */
class VideoStatusFlushDeferralTests {

    private static final int VID = VideoStatusFlushTestFixture.DEFAULT_VID;
    private static final long BEYOND_MAX_BACKOFF_MILLIS = 30_001L;

    @Test
    void mixedFieldBatchDefersWholeBatchWithoutPartialApply() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        // play=5 足够 +3，但 unlike=0 不足以 -1：整批必须延后，不能部分落库。
        fixture.addRow(VID, 5, 0, 0);
        fixture.addEvent(1L, "PLAY", 3L, 1);
        fixture.addEvent(2L, "UNLIKE", -1L, 1);
        fixture.addEvent(3L, "UNLIKE", 1L, 0); // 依赖事件仍在 RECEIVED(0)
        fixture.markDirty(VID);

        fixture.runScheduler();

        assertEquals(1, fixture.applyCalls);
        assertEquals(5, fixture.playOf(VID), "延后不允许部分落库");
        assertEquals(0, fixture.unlikeOf(VID));
        assertEquals(1, fixture.statusOf(1L));
        assertEquals(1, fixture.statusOf(2L));
        assertEquals(0, fixture.statusOf(3L));
        assertTrue(fixture.repairedIds().isEmpty());
        assertTrue(fixture.dirtyContains(VID));

        // 依赖事件状态提交完成 → 下一轮整批净增量为 0，正常落库
        fixture.setEventStatus(3L, 1);
        fixture.advance(BEYOND_MAX_BACKOFF_MILLIS);
        fixture.runScheduler();

        assertEquals(8, fixture.playOf(VID));
        assertEquals(0, fixture.unlikeOf(VID));
        assertEquals(2, fixture.statusOf(1L));
        assertEquals(2, fixture.statusOf(2L));
        assertEquals(2, fixture.statusOf(3L));
        assertTrue(fixture.repairedIds().isEmpty());
        assertFalse(fixture.dirtyContains(VID));
    }

    @Test
    void missingStatusRowIsControlledRepair() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addEvent(1L, "PLAY", 1L, 1);
        fixture.markDirty(VID);

        fixture.runScheduler();

        assertEquals(3, fixture.statusOf(1L), "缺行必须转受控修复");
        assertEquals(1, fixture.repairedIds().size());
        assertEquals(List.of(1L), fixture.repairedIds().get(0));
        assertTrue(fixture.repairErrors().get(0).contains("行缺失"));
        assertFalse(fixture.dirtyContains(VID),
                "该 vid 已无待刷事件，不需要再保留 dirty");
    }

    @Test
    void upperBoundShortfallDefersLegalPlusOneInsteadOfRepairing() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        // DB 已达 Long.MAX，Redis 已应用取消 -1，但其状态提交暂停（仍是 status 0）。
        fixture.addRow(VID, Long.MAX_VALUE);
        fixture.addEvent(1L, "PLAY", 1L, 1);
        fixture.addEvent(2L, "PLAY", -1L, 0);
        fixture.markDirty(VID);

        fixture.runScheduler();

        assertEquals(1, fixture.applyCalls, "一次 0 行后必须查基线分类");
        assertEquals(1, fixture.statusOf(1L),
                "上界不足只是暂态，合法 +1 不允许隔离成 status 3");
        assertEquals(0, fixture.statusOf(2L));
        assertEquals(Long.MAX_VALUE, fixture.playOf(VID),
                "延后不得回绕、不得部分写入");
        assertTrue(fixture.repairedIds().isEmpty(), "0 次误修复");
        assertTrue(fixture.dirtyContains(VID), "延后必须保留 dirty 标记");

        // 取消事件状态提交完成 → 净零，正常推进 FLUSHED，统计不变
        fixture.setEventStatus(2L, 1);
        fixture.advance(BEYOND_MAX_BACKOFF_MILLIS);
        fixture.runScheduler();

        assertEquals(2, fixture.statusOf(1L));
        assertEquals(2, fixture.statusOf(2L));
        assertEquals(Long.MAX_VALUE, fixture.playOf(VID));
        assertTrue(fixture.repairedIds().isEmpty());
        assertFalse(fixture.dirtyContains(VID));
    }

    @Test
    void pausedMinusOneCommitDefersLegalPlusOneAtUpperBoundInEveryRound() {
        int deferredRounds = 0;
        for (int round = 0; round < 100; round++) {
            VideoStatusFlushTestFixture fixture =
                    new VideoStatusFlushTestFixture();
            fixture.addRow(VID, Long.MAX_VALUE);
            // +1：Redis 已成功，markRedisApplied 未提交（status 0，flush 看不到）
            fixture.addEvent(1L, "PLAY", 1L, 1);
            // -1：Redis 已应用取消，状态提交同样未完成（status 0）
            fixture.addEvent(2L, "PLAY", -1L, 0);
            fixture.markDirty(VID);

            fixture.runScheduler();

            assertEquals(1, fixture.statusOf(1L),
                    "round=" + round + "：上界不足的 +1 只能待刷，不允许隔离");
            assertEquals(0, fixture.statusOf(2L), "round=" + round);
            assertEquals(Long.MAX_VALUE, fixture.playOf(VID),
                    "round=" + round + "：不得回绕或部分写入");
            assertTrue(fixture.repairedIds().isEmpty(),
                    "round=" + round + "：0 次误修复");
            assertTrue(fixture.dirtyContains(VID),
                    "round=" + round + "：延后必须保留 dirty");
            deferredRounds++;

            // 释放窗口：取消事件状态提交完成 → 净零，两个合法事件一起 FLUSHED
            fixture.setEventStatus(2L, 1);
            fixture.advance(BEYOND_MAX_BACKOFF_MILLIS);
            fixture.runScheduler();

            assertEquals(2, fixture.statusOf(1L), "round=" + round);
            assertEquals(2, fixture.statusOf(2L), "round=" + round);
            assertEquals(Long.MAX_VALUE, fixture.playOf(VID),
                    "round=" + round + "：最终统计必须不变");
            assertTrue(fixture.repairedIds().isEmpty(), "round=" + round);
            assertFalse(fixture.dirtyContains(VID), "round=" + round);
        }
        assertEquals(100, deferredRounds);
    }

    @Test
    void intermediateOverflowThatNetsWithinLongIsAppliedNotQuarantined() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, 0);
        // 正增量优先排序后是 +MAX, +1, -MAX：逐步 Math.addExact 会在中间步溢出，
        // 但最终净量是 +1，完全合法。
        fixture.addEvent(1L, "PLAY", Long.MAX_VALUE, 1);
        fixture.addEvent(2L, "PLAY", -Long.MAX_VALUE, 1);
        fixture.addEvent(3L, "PLAY", 1L, 1);
        fixture.markDirty(VID);

        fixture.runScheduler();

        assertEquals(1, fixture.applyCalls, "净量合法就必须落库");
        assertEquals(1, fixture.playOf(VID));
        assertEquals(2, fixture.statusOf(1L));
        assertEquals(2, fixture.statusOf(2L));
        assertEquals(2, fixture.statusOf(3L));
        assertTrue(fixture.repairedIds().isEmpty(),
                "中间溢出不得把合法事件误修复");
        assertFalse(fixture.dirtyContains(VID));
    }

    @Test
    void netDeltaBeyondLongDefersInsteadOfRepairing() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, 0);
        // 两条合法 +MAX 事件：最终净量超出 long，无法一次落库，但事件本身不非法。
        fixture.addEvent(1L, "PLAY", Long.MAX_VALUE, 1);
        fixture.addEvent(2L, "PLAY", Long.MAX_VALUE, 1);
        fixture.markDirty(VID);

        fixture.runScheduler();

        assertEquals(0, fixture.applyCalls, "净量非法则不得进入 SQL");
        assertEquals(1, fixture.statusOf(1L));
        assertEquals(1, fixture.statusOf(2L));
        assertEquals(0, fixture.playOf(VID));
        assertTrue(fixture.repairedIds().isEmpty(),
                "净量超出 long 只能延后等拆批，不允许转人工修复");
        assertTrue(fixture.dirtyContains(VID));
    }

    @Test
    void unknownEventTypeIsControlledRepairWithoutSqlUpdate() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, 0);
        fixture.addEvent(1L, "NOT_A_TYPE", 1L, 1);
        fixture.markDirty(VID);

        fixture.runScheduler();

        assertEquals(0, fixture.applyCalls, "未知类型不允许进入 SQL");
        assertEquals(3, fixture.statusOf(1L));
        assertTrue(fixture.repairErrors().get(0).contains("未知统计类型"));
    }

    @Test
    void zeroRowUpdateWithSufficientBaselineIsRetriedNotQuarantined() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, 10);
        fixture.addEvent(1L, "PLAY", 1L, 1);
        fixture.markDirty(VID);
        // Java 侧守卫成立（10+1 合法），SQL 却未命中行 → 只能视为暂态重试
        fixture.forceApplyBatchDeltaResult(0);

        fixture.runScheduler();

        assertEquals(1, fixture.statusOf(1L));
        assertTrue(fixture.repairedIds().isEmpty(), "暂态未命中不得误隔离");
        assertTrue(fixture.dirtyContains(VID));
        assertEquals(10, fixture.playOf(VID));
    }

    @Test
    void deferralBackoffBlocksLockAndSqlUntilDue() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, 0);
        fixture.addEvent(1L, "PLAY", -1L, 1);
        fixture.markDirty(VID);

        fixture.runScheduler();
        assertEquals(1, fixture.lockCalls);
        assertEquals(1, fixture.pendingQueries);

        // 退避期内：不取 vid 锁、不查 SQL，但 dirty 必须保留
        fixture.runScheduler();
        assertEquals(1, fixture.lockCalls, "退避期内不允许再次取锁");
        assertEquals(1, fixture.pendingQueries, "退避期内不允许再次查询待刷事件");
        assertTrue(fixture.dirtyContains(VID));

        fixture.advance(999);
        fixture.runScheduler();
        assertEquals(1, fixture.lockCalls, "常量退避至少 1 秒");

        // 30 秒大于规范允许的最大常量退避（1s..30s），必须到期重试
        fixture.advance(BEYOND_MAX_BACKOFF_MILLIS - 999);
        fixture.runScheduler();
        assertEquals(2, fixture.lockCalls);
        assertEquals(2, fixture.pendingQueries);
        assertEquals(1, fixture.statusOf(1L));
    }

    @Test
    void successfulFlushClearsBackoffSoNextDeferIsRetriedImmediately() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, 0);
        fixture.addEvent(2L, "PLAY", -1L, 1);
        fixture.markDirty(VID);

        fixture.runScheduler();
        assertEquals(1, fixture.lockCalls);
        assertEquals(1, fixture.statusOf(2L));

        // 依赖的 +1 变得可见，同批净零 → 正常 FLUSHED（不写统计）
        fixture.addEvent(3L, "PLAY", 1L, 1);
        fixture.advance(BEYOND_MAX_BACKOFF_MILLIS);
        int applyCallsBeforeConvergence = fixture.applyCalls;
        fixture.runScheduler();
        assertEquals(2, fixture.lockCalls);
        assertEquals(applyCallsBeforeConvergence, fixture.applyCalls,
                "净零批次不再写统计");
        assertEquals(2, fixture.statusOf(2L));
        assertEquals(2, fixture.statusOf(3L));

        // 成功刷库必须清空退避：新的延后无需等待退避即可被重试
        fixture.addEvent(4L, "PLAY", -1L, 1);
        fixture.markDirty(VID);
        fixture.runScheduler();
        assertEquals(3, fixture.lockCalls);
        assertEquals(1, fixture.statusOf(4L));
        assertTrue(fixture.repairedIds().isEmpty());
    }

    @Test
    void batchSplitAndDuplicateFlushConvergeWithoutDoubleCounting() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, 0);
        fixture.setFlushEventBatchSize(2);
        for (long id = 1; id <= 5; id++) {
            fixture.addEvent(id, "PLAY", 1L, 1);
        }
        fixture.markDirty(VID);

        fixture.runScheduler();
        assertEquals(2, fixture.playOf(VID));
        fixture.runScheduler();
        assertEquals(4, fixture.playOf(VID));
        fixture.runScheduler();
        assertEquals(5, fixture.playOf(VID));
        assertEquals(3, fixture.applyCalls);

        // 重复触发（无待刷事件）不得重复计数
        fixture.runScheduler();
        assertEquals(5, fixture.playOf(VID));
        assertEquals(3, fixture.applyCalls);
        for (long id = 1; id <= 5; id++) {
            assertEquals(2, fixture.statusOf(id), "id=" + id + " 必须 FLUSHED");
        }
        assertTrue(fixture.repairedIds().isEmpty());
        assertFalse(fixture.dirtyContains(VID));
    }

    @Test
    void splitBatchLimitOneUpperBoundConvergesBothLegalPlusOneAndMinusOne() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        // 上限边界 + 拆批 LIMIT=1：两个合法事件都是 status 1，
        // “正增量优先”会一直选中 +1，必须靠一次负增量优先备选让 -1 前进。
        fixture.addRow(VID, Long.MAX_VALUE);
        fixture.setFlushEventBatchSize(1);
        fixture.addEvent(1L, "PLAY", 1L, 1);
        fixture.addEvent(2L, "PLAY", -1L, 1);
        fixture.markDirty(VID);

        // 第 1 轮：初批 +1 触上界 → 备选 -1 落库，初批保持 status 1
        fixture.runScheduler();
        assertEquals(1, fixture.negativeFirstQueries,
                "上限失败必须且只能查一次负增量优先备选");
        assertEquals(Long.MAX_VALUE - 1, fixture.playOf(VID));
        assertEquals(2, fixture.statusOf(2L), "合法 -1 必须能自动落库");
        assertEquals(1, fixture.statusOf(1L), "初批不得被挪状态，下一轮再试");
        assertTrue(fixture.repairedIds().isEmpty(), "0 次误修复");
        assertTrue(fixture.dirtyContains(VID));

        // 第 2 轮：剩余 +1 在上界余量内正常落库，批次收敛
        fixture.runScheduler();
        assertEquals(Long.MAX_VALUE, fixture.playOf(VID));
        assertEquals(2, fixture.statusOf(1L));
        assertEquals(2, fixture.statusOf(2L));
        assertTrue(fixture.repairedIds().isEmpty());
        assertFalse(fixture.dirtyContains(VID), "收敛后不应残留 dirty");
        assertEquals(3, fixture.applyCalls, "两批共 3 次 UPDATE 尝试（含 1 次 0 行）");
    }

    @Test
    void splitBatchLimitOneUpperBoundConvergesInEveryRound() {
        for (int round = 0; round < 100; round++) {
            VideoStatusFlushTestFixture fixture =
                    new VideoStatusFlushTestFixture();
            fixture.addRow(VID, Long.MAX_VALUE);
            fixture.setFlushEventBatchSize(1);
            fixture.addEvent(1L, "PLAY", 1L, 1);
            fixture.addEvent(2L, "PLAY", -1L, 1);
            fixture.markDirty(VID);

            fixture.runScheduler();
            fixture.runScheduler();

            assertEquals(2, fixture.statusOf(1L), "round=" + round);
            assertEquals(2, fixture.statusOf(2L), "round=" + round);
            assertEquals(Long.MAX_VALUE, fixture.playOf(VID),
                    "round=" + round + "：最终统计必须回到上界，不回绕");
            assertTrue(fixture.repairedIds().isEmpty(),
                    "round=" + round + "：0 次误修复");
            assertFalse(fixture.dirtyContains(VID), "round=" + round);
            assertEquals(1, fixture.negativeFirstQueries,
                    "round=" + round + "：备选只允许一次");
        }
    }

    @Test
    void netOverflowUsesOneNegativeFirstFallbackToMakeProgress() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, Long.MAX_VALUE);
        fixture.setFlushEventBatchSize(2);
        fixture.addEvent(1L, "PLAY", Long.MAX_VALUE, 1);
        fixture.addEvent(2L, "PLAY", Long.MAX_VALUE, 1);
        fixture.addEvent(3L, "PLAY", -Long.MAX_VALUE, 1);
        fixture.markDirty(VID);

        // 第 1 轮：初批 [+MAX, +MAX] 净量超 long → 备选 [-MAX] 可刷
        fixture.runScheduler();
        assertEquals(1, fixture.applyCalls,
                "初批净量超 long 不得进入 SQL，只有备选批次一次 UPDATE");
        assertEquals(2, fixture.statusOf(3L), "备选批次必须 FLUSHED");
        assertEquals(1, fixture.statusOf(1L));
        assertEquals(1, fixture.statusOf(2L));
        assertEquals(0, fixture.playOf(VID));
        assertTrue(fixture.repairedIds().isEmpty());
        assertTrue(fixture.dirtyContains(VID));

        // 第 2 轮：备选已无新行（不递归、不重复刷）→ 延后
        fixture.advance(BEYOND_MAX_BACKOFF_MILLIS);
        fixture.runScheduler();
        assertEquals(2, fixture.negativeFirstQueries, "每轮最多一次备选");
        assertEquals(1, fixture.statusOf(1L));
        assertEquals(1, fixture.statusOf(2L));
        assertEquals(0, fixture.playOf(VID));
        assertTrue(fixture.repairedIds().isEmpty(), "仍不得转人工修复");
        assertTrue(fixture.dirtyContains(VID));
    }

    @Test
    void negativeFirstFallbackStillAtBoundaryDefersWithoutRecursion() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, Long.MAX_VALUE);
        fixture.setFlushEventBatchSize(1);
        fixture.addEvent(1L, "PLAY", 1L, 1);
        fixture.addEvent(2L, "PLAY", 1L, 1);
        fixture.markDirty(VID);

        for (int attempt = 1; attempt <= 30; attempt++) {
            fixture.runScheduler();
            assertEquals(attempt, fixture.negativeFirstQueries,
                    "attempt=" + attempt + "：每轮只允许一次备选，不得递归");
            assertEquals(attempt, fixture.applyCalls,
                    "attempt=" + attempt + "：备选与初批同 id 时不得重复 UPDATE");
            assertEquals(1, fixture.statusOf(1L), "attempt=" + attempt);
            assertEquals(1, fixture.statusOf(2L), "attempt=" + attempt);
            assertEquals(Long.MAX_VALUE, fixture.playOf(VID),
                    "attempt=" + attempt);
            assertTrue(fixture.repairedIds().isEmpty(),
                    "attempt=" + attempt + "：仍不得转人工修复");
            assertTrue(fixture.dirtyContains(VID), "attempt=" + attempt);
            fixture.advance(BEYOND_MAX_BACKOFF_MILLIS);
        }
    }

    @Test
    void lowerBoundDeferDoesNotQueryNegativeFallback() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, 0);
        fixture.addEvent(2L, "PLAY", -1L, 1);
        fixture.markDirty(VID);

        fixture.runScheduler();

        assertEquals(1, fixture.pendingQueries);
        assertEquals(0, fixture.negativeFirstQueries,
                "下界不足需要正增量，换负增量优先没有意义，不应查备选");
        assertEquals(1, fixture.statusOf(2L));
        assertEquals(0, fixture.playOf(VID));
        assertTrue(fixture.dirtyContains(VID));
    }

    @Test
    void unknownTypeInsideNegativeFallbackKeepsControlledRepair() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, Long.MAX_VALUE);
        fixture.setFlushEventBatchSize(1);
        fixture.addEvent(1L, "PLAY", 1L, 1);
        fixture.addEvent(2L, "NOT_A_TYPE", -1L, 1);
        fixture.markDirty(VID);

        fixture.runScheduler();

        // 未知类型仍然只能受控修复，不能被“延后”吞掉；初批保持 status 1。
        assertEquals(3, fixture.statusOf(2L));
        assertEquals(1, fixture.statusOf(1L));
        assertTrue(fixture.repairErrors().get(0).contains("未知统计类型"));
        assertEquals(Long.MAX_VALUE, fixture.playOf(VID));
        assertTrue(fixture.dirtyContains(VID),
                "初批仍未刷库，必须保留 dirty");
    }

    @Test
    void statusZeroDependencyStuckKeepsDeferringWithoutQuarantine() {
        VideoStatusFlushTestFixture fixture =
                new VideoStatusFlushTestFixture();
        fixture.addRow(VID, 0);
        fixture.addEvent(1L, "PLAY", 1L, 0); // 永远停在 RECEIVED(0)
        fixture.addEvent(2L, "PLAY", -1L, 1);
        fixture.markDirty(VID);

        for (int attempt = 1; attempt <= 35; attempt++) {
            fixture.runScheduler();
            assertEquals(1, fixture.statusOf(2L), "attempt=" + attempt);
            assertEquals(0, fixture.statusOf(1L), "attempt=" + attempt);
            assertEquals(0, fixture.playOf(VID), "attempt=" + attempt);
            assertTrue(fixture.dirtyContains(VID), "attempt=" + attempt);
            assertTrue(fixture.repairedIds().isEmpty(),
                    "超过预算只告警，不误隔离: attempt=" + attempt);
            fixture.advance(BEYOND_MAX_BACKOFF_MILLIS);
        }
        assertEquals(35, fixture.lockCalls, "每次退避到期都必须真正重试");
    }
}
