package com.feibijiubi.backend.interaction.service.impl;

import com.feibijiubi.backend.interaction.service.VideoInteractionCleanupService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保留期常量与 30 分钟滚动窗口之间的量级关系。
 *
 * <p>放在实现包内是为了直接读 {@link VideoInteractionServiceImpl#WINDOW_MINUTES}（包内可见）：
 * 两个常量分属“清理”和“计数语义”，一旦有人把保留期改小到与窗口同一量级，清理就会开始删掉仍在
 * 30 分钟窗口内的行，<b>静默</b>绕过限计。这条不变量是唯一能在纯单测里钉住它的地方。</p>
 */
class VideoInteractionCleanupRetentionTests {

    @Test
    void retentionIsTheApprovedFixedPolicy() {
        assertEquals(30, VideoInteractionCleanupService.REQUEST_RETENTION_DAYS,
                "请求行保留期已批准为 30 天（自 completed_at 起算），且不可配置");
        assertEquals(30, VideoInteractionCleanupService.WINDOW_RETENTION_DAYS,
                "窗口行保留期已批准为连续 30 天无计数，且不可配置");
    }

    @Test
    void retentionIsFarLargerThanTheRollingWindow() {
        long windowMinutesCoveredByRetention =
                (long) VideoInteractionCleanupService.WINDOW_RETENTION_DAYS * 24 * 60;

        assertTrue(windowMinutesCoveredByRetention > VideoInteractionServiceImpl.WINDOW_MINUTES * 24,
                "清理保留期必须比 30 分钟滚动窗口大几个数量级：同一量级意味着清理会删掉仍然有效的窗口行");
    }
}
