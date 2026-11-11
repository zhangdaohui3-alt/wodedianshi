package com.wodedianshi.tv;

/** Pure decisions for remote parental-lock rules; kept separate for deterministic tests. */
public final class ParentRemoteLockPolicy {
    private ParentRemoteLockPolicy() {}

    public static boolean isInsideWindow(int minute, int startMinute, int endMinute) {
        if (minute < 0 || minute >= 1440 || startMinute < 0 || startMinute >= 1440
                || endMinute < 0 || endMinute >= 1440) return false;
        if (startMinute == endMinute) return true;
        return startMinute < endMinute
                ? minute >= startMinute && minute < endMinute
                : minute >= startMinute || minute < endMinute;
    }

    public static boolean shouldLockOffline(boolean strict, boolean hasValidCache,
                                             boolean remoteEnabled) {
        if (!remoteEnabled) return false;
        if (!hasValidCache) return true;
        return strict;
    }

    /** Never substitute the device wall clock for a missing network time source. */
    public static long trustedServerTime(long payloadTimeMs, long httpDateMs) {
        if (payloadTimeMs > 0L) return payloadTimeMs;
        return Math.max(0L, httpDateMs);
    }

    public static boolean clockJumped(long wallDeltaMs, long elapsedDeltaMs,
                                      long toleranceMs) {
        return toleranceMs >= 0L && Math.abs(wallDeltaMs - elapsedDeltaMs) > toleranceMs;
    }

    public static long safeUsage(long storedUsageMs, long deltaMs) {
        if (storedUsageMs < 0L) storedUsageMs = 0L;
        if (deltaMs <= 0L) return storedUsageMs;
        if (Long.MAX_VALUE - storedUsageMs < deltaMs) return Long.MAX_VALUE;
        return storedUsageMs + deltaMs;
    }
}
