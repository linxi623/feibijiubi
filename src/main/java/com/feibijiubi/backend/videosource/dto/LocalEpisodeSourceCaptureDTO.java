package com.feibijiubi.backend.videosource.dto;

/** Positions select within a NEW snapshot, not within the earlier preview; confirmation is separate. */
public record LocalEpisodeSourceCaptureDTO(String sourceCode, String workId,
        Integer lineOrdinal, Integer entryOrdinal, Long expectedRevision, Long targetBindingId) {}
