package com.feibijiubi.backend.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LocalEpisodeSourceBinding {
    private Long id;
    private Long episodeId;
    private Long candidateId;
}
