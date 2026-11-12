package com.wodedianshi.tv;

/** Maps a verified MPV candidate to explicit Android video-output and decoder options. */
public final class MpvOptionPolicy {
    private MpvOptionPolicy() {}

    public static final class Options {
        public final String videoOutput;
        public final String gpuContext;
        public final String hardwareDecoder;
        public final boolean supportsSubtitles;
        public final boolean supportsOsd;

        private Options(String vo, String gpu, String hwdec, boolean subtitles, boolean osd) {
            videoOutput = vo;
            gpuContext = gpu;
            hardwareDecoder = hwdec;
            supportsSubtitles = subtitles;
            supportsOsd = osd;
        }
    }

    public static Options forCandidate(PlaybackEnginePolicy.Candidate candidate) {
        if (candidate == null || candidate.engine != PlaybackEnginePolicy.Engine.MPV
                || candidate.decoderMode == null || candidate.outputMode == null) {
            throw new IllegalArgumentException("A complete MPV playback candidate is required");
        }

        final String hwdec;
        switch (candidate.decoderMode) {
            case PLATFORM_AUTO:
                hwdec = "auto-safe";
                break;
            case HARDWARE:
                hwdec = "mediacodec,mediacodec-copy";
                break;
            case MEDIACODEC_DIRECT:
                hwdec = "mediacodec";
                break;
            case MEDIACODEC_COPY:
                hwdec = "mediacodec-copy";
                break;
            case SOFTWARE:
                hwdec = "no";
                break;
            default:
                throw new IllegalArgumentException("Unsupported MPV decoder mode: " + candidate.decoderMode);
        }

        if (candidate.outputMode == PlaybackEnginePolicy.OutputMode.MEDIACODEC_EMBED) {
            if (candidate.decoderMode != PlaybackEnginePolicy.DecoderMode.MEDIACODEC_DIRECT
                    && candidate.decoderMode != PlaybackEnginePolicy.DecoderMode.HARDWARE) {
                throw new IllegalArgumentException(
                        "mediacodec_embed requires the direct MediaCodec decoder");
            }
            return new Options("mediacodec_embed", null, "mediacodec", false, false);
        }

        if (candidate.outputMode == PlaybackEnginePolicy.OutputMode.DEFAULT
                || candidate.outputMode == PlaybackEnginePolicy.OutputMode.GPU) {
            return new Options("gpu", "android", hwdec, true, true);
        }

        throw new IllegalArgumentException("Unsupported MPV output mode: " + candidate.outputMode);
    }
}
