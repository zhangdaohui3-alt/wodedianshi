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
}
