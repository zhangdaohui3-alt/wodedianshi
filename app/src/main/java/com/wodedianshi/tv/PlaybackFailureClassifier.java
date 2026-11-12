package com.wodedianshi.tv;

/**
 * Maps Media3 PlaybackException codes to recovery domains.
 *
 * Kept separate from UI and engine creation so the mapping can be tested
 * without an Android device. Numeric values follow Media3 1.11.x
 * PlaybackException error-code definitions.
 */
public final class PlaybackFailureClassifier {
    private PlaybackFailureClassifier() {}

    public static PlaybackEnginePolicy.FailureKind classify(int code) {
        switch (code) {
            // Sliding live-window recovery is handled specially by MainActivity.
            case 1002:
            case 2000: case 2001: case 2002: case 2004: case 2005: case 2006: case 2008:
                return PlaybackEnginePolicy.FailureKind.TRANSPORT;

            // Local network/security configuration: another URL or engine will not fix it.
            case 2007:
                return PlaybackEnginePolicy.FailureKind.CONFIGURATION;

            // Container/manifest parse and demux failures.
            case 3001: case 3002:
                return PlaybackEnginePolicy.FailureKind.DEMUX;
            case 3003: case 3004:
                return PlaybackEnginePolicy.FailureKind.UNSUPPORTED_CONTAINER_OR_CODEC;

            // Decoder selection/creation versus failure after decoding has started.
            case 4001: case 4002:
                return PlaybackEnginePolicy.FailureKind.DECODER_INITIALIZATION;
            case 4003: case 4006:
                return PlaybackEnginePolicy.FailureKind.DECODER_RUNTIME;
            case 4004: case 4005:
                return PlaybackEnginePolicy.FailureKind.UNSUPPORTED_CONTAINER_OR_CODEC;

            // Audio output pipeline.
            case 5001: case 5002: case 5003: case 5004:
                return PlaybackEnginePolicy.FailureKind.AUDIO_OUTPUT;

            // Video frame processing/output pipeline.
            case 7000: case 7001:
                return PlaybackEnginePolicy.FailureKind.VIDEO_RENDER;

            // Includes DRM, app-state and unknown/custom failures. Never guess.
            default:
                return PlaybackEnginePolicy.FailureKind.UNKNOWN;
        }
    }
}
