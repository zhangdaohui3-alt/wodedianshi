package com.wodedianshi.tv;

/** Freshness policy for completed VOD catalogue aggregates. */
public final class VodCachePolicy {
    private VodCachePolicy() {}

    public static boolean isFresh(long savedAtMs, long nowMs, long maxAgeMs) {
        return savedAtMs > 0L && nowMs >= savedAtMs
                && maxAgeMs > 0L && nowMs - savedAtMs < maxAgeMs;
    }
}
