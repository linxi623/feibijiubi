package com.feibijiubi.backend.vo;

import com.feibijiubi.backend.entity.LocalEpisodeMediaCandidate;
import java.time.LocalDateTime;
import java.util.List;

/** Administrator projection; no address, line key or address fingerprint. Escape untrusted label in UI. */
public record LocalEpisodeSourceVO(long revision, Double durationSeconds, List<Binding> bindings) {
    public record Binding(long id, Candidate candidate) {}
    public record Candidate(long id, long episodeId, long expectedRevision, Long targetBindingId,
            String sourceCode, String workId, int lineOrdinal, int entryOrdinal, String label,
            String probeStatus, Double candidateSeconds, LocalDateTime capturedAt,
            Double confirmedSeconds, Integer confirmedBy, LocalDateTime confirmedAt, String reason) {
        public static Candidate from(LocalEpisodeMediaCandidate c) {
            return new Candidate(c.getId(), c.getEpisodeId(), c.getExpectedRevision(), c.getTargetBindingId(),
                    c.getSourceCode(), c.getWorkId(), c.getLineOrdinal(), c.getEntryOrdinal(), c.getLabel(),
                    c.getProbeStatus(), c.getCandidateSeconds(), c.getCapturedAt(), c.getConfirmedSeconds(),
                    c.getConfirmedBy(), c.getConfirmedAt(), c.getConfirmationReason());
        }
    }
}
