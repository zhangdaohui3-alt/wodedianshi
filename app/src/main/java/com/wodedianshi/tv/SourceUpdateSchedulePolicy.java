package com.wodedianshi.tv;

/** Controls the one-time independent full-catalogue rebuild lifecycle. */
public final class SourceUpdateSchedulePolicy {
    private SourceUpdateSchedulePolicy() {}

    /**
     * Schedule the independent full rebuild only when automatic updates are
     * enabled and no full rebuild has been published. Older incremental-update
     * timestamps must not suppress the first complete rebuild.
     */
    public static boolean shouldRunInitialDiscovery(boolean automaticUpdatesEnabled,
                                                    long fullRebuildCompletedAtMillis) {
        return automaticUpdatesEnabled && fullRebuildCompletedAtMillis <= 0L;
    }

    /** Automatic catalogue work must not compete with foreground TV playback or input. */
    public static boolean shouldDeferForegroundAutoUpdate(boolean appForeground, boolean manual) {
        return appForeground && !manual;
    }

    /** A first-run rebuild is complete only after every candidate and feed was covered. */
    public static boolean isInitialDiscoveryComplete(int candidateCount, int scannedCount,
                                                      int failedFeedCount) {
        return candidateCount > 0 && scannedCount >= candidateCount && failedFeedCount == 0;
    }

    /** At least one newly discovered source must pass the media preflight. */
    public static boolean hasValidatedDiscoverySources(int validatedSourceCount) {
        return validatedSourceCount > 0;
    }
}
