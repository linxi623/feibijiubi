package com.feibijiubi.backend.videosource.dto;

/** No URL, hash, probe-success flag or operator accepted from the client. */
public record LocalEpisodeSourceConfirmDTO(Long expectedRevision, Double durationSeconds, String reason) {}
