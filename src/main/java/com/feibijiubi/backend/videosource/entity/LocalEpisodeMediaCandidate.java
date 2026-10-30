package com.feibijiubi.backend.videosource.entity;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Server-only persistence model. Deliberately no generated toString: address may contain credentials. */
@Getter
@Setter
public class LocalEpisodeMediaCandidate {
    private Long id;
    private Long episodeId;
    private Long expectedRevision;
    private Long targetBindingId;
    private String sourceCode;
    private String workId;
    private Integer lineOrdinal;
    private Integer entryOrdinal;
    private String label;
    private String address;
    private String addressHash;
    private String probeStatus;
    private Double candidateSeconds;
    private String manifestHash;
    private Integer capturedBy;
    private LocalDateTime capturedAt;
    private Double confirmedSeconds;
    private Integer confirmedBy;
    private LocalDateTime confirmedAt;
    private String confirmationReason;
}
