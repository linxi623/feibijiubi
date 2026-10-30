package com.feibijiubi.backend.videosource.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LocalEpisodeSourceBinding {
    private Long id;
    private Long episodeId;
    private Long candidateId;
}
