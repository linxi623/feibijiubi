package com.feibijiubi.backend.service.localvideo;

import com.feibijiubi.backend.dto.LocalEpisodeSourceCaptureDTO;
import com.feibijiubi.backend.dto.LocalEpisodeSourceConfirmDTO;
import com.feibijiubi.backend.vo.LocalEpisodeSourceVO;
import java.util.List;

public interface LocalEpisodeSourceService {
    LocalEpisodeSourceVO.Candidate capture(Integer adminId, long episodeId, LocalEpisodeSourceCaptureDTO request);
    LocalEpisodeSourceVO confirm(Integer adminId, long episodeId, long candidateId, LocalEpisodeSourceConfirmDTO request);
    LocalEpisodeSourceVO current(long episodeId);
    List<LocalEpisodeSourceVO.Candidate> candidates(long episodeId, long afterId, int limit);
}
