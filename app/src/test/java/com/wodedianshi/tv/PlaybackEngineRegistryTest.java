package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class PlaybackEngineRegistryTest {
    private PlaybackEnginePolicy.Candidate c(PlaybackEnginePolicy.Engine engine,
                                              PlaybackEnginePolicy.DecoderMode mode) {
        return new PlaybackEnginePolicy.Candidate(engine, mode);
    }

    @Test public void onlyRuntimeReadyCandidatesAreRegisteredInPreferenceOrder() {
        PlaybackEnginePolicy.Candidate mpvHw = c(PlaybackEnginePolicy.Engine.MPV,
                PlaybackEnginePolicy.DecoderMode.HARDWARE);
        PlaybackEnginePolicy.Candidate mpvSw = c(PlaybackEnginePolicy.Engine.MPV,
                PlaybackEnginePolicy.DecoderMode.SOFTWARE);
        PlaybackEnginePolicy.Candidate vlcSw = c(PlaybackEnginePolicy.Engine.LIBVLC,
                PlaybackEnginePolicy.DecoderMode.SOFTWARE);
        PlaybackEnginePolicy.Candidate ijkSw = c(PlaybackEnginePolicy.Engine.IJK,
                PlaybackEnginePolicy.DecoderMode.SOFTWARE);

        List<PlaybackEnginePolicy.Candidate> result = PlaybackEngineRegistry.completeOrder(
                Arrays.asList(mpvHw, mpvSw, vlcSw, ijkSw),
                (engine, mode) -> engine == PlaybackEnginePolicy.Engine.MPV
                        && mode == PlaybackEnginePolicy.DecoderMode.SOFTWARE
                        || engine == PlaybackEnginePolicy.Engine.LIBVLC);

        assertEquals(Arrays.asList(
                c(PlaybackEnginePolicy.Engine.MEDIA3, PlaybackEnginePolicy.DecoderMode.PLATFORM_AUTO),
                mpvSw, vlcSw), result);
    }

    @Test public void media3AndDuplicateEntriesAreNeverRegisteredAsFallbacks() {
        PlaybackEnginePolicy.Candidate media3 = c(PlaybackEnginePolicy.Engine.MEDIA3,
                PlaybackEnginePolicy.DecoderMode.PLATFORM_AUTO);
        PlaybackEnginePolicy.Candidate mpv = c(PlaybackEnginePolicy.Engine.MPV,
                PlaybackEnginePolicy.DecoderMode.SOFTWARE);

        List<PlaybackEnginePolicy.Candidate> result = PlaybackEngineRegistry.verifiedFallbacks(
                Arrays.asList(media3, mpv, mpv), (engine, mode) -> true);
        assertEquals(Collections.singletonList(mpv), result);
    }

    @Test public void outputModeSpecificProbeCanRejectOnlyOneRenderingPath() {
        PlaybackEnginePolicy.Candidate gpu = new PlaybackEnginePolicy.Candidate(
                PlaybackEnginePolicy.Engine.MPV, PlaybackEnginePolicy.DecoderMode.HARDWARE,
                PlaybackEnginePolicy.OutputMode.GPU);
        PlaybackEnginePolicy.Candidate embed = new PlaybackEnginePolicy.Candidate(
                PlaybackEnginePolicy.Engine.MPV, PlaybackEnginePolicy.DecoderMode.HARDWARE,
                PlaybackEnginePolicy.OutputMode.MEDIACODEC_EMBED);
        PlaybackEngineRegistry.AvailabilityProbe probe = new PlaybackEngineRegistry.AvailabilityProbe() {
            @Override public boolean isReady(PlaybackEnginePolicy.Engine engine,
                                             PlaybackEnginePolicy.DecoderMode mode) {
                return true;
            }
            @Override public boolean isReady(PlaybackEnginePolicy.Candidate candidate) {
                return candidate.outputMode == PlaybackEnginePolicy.OutputMode.GPU;
            }
        };
        assertEquals(Collections.singletonList(gpu),
                PlaybackEngineRegistry.verifiedFallbacks(Arrays.asList(gpu, embed), probe));
    }

    @Test public void brokenOptionalProbeDoesNotBlockOtherEngines() {
        PlaybackEnginePolicy.Candidate mpv = c(PlaybackEnginePolicy.Engine.MPV,
                PlaybackEnginePolicy.DecoderMode.SOFTWARE);
        PlaybackEnginePolicy.Candidate vlc = c(PlaybackEnginePolicy.Engine.LIBVLC,
                PlaybackEnginePolicy.DecoderMode.SOFTWARE);

        List<PlaybackEnginePolicy.Candidate> result = PlaybackEngineRegistry.verifiedFallbacks(
                Arrays.asList(mpv, vlc), (engine, mode) -> {
                    if (engine == PlaybackEnginePolicy.Engine.MPV) {
                        throw new IllegalStateException("native load failed");
                    }
                    return true;
                });
        assertEquals(Collections.singletonList(vlc), result);
    }

    @Test public void emptyOrMissingProbeLeavesOnlyPrimaryEngine() {
        assertTrue(PlaybackEngineRegistry.verifiedFallbacks(
                Arrays.asList(c(PlaybackEnginePolicy.Engine.MPV,
                        PlaybackEnginePolicy.DecoderMode.SOFTWARE)), null).isEmpty());
        assertEquals(1, PlaybackEngineRegistry.completeOrder(null, (engine, mode) -> true).size());
    }

    @Test public void returnedRegistryIsImmutable() {
        List<PlaybackEnginePolicy.Candidate> result = PlaybackEngineRegistry.verifiedFallbacks(
                Arrays.asList(c(PlaybackEnginePolicy.Engine.MPV,
                        PlaybackEnginePolicy.DecoderMode.SOFTWARE)), (engine, mode) -> true);
        try {
            result.clear();
            fail("Registry snapshots must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
