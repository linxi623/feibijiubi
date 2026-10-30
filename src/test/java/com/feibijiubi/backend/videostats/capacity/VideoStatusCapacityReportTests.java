package com.feibijiubi.backend.videostats.capacity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T14-A 报告渲染用例：纯文本，不连接任何基础设施。
 *
 * <p>验收“UNKNOWN 不渲染成 0”“候选一律 NOT_AUTHORIZED_FOR_DELETION”“noeviction 处置建议不删门闩”
 * 与“不做 COUNT(*) 全表统计”这些口径必须出现在报告里，而不是只存在于代码注释。</p>
 */
class VideoStatusCapacityReportTests {

    @Test
    void reportRendersReadOnlyDryRunContractAndNeverZeroForUnknown() {
        VideoStatusCapacityReport report = sampleReport();
        String text = report.render();

        assertTrue(text.contains(VideoStatusCapacityReport.MODE_READ_ONLY_DRY_RUN));
        assertTrue(text.contains("writePerformed=false"));
        assertTrue(text.contains(VideoStatusCapacityReport.RETENTION_PERIOD_UNDECIDED));
        assertTrue(text.contains("不执行 COUNT(*) 全表统计"), "必须说明行数是估算，不是 COUNT(*)");
        assertTrue(text.contains("id=42"), "候选只渲染 id/时间/状态");
        assertTrue(text.contains("deletion=" + VideoStatusCapacityReport.DELETION_NOT_AUTHORIZED));
        assertTrue(text.contains("INDEX_MISSING"), "缺索引必须出现在报告里");
        assertTrue(text.contains("禁止删除重建门闩"), "noeviction 处置建议必须包含不删门闩");
        assertTrue(text.contains("未统计 processed key 总数"), "不能声称统计了 processed key 总数");
        assertTrue(text.contains("SCAN 命令 20/20 次（COUNT=200 只是 hint"),
                "报告必须把 COUNT 标为 hint、把命令次数标为硬上限");
        assertTrue(text.contains("游标归零=否（覆盖截断"),
                "游标未归零必须显式渲染为覆盖截断，不能说遍历完成");
        assertTrue(text.contains("UNKNOWN（"), "数据不足的估算必须渲染为 UNKNOWN");
        // 估算行的格式是 "label: low 单位 ～ high 单位"，UNKNOWN 时整行只有原因，不会出现 ": 0 ..."。
        assertFalse(text.contains(": 0 B/天"), "UNKNOWN 不能渲染成 0 B/天");
        assertFalse(text.contains(": 0 天"), "UNKNOWN 不能渲染成 0 天耗尽");
        assertEquals(2, report.tables().size(), "体积估算必须覆盖被审计的表");
        assertEquals(0L, report.memory().maxMemoryBytes(), "maxmemory=0 表示未设置上限");
        assertFalse(report.writePerformed());
        assertFalse(report.deletionAuthorized());
    }

    @Test
    void reportOmitsAnyConnectionOrCredentialDetail() {
        String text = sampleReport().render();

        // 报告可以出现“不读取 payload”这类说明文字，但不能出现任何数据形态的连接串/凭证/事件字段值。
        for (String forbidden : List.of("jdbc:", "127.0.0.1", "password", "isolated-test-only",
                "payload=", "payload:", "last_error=", "event_id=", "eventId=")) {
            assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains(forbidden.toLowerCase(java.util.Locale.ROOT)),
                    "报告不得包含连接串/凭证/事件内容: " + forbidden);
        }
    }

    private static VideoStatusCapacityReport sampleReport() {
        List<VideoStatusCapacityReport.TableSize> tables = List.of(
                new VideoStatusCapacityReport.TableSize(
                        VideoStatusCapacityAudit.TABLE_OUTBOX, 1_000L, 200_000L, 100_000L, 300_000L),
                new VideoStatusCapacityReport.TableSize(
                        VideoStatusCapacityAudit.TABLE_CONSUMED, null, null, null, null));
        List<VideoStatusCapacityReport.CandidateWindow> windows = List.of(
                new VideoStatusCapacityReport.CandidateWindow(VideoStatusCapacityAudit.TABLE_OUTBOX,
                        VideoStatusCapacityReport.WINDOW_EVALUABLE,
                        VideoStatusCapacityAudit.OUTBOX_WINDOW_INDEX,
                        "key=idx_video_status_outbox_created type=range rows≈1（EXPLAIN 估算）",
                        List.of(new VideoStatusCapacityReport.TerminalCandidate(
                                VideoStatusCapacityAudit.TABLE_OUTBOX, 42L,
                                "2025-01-01T00:00:00.000000", 2,
                                VideoStatusCapacityReport.DELETION_NOT_AUTHORIZED)),
                        true, null),
                new VideoStatusCapacityReport.CandidateWindow(VideoStatusCapacityAudit.TABLE_CONSUMED,
                        VideoStatusCapacityReport.WINDOW_NOT_EVALUABLE,
                        VideoStatusCapacityAudit.CONSUMED_WINDOW_INDEX, null, List.of(), false,
                        "INDEX_MISSING：缺索引不降级为全表扫描"));
        VideoStatusCapacityReport.RedisMemory memory = new VideoStatusCapacityReport.RedisMemory(
                1_000L, 2_000L, 0L, "noeviction", 5L, null);
        VideoStatusCapacityReport.ScanBudget budget = new VideoStatusCapacityReport.ScanBudget(20, 20, 100, 200);
        VideoStatusCapacityReport.ProcessedKeySample sample = new VideoStatusCapacityReport.ProcessedKeySample(
                0, 3_900, budget, false, null, null, null,
                VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN, "样本不足：命中 0 个 processed key");
        VideoStatusCapacityEstimator.Inputs inputs = new VideoStatusCapacityEstimator.Inputs(
                0L, 300L, null, null, null, 0, 1_000L, 0L, 0);
        List<VideoStatusCapacityReport.Estimate> estimates = List.of(
                new VideoStatusCapacityReport.Estimate("DB 日增（outbox+consumed）",
                        VideoStatusCapacityEstimator.dbDailyBytes(inputs), "B/天"),
                new VideoStatusCapacityReport.Estimate("Redis 预计耗尽天数",
                        VideoStatusCapacityEstimator.redisExhaustionDays(inputs), "天"));
        return new VideoStatusCapacityReport("2026-01-01T00:00:00.000000", tables, windows, memory,
                sample, inputs, estimates,
                List.of("DB 日增（outbox+consumed） UNKNOWN：假设日事件量未提供"), 30, 20);
    }
}
