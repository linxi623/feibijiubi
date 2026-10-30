package com.feibijiubi.backend.integration.videosource.media;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1：{@link HlsMediaDurationCandidate} 的不可变性与安全字段用例。纯离线，无样例下载。
 */
class HlsMediaDurationCandidateTests {

    private static final String FINGERPRINT =
            "9f2c1b0d3e4a5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8";

    @Test
    void rejectsNonPositiveDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> new HlsMediaDurationCandidate(null, 1, FINGERPRINT));
        assertThrows(IllegalArgumentException.class,
                () -> new HlsMediaDurationCandidate(BigDecimal.ZERO, 1, FINGERPRINT));
        assertThrows(IllegalArgumentException.class,
                () -> new HlsMediaDurationCandidate(new BigDecimal("-0.5"), 1, FINGERPRINT));
    }

    @Test
    void rejectsDoubleOverflowAndUnderflow() {
        assertThrows(IllegalArgumentException.class,
                () -> new HlsMediaDurationCandidate(new BigDecimal("1E+400"), 1, FINGERPRINT));
        assertThrows(IllegalArgumentException.class,
                () -> new HlsMediaDurationCandidate(new BigDecimal("1E-400"), 1, FINGERPRINT));
    }

    @Test
    void rejectsNonPositiveSegmentCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new HlsMediaDurationCandidate(BigDecimal.ONE, 0, FINGERPRINT));
    }

    @Test
    void rejectsFingerprintThatIsNotLowercaseSha256Hex() {
        assertThrows(IllegalArgumentException.class,
                () -> new HlsMediaDurationCandidate(BigDecimal.ONE, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HlsMediaDurationCandidate(BigDecimal.ONE, 1, FINGERPRINT.toUpperCase()));
        assertThrows(IllegalArgumentException.class,
                () -> new HlsMediaDurationCandidate(BigDecimal.ONE, 1, FINGERPRINT.substring(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new HlsMediaDurationCandidate(BigDecimal.ONE, 1, "z".repeat(64)));
    }

    @Test
    void toStringExposesOnlyTheThreeSafeFields() {
        HlsMediaDurationCandidate candidate = new HlsMediaDurationCandidate(
                new BigDecimal("3.5"), 2, FINGERPRINT);

        String text = candidate.toString();

        assertTrue(text.contains("durationSeconds=3.5"));
        assertTrue(text.contains("segmentCount=2"));
        assertTrue(text.contains(FINGERPRINT));
        assertFalse(text.contains("example.invalid"));
        assertFalse(text.contains("confirmed"));
        assertFalse(text.contains("playable"));
        assertEquals(2, candidate.segmentCount());
        assertEquals(new BigDecimal("3.5"), candidate.durationSeconds());
        assertEquals(FINGERPRINT, candidate.manifestSha256());
    }
}
