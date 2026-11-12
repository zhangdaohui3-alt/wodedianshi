package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class PlaybackEnginePolicyTest {
    private PlaybackEnginePolicy.Candidate c(PlaybackEnginePolicy.Engine e,
                                               PlaybackEnginePolicy.DecoderMode d) {
        return new PlaybackEnginePolicy.Candidate(e, d);
    }

    private final PlaybackEnginePolicy.Candidate media3 =
            c(PlaybackEnginePolicy.Engine.MEDIA3, PlaybackEnginePolicy.DecoderMode.PLATFORM_AUTO);
    private final PlaybackEnginePolicy.Candidate mpvSoftware =
            c(PlaybackEnginePolicy.Engine.MPV, PlaybackEnginePolicy.DecoderMode.SOFTWARE);
    private final PlaybackEnginePolicy.Candidate mpvHardware =
            c(PlaybackEnginePolicy.Engine.MPV, PlaybackEnginePolicy.DecoderMode.HARDWARE);
    private final PlaybackEnginePolicy.Candidate ijkSoftware =
            c(PlaybackEnginePolicy.Engine.IJK, PlaybackEnginePolicy.DecoderMode.SOFTWARE);
    private final PlaybackEnginePolicy.Candidate vlcSoftware =
            c(PlaybackEnginePolicy.Engine.LIBVLC, PlaybackEnginePolicy.DecoderMode.SOFTWARE);

    @Test public void media3IsPrimaryAndVerifiedRegistryControlsFallbackOrder() {
        assertEquals(Collections.singletonList(media3),
                PlaybackEnginePolicy.orderedCandidates(Collections.emptyList()));
        assertEquals(Arrays.asList(media3, mpvSoftware, ijkSoftware, vlcSoftware),
                PlaybackEnginePolicy.orderedCandidates(Arrays.asList(mpvSoftware, ijkSoftware, vlcSoftware)));
    }

    @Test public void duplicateAndUnverifiedMedia3EntriesAreIgnored() {
        assertEquals(Arrays.asList(media3, mpvSoftware),
                PlaybackEnginePolicy.orderedCandidates(Arrays.asList(media3, mpvSoftware, mpvSoftware)));
    }

    @Test public void transportFailureChangesSourceNotEngineOrDecoder() {
        List<PlaybackEnginePolicy.Candidate> all =
                PlaybackEnginePolicy.orderedCandidates(Arrays.asList(mpvSoftware, ijkSoftware));
        PlaybackEnginePolicy.Decision d = PlaybackEnginePolicy.decide(
                PlaybackEnginePolicy.FailureKind.TRANSPORT, all, media3);
        assertTrue(d.changeSource);
        assertFalse(d.switchEngine);
        assertFalse(d.changeDecoderMode);
        assertNull(d.nextCandidate);
    }

    @Test public void decoderFailureMovesFromMedia3ToMpvSoftware() {
        List<PlaybackEnginePolicy.Candidate> all =
                PlaybackEnginePolicy.orderedCandidates(Arrays.asList(mpvSoftware, ijkSoftware));
        PlaybackEnginePolicy.Decision d = PlaybackEnginePolicy.decide(
                PlaybackEnginePolicy.FailureKind.DECODER_RUNTIME, all, media3);
        assertFalse(d.changeSource);
        assertTrue(d.switchEngine);
        assertTrue(d.changeDecoderMode);
        assertEquals(mpvSoftware, d.nextCandidate);
    }

    @Test public void hardwareDecoderFailureCanFallBackToSoftwareWithinSameEngine() {
        List<PlaybackEnginePolicy.Candidate> all =
                PlaybackEnginePolicy.orderedCandidates(Arrays.asList(mpvHardware, mpvSoftware, ijkSoftware));
        PlaybackEnginePolicy.Decision d = PlaybackEnginePolicy.decide(
                PlaybackEnginePolicy.FailureKind.DECODER_RUNTIME, all, mpvHardware);
        assertFalse(d.changeSource);
        assertFalse(d.switchEngine);
        assertTrue(d.changeDecoderMode);
        assertEquals(mpvSoftware, d.nextCandidate);
    }

    @Test public void demuxFailureMovesFromIijkToLibVlcCandidate() {
        List<PlaybackEnginePolicy.Candidate> all =
                PlaybackEnginePolicy.orderedCandidates(Arrays.asList(mpvSoftware, ijkSoftware, vlcSoftware));
        PlaybackEnginePolicy.Decision d = PlaybackEnginePolicy.decide(
                PlaybackEnginePolicy.FailureKind.DEMUX, all, ijkSoftware);
        assertTrue(d.switchEngine);
        assertFalse(d.changeDecoderMode);
        assertEquals(vlcSoftware, d.nextCandidate);
    }

    @Test public void noCandidateIsRepeatedAfterLastFallback() {
        List<PlaybackEnginePolicy.Candidate> all =
                PlaybackEnginePolicy.orderedCandidates(Arrays.asList(mpvSoftware));
        PlaybackEnginePolicy.Decision d = PlaybackEnginePolicy.decide(
                PlaybackEnginePolicy.FailureKind.DEMUX, all, mpvSoftware);
        assertFalse(d.switchEngine);
        assertFalse(d.changeDecoderMode);
        assertNull(d.nextCandidate);
    }

    @Test public void configurationAndUnknownFailuresDoNotTriggerBlindSwitch() {
        List<PlaybackEnginePolicy.Candidate> all =
                PlaybackEnginePolicy.orderedCandidates(Arrays.asList(mpvSoftware, ijkSoftware));
        PlaybackEnginePolicy.Decision configuration = PlaybackEnginePolicy.decide(
                PlaybackEnginePolicy.FailureKind.CONFIGURATION, all, media3);
        PlaybackEnginePolicy.Decision unknown = PlaybackEnginePolicy.decide(
                PlaybackEnginePolicy.FailureKind.UNKNOWN, all, media3);
        assertFalse(configuration.changeSource);
        assertFalse(configuration.switchEngine);
        assertFalse(unknown.changeSource);
        assertFalse(unknown.switchEngine);
    }

    @Test public void unavailableCandidateCannotBeSelected() {
        List<PlaybackEnginePolicy.Candidate> all =
                PlaybackEnginePolicy.orderedCandidates(Arrays.asList(mpvSoftware));
        PlaybackEnginePolicy.Decision d = PlaybackEnginePolicy.decide(
                PlaybackEnginePolicy.FailureKind.DECODER_INITIALIZATION, all, ijkSoftware);
        assertFalse(d.switchEngine);
        assertNull(d.nextCandidate);
    }
}
