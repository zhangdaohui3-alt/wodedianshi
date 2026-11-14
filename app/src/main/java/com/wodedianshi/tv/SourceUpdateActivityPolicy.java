package com.wodedianshi.tv;

/** Lease policy preventing background source maintenance from committing while the TV UI is active. */
public final class SourceUpdateActivityPolicy {
    public static final long LEASE_MS = 90_000L;

    private SourceUpdateActivityPolicy() {}

    public static boolean isLeaseFresh(boolean active, long heartbeatAtMs, long nowMs) {
        if (!active || heartbeatAtMs <= 0L || nowMs < heartbeatAtMs) return false;
        return nowMs - heartbeatAtMs <= LEASE_MS;
    }
}
