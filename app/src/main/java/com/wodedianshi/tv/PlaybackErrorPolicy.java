package com.wodedianshi.tv;

import java.util.Locale;

/** Shared endpoint/compatibility decisions for live and VOD playback. */
public final class PlaybackErrorPolicy {
    private PlaybackErrorPolicy() {}

    enum Disposition {
        MISSING_RESOURCE(true,600000L),
        RATE_LIMITED(true,60000L),
        TRANSIENT_NETWORK(true,30000L),
        ENDPOINT_FAILURE(true,120000L),
        COMPATIBILITY(false,600000L),
        UNKNOWN(false,120000L);

        final boolean skipSameUrlVlcRetry;
        final long cooldownMs;
        Disposition(boolean skipSameUrlVlcRetry,long cooldownMs) {
            this.skipSameUrlVlcRetry=skipSameUrlVlcRetry;
            this.cooldownMs=cooldownMs;
        }
    }

    /** Explicit HTTP failure evidence outranks the Media3 error name.
     * Ambiguous HTTP status (including 401/403), parse and decoder errors
     * retain compatibility fallback unless the error name proves otherwise. */
    static Disposition classify(String errorCodeName,int httpStatus) {
        if(httpStatus==404||httpStatus==410)return Disposition.MISSING_RESOURCE;
        if(httpStatus==429)return Disposition.RATE_LIMITED;
        if(httpStatus>=500&&httpStatus<=599)return Disposition.TRANSIENT_NETWORK;
        if(errorCodeName==null)return Disposition.UNKNOWN;
        switch(errorCodeName.trim().toUpperCase(Locale.ROOT)) {
            case "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED":
            case "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT":
                return Disposition.TRANSIENT_NETWORK;
            case "ERROR_CODE_IO_FILE_NOT_FOUND":
            case "ERROR_CODE_BEHIND_LIVE_WINDOW":
                return Disposition.ENDPOINT_FAILURE;
            case "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED":
            case "ERROR_CODE_DECODING_FAILED":
            case "ERROR_CODE_DECODER_INIT_FAILED":
                return Disposition.COMPATIBILITY;
            default:
                return Disposition.UNKNOWN;
        }
    }

    /** A live stream that already rendered video and advanced audio is not
     * initially codec-incompatible merely because its stream ended. */
    public static boolean shouldTryVlcForEndedLive(boolean renderedVideo, boolean advancedAudio) {
        return !renderedVideo || !advancedAudio;
    }
}
