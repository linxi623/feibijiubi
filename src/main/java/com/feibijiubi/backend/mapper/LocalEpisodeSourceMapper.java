package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.LocalEpisodeMediaCandidate;
import com.feibijiubi.backend.entity.LocalEpisodeSourceBinding;
import com.feibijiubi.backend.entity.LocalVideoEpisode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface LocalEpisodeSourceMapper {
    LocalVideoEpisode lockEpisode(@Param("id") long id);
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
