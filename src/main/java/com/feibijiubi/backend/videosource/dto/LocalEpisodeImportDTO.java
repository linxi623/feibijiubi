package com.feibijiubi.backend.videosource.dto;

/**
 * M5 管理员单集导入请求。
 *
 * <p>只接受管理员必须决定的值：预期来源 revision、当前管理员的临时封面 key、显式视频标题、
 * 二级分类组合与可见性。刻意<b>没有</b> uid / vid / sourceType / status / duration /
 * videoUrl / videoKey 字段：作者取登录态，来源类型固定 2（转载），审核状态固定 PENDING，
 * 时长取剧集已确认的统一时长，播放地址与资源 key 不由本接口写入。</p>
 *
 * <p>未知字段按项目现有 JSON 策略忽略且不生效，不会因为多传 {@code duration} 或 {@code uid}
 * 就被应用。</p>
 */
public record LocalEpisodeImportDTO(
        Long expectedRevision,
        String tempCoverKey,
        String title,
        String mcId,
        String scId,
        Integer visibility) {
}
