package com.feibijiubi.backend.videosource.mapper;

import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * M5 单集导入事务的持久化语句。
 *
 * <p>负责锁剧集、读取来源证据、绑定 vid 和导入专用封面认领。这里不读取候选地址/摘要；
 * video 与 video_status 仍由各自的 mapper 写入。</p>
 *
 * <p>导入事务的正确性来自：{@code lockEpisodeForImport} 的 {@code FOR UPDATE} 行锁、
 * {@code bindVidAndImportRevision} 的 {@code vid IS NULL} CAS（影响行数必须为 1），以及
 * {@code local_video_episode.uk_local_video_episode_vid} 唯一键的最后防线。唯一键本身不能阻止
 * 同集并发各建一个 video，行锁 + CAS 才可以。</p>
 */
@Mapper
public interface LocalEpisodeImportMapper {

    /** 锁定本地剧集行；返回导入判定所需的 id / duration / vid / import_revision。 */
    LocalVideoEpisode lockEpisodeForImport(@Param("id") long id);

    /** 当前来源 revision；缺行等价于 0。 */
    Long selectSourceRevision(@Param("episodeId") long episodeId);

    /**
     * 只读已确认来源的 source_code（去重由服务层做）。刻意不投影 address / address_hash /
     * manifest_hash：M5 不读取、不返回、不记录媒体地址。
     *
     * <p>结果最多 33 行（与 S2 的关联上限一致），用于判定“至少存在一个已确认来源”。</p>
     */
    List<String> selectConfirmedSourceCodes(@Param("episodeId") long episodeId);

    /**
     * 首次导入 CAS：只有 {@code vid IS NULL} 时才写入 vid 与首次导入 revision。
     * 影响行数不是 1 时服务层必须回滚。
     */
    int bindVidAndImportRevision(@Param("episodeId") long episodeId,
                                 @Param("vid") int vid,
                                 @Param("revision") long revision);

    /** 认领时原子检查所有者、类型、未认领且未过期；时间与应用创建 expire_at 的时钟一致。 */
    int claimTempCover(@Param("objectKey") String objectKey,
                       @Param("uid") int uid,
                       @Param("now") LocalDateTime now);
}
