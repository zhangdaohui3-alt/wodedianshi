package com.wodedianshi.tv;

import java.util.Locale;

/** Decides whether a Media3 error merits retrying the same URL with LibVLC. */
public final class PlaybackErrorPolicy {
    private PlaybackErrorPolicy() {}

    /**
     * Connection establishment failures and timeouts are endpoint/network
     * failures, not decoder compatibility signals. Skip a same-URL engine retry
     * so automatic backup selection is not delayed by a second connection timeout.
     * HTTP status, manifest/container parsing and decoder errors still retain the
     * LibVLC fallback because a different network stack or demuxer may recover.
     */
    public static boolean shouldSkipSameUrlVlcRetry(String errorCodeName) {
        if (errorCodeName == null) return false;
        String name = errorCodeName.trim().toUpperCase(Locale.ROOT);
        return "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED".equals(name)
                || "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT".equals(name);
    }

    /**
     * Cool down endpoints according to the failure evidence. Transient network
     * and server failures should be retried relatively soon; deterministic
     * missing resources and local decode/parse incompatibilities should not
     * dominate the next channel scan.
     */
    public static long cooldownMs(String errorCodeName, int httpStatus) {
        if (httpStatus == 404 || httpStatus == 410) return 10L * 60L * 1000L;
        if (httpStatus == 429) return 60L * 1000L;
        if (httpStatus >= 500 && httpStatus <= 599) return 30L * 1000L;
        if (errorCodeName == null) return 120L * 1000L;
        String name = errorCodeName.trim().toUpperCase(Locale.ROOT);
        if ("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED".equals(name)
                || "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT".equals(name)) {
            return 30L * 1000L;
        }
        if ("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED".equals(name)
                || "ERROR_CODE_DECODING_FAILED".equals(name)
                || "ERROR_CODE_DECODER_INIT_FAILED".equals(name)) {
            return 10L * 60L * 1000L;
        }
        return 120L * 1000L;
    }
}
