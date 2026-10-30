package com.feibijiubi.backend.service.impl.video.external;

import com.feibijiubi.backend.integration.videosource.media.HlsDurationProbe;
import com.feibijiubi.backend.integration.videosource.media.HlsMediaDurationCandidate;
import com.feibijiubi.backend.integration.videosource.transport.RestrictedManifestHttpClient;
import com.feibijiubi.backend.integration.videosource.transport.ValidatedDnsResolver;
import com.feibijiubi.backend.service.video.external.ExternalMediaProbe;
import com.feibijiubi.backend.service.video.external.ExternalVideoSourceDescriptor;
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
