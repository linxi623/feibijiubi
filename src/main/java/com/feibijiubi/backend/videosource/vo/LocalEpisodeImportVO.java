package com.feibijiubi.backend.videosource.vo;

/**
 * M5 单集导入结果。
 *
 * <p>只返回稳定业务标识与状态：本地剧集 id、video.vid、统一时长、首次导入来源 revision、
 * 审核状态与本次是否复用。刻意不返回 cover key / cover URL / video URL / video key，也不返回
 * 任何来源地址或摘要。</p>
 *
 * @param episodeId       本地剧集 id
 * @param vid             已有或新建的 {@code video.vid}
 * @param durationSeconds 剧集已确认的统一时长（不是探测候选秒数）
 * @param importRevision  首次导入时观察到的来源 revision；重试返回同一值
 * @param status          {@code video.status}，首次导入为 0（PENDING）
 * @param reused          true = 本次没有创建 video/status，只返回既有绑定
 */
public record LocalEpisodeImportVO(
        long episodeId,
        int vid,
        double durationSeconds,
        Long importRevision,
        int status,
        boolean reused) {
}
