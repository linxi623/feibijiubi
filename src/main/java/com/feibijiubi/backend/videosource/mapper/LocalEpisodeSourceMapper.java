package com.feibijiubi.backend.videosource.mapper;

import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.videosource.entity.LocalEpisodeMediaCandidate;
import com.feibijiubi.backend.videosource.entity.LocalEpisodeSourceBinding;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface LocalEpisodeSourceMapper {
    LocalVideoEpisode lockEpisode(@Param("id") long id);

    /**
     * 已导入剧集确认线路时锁定原 video 行。调用方必须先持有剧集行锁，保持 episode → video 的
     * 固定顺序，避免与软删/改稿并发时基于过期状态加线；本查询不写 video。
     */
    Video lockVideoForUpdate(@Param("vid") int vid);
    Long selectRevision(@Param("episodeId") long episodeId);
    int initializeState(@Param("episodeId") long episodeId);
    int advanceRevision(@Param("episodeId") long episodeId, @Param("expected") long expected);
    int insertCandidate(LocalEpisodeMediaCandidate candidate);
    LocalEpisodeMediaCandidate selectCandidate(@Param("episodeId") long episodeId, @Param("id") long id);
    List<LocalEpisodeMediaCandidate> selectCandidates(@Param("episodeId") long episodeId,
                                                     @Param("afterId") long afterId, @Param("limit") int limit);
    List<LocalEpisodeSourceBinding> selectBindings(@Param("episodeId") long episodeId);
    int insertBinding(LocalEpisodeSourceBinding binding);
    int replaceBinding(@Param("episodeId") long episodeId, @Param("id") long id, @Param("candidateId") long candidateId);
    int confirmCandidate(@Param("episodeId") long episodeId, @Param("id") long id,
                         @Param("seconds") double seconds, @Param("adminId") int adminId, @Param("reason") String reason);
    int setDuration(@Param("episodeId") long episodeId, @Param("seconds") double seconds);
}
