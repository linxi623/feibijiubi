package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.videosource.integration.media.HlsMediaDurationCandidate;

/** Production implementation uses the independent media allowlist, never the CMS API policy. */
public interface ExternalMediaProbe {
    HlsMediaDurationCandidate probe(ExternalVideoSourceDescriptor source, String address);
}
