package com.feibijiubi.backend.videosource.service.impl;

import com.feibijiubi.backend.videosource.integration.media.HlsDurationProbe;
import com.feibijiubi.backend.videosource.integration.media.HlsMediaDurationCandidate;
import com.feibijiubi.backend.videosource.integration.transport.RestrictedManifestHttpClient;
import com.feibijiubi.backend.videosource.integration.transport.ValidatedDnsResolver;
import com.feibijiubi.backend.videosource.service.ExternalMediaProbe;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceDescriptor;
import org.springframework.stereotype.Component;
import java.net.InetAddress;

@Component
public class RestrictedExternalMediaProbe implements ExternalMediaProbe {
    @Override
    public HlsMediaDurationCandidate probe(ExternalVideoSourceDescriptor source, String address) {
        return new HlsDurationProbe(new RestrictedManifestHttpClient(
                source.mediaPolicy().orElseThrow(), new ValidatedDnsResolver(InetAddress::getAllByName)))
                .probe(address);
    }
}
