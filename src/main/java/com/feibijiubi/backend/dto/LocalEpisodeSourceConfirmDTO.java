package com.feibijiubi.backend.dto;

/** No URL, hash, probe-success flag or operator accepted from the client. */
public record LocalEpisodeSourceConfirmDTO(Long expectedRevision, Double durationSeconds, String reason) {}
