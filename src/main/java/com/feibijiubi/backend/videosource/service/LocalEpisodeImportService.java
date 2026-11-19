package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.videosource.dto.LocalEpisodeImportDTO;
import com.feibijiubi.backend.videosource.vo.LocalEpisodeImportVO;

/**
 * M5：管理员把已确认来源与统一时长的本地剧集导入为一个 {@code video}。
 *
 * <p>首次导入在同一个短事务里创建 PENDING video、初始化 video_status、以
 * {@code vid IS NULL} CAS 绑定剧集并认领封面；已绑定且未软删的重复请求直接返回原 vid，
 * 不修改作者、视频、统计或来源；已软删的绑定固定 409，不复活。</p>
 */
public interface LocalEpisodeImportService {

    /** {@code video.title} 的列宽。 */
    int MAX_TITLE_LENGTH = 80;
    /** {@code category.mc_id / sc_id} 的列宽。 */
    int MAX_CATEGORY_ID_LENGTH = 20;
    /** {@code upload_temp_file.object_key} 的列宽。 */
    int MAX_TEMP_COVER_KEY_LENGTH = 500;

    /**
     * 导入一集。
     *
     * @param adminId   登录态管理员 uid；{@code null} 时 401
     * @param episodeId 本地剧集 id，必须为正
     * @param request   预期 revision、临时封面 key、标题、分类与可见性
     */
    LocalEpisodeImportVO importEpisode(Integer adminId, long episodeId, LocalEpisodeImportDTO request);
}
