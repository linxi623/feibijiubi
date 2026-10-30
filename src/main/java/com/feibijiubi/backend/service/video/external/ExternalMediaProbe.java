package com.feibijiubi.backend.service.video.external;

import com.feibijiubi.backend.integration.videosource.media.HlsMediaDurationCandidate;

/** Production implementation uses the independent media allowlist, never the CMS API policy. */
public interface ExternalMediaProbe {
    HlsMediaDurationCandidate probe(ExternalVideoSourceDescriptor source, String address);
}
