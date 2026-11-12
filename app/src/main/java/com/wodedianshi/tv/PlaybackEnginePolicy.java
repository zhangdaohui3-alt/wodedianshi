package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pure decision policy for controlled multi-engine, decoder, and output fallback. */
public final class PlaybackEnginePolicy {
    private PlaybackEnginePolicy() {}

    public enum Engine { MEDIA3, MPV, IJK, LIBVLC }

    /** Decoder strategy is independent of the engine and output surface path. */
    public enum DecoderMode { PLATFORM_AUTO, HARDWARE, MEDIACODEC_DIRECT, MEDIACODEC_COPY, SOFTWARE }

    /** Video output path is explicit because Android hardware decode paths differ materially. */
    public enum OutputMode { DEFAULT, GPU, MEDIACODEC_EMBED }

    public enum FailureKind {
        TRANSPORT, CONFIGURATION, UNSUPPORTED_CONTAINER_OR_CODEC, DEMUX,
        DECODER_INITIALIZATION, DECODER_RUNTIME, VIDEO_RENDER, AUDIO_OUTPUT, UNKNOWN
    }

    /** One candidate is a concrete engine + decoder + output combination. */
    public static final class Candidate {
        public final Engine engine;
        public final DecoderMode decoderMode;
        public final OutputMode outputMode;

        public Candidate(Engine engine, DecoderMode decoderMode) {
            this(engine, decoderMode, OutputMode.DEFAULT);
        }

        public Candidate(Engine engine, DecoderMode decoderMode, OutputMode outputMode) {
            this.engine = engine;
            this.decoderMode = decoderMode;
            this.outputMode = outputMode;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Candidate)) return false;
            Candidate that = (Candidate) other;
            return engine == that.engine
                    && decoderMode == that.decoderMode
                    && outputMode == that.outputMode;
        }

        @Override public int hashCode() {
            return Objects.hash(engine, decoderMode, outputMode);
        }

        @Override public String toString() {
            return engine + "/" + decoderMode + "/" + outputMode;
        }
    }

    public static final class Decision {
        public final boolean changeSource;
        public final boolean switchEngine;
        public final boolean changeDecoderMode;
        public final boolean changeOutputMode;
        public final Candidate nextCandidate;
        public final String reason;

        private Decision(boolean source, boolean engine, boolean decoder, boolean output,
                         Candidate next, String why) {
            changeSource = source;
            switchEngine = engine;
            changeDecoderMode = decoder;
            changeOutputMode = output;
            nextCandidate = next;
            reason = why;
        }
    }

    /**
     * Media3 is the primary candidate. Fallback candidates must come from a
     * runtime registry that has verified the ABI-specific libraries and adapter.
     */
    public static List<Candidate> orderedCandidates(List<Candidate> verifiedFallbacks) {
        List<Candidate> result = new ArrayList<>();
        result.add(new Candidate(Engine.MEDIA3, DecoderMode.PLATFORM_AUTO));
        if (verifiedFallbacks != null) {
            for (Candidate candidate : verifiedFallbacks) {
                if (candidate == null || candidate.engine == null || candidate.decoderMode == null
                        || candidate.outputMode == null) continue;
                if (candidate.engine == Engine.MEDIA3) continue;
                if (!result.contains(candidate)) result.add(candidate);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Transport failures rotate a distinct URL. Decode/demux/render failures
     * advance to the next verified engine+decoder+output candidate.
     * Configuration and unknown failures never trigger blind switching.
     */
    public static Decision decide(FailureKind failure, List<Candidate> available,
                                  Candidate current) {
        if (failure == null || available == null || available.isEmpty()
                || current == null || current.engine == null || current.decoderMode == null
                || current.outputMode == null) {
            return new Decision(false, false, false, false, null, "invalid playback state");
        }
        if (failure == FailureKind.TRANSPORT) {
            return new Decision(true, false, false, false, null,
                    "transport failure: try a distinct source");
        }
        if (!requiresPlaybackFallback(failure)) {
            return new Decision(false, false, false, false, null,
                    "configuration or unclassified failure: do not switch blindly");
        }
        int index = available.indexOf(current);
        if (index < 0) {
            return new Decision(false, false, false, false, null,
                    "current engine/decoder/output candidate is not available");
        }
        if (index + 1 >= available.size()) {
            return new Decision(false, false, false, false, null,
                    "no compatible engine/decoder/output candidate remains");
        }
        Candidate next = available.get(index + 1);
        return new Decision(false, current.engine != next.engine,
                current.decoderMode != next.decoderMode,
                current.outputMode != next.outputMode, next,
                "decoder/demux/render failure: try next verified candidate");
    }

    private static boolean requiresPlaybackFallback(FailureKind failure) {
        switch (failure) {
            case UNSUPPORTED_CONTAINER_OR_CODEC:
            case DEMUX:
            case DECODER_INITIALIZATION:
            case DECODER_RUNTIME:
            case VIDEO_RENDER:
            case AUDIO_OUTPUT:
                return true;
            default:
                return false;
        }
    }
}
