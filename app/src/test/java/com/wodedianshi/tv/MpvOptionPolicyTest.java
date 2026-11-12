package com.wodedianshi.tv;

import org.junit.Test;

import static org.junit.Assert.*;

public class MpvOptionPolicyTest {
    private PlaybackEnginePolicy.Candidate c(PlaybackEnginePolicy.DecoderMode decoder,
                                              PlaybackEnginePolicy.OutputMode output) {
        return new PlaybackEnginePolicy.Candidate(PlaybackEnginePolicy.Engine.MPV, decoder, output);
    }

    @Test public void gpuOutputUsesAndroidContextAndDirectMediaCodec() {
        MpvOptionPolicy.Options options = MpvOptionPolicy.forCandidate(c(
                PlaybackEnginePolicy.DecoderMode.MEDIACODEC_DIRECT,
                PlaybackEnginePolicy.OutputMode.GPU));
        assertEquals("gpu", options.videoOutput);
        assertEquals("android", options.gpuContext);
        assertEquals("mediacodec", options.hardwareDecoder);
        assertTrue(options.supportsSubtitles);
        assertTrue(options.supportsOsd);
    }

    @Test public void gpuOutputCanSelectMediaCodecCopy() {
        MpvOptionPolicy.Options options = MpvOptionPolicy.forCandidate(c(
                PlaybackEnginePolicy.DecoderMode.MEDIACODEC_COPY,
                PlaybackEnginePolicy.OutputMode.GPU));
        assertEquals("mediacodec-copy", options.hardwareDecoder);
        assertEquals("gpu", options.videoOutput);
    }

    @Test public void softwareFallbackDisablesHardwareDecode() {
        MpvOptionPolicy.Options options = MpvOptionPolicy.forCandidate(c(
                PlaybackEnginePolicy.DecoderMode.SOFTWARE,
                PlaybackEnginePolicy.OutputMode.GPU));
        assertEquals("no", options.hardwareDecoder);
        assertEquals("android", options.gpuContext);
    }

    @Test public void embeddedMediaCodecOutputDisablesGpuOnlyFeatures() {
        MpvOptionPolicy.Options options = MpvOptionPolicy.forCandidate(c(
                PlaybackEnginePolicy.DecoderMode.MEDIACODEC_DIRECT,
                PlaybackEnginePolicy.OutputMode.MEDIACODEC_EMBED));
        assertEquals("mediacodec_embed", options.videoOutput);
        assertNull(options.gpuContext);
        assertEquals("mediacodec", options.hardwareDecoder);
        assertFalse(options.supportsSubtitles);
        assertFalse(options.supportsOsd);
    }

    @Test public void embeddedOutputRejectsSoftwareDecoder() {
        try {
            MpvOptionPolicy.forCandidate(c(PlaybackEnginePolicy.DecoderMode.SOFTWARE,
                    PlaybackEnginePolicy.OutputMode.MEDIACODEC_EMBED));
            fail("mediacodec_embed cannot use software decoding");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test public void nonMpvCandidateIsRejected() {
        try {
            MpvOptionPolicy.forCandidate(new PlaybackEnginePolicy.Candidate(
                    PlaybackEnginePolicy.Engine.MEDIA3,
                    PlaybackEnginePolicy.DecoderMode.PLATFORM_AUTO));
            fail("MPV options must not be applied to Media3");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
