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

    // A completed Room snapshot is the default directory. Periodic discovery is
    // deliberately infrequent; WorkManager's timing is inexact by design.
    public static final long ROUTINE_REFRESH_DAYS = 7L;

    /** Only a completed first rebuild enables low-priority routine maintenance. */
    public static boolean shouldScheduleRoutineMaintenance(boolean automaticEnabled,
            long fullRebuildCompletedAtMillis) {
        return automaticEnabled && fullRebuildCompletedAtMillis > 0L;
    }

    /**
     * A routine automatic pass is optional and can be skipped until the next
     * weekly interval. Never build a 10-minute retry loop while the TV is busy.
     * First-run and explicitly requested work should retain their queued intent.
     */
    public static boolean shouldRetryWhenBusy(boolean manual, boolean initialDiscoveryChain) {
        return manual || initialDiscoveryChain;
    }

    /** Automatic catalogue work must not compete with foreground TV playback or input. */
    public static boolean shouldDeferForegroundAutoUpdate(boolean appForeground, boolean manual) {
        return appForeground && !manual;
    }

    /**
     * First-install exception: an empty Room catalogue cannot become usable if
     * every discovery run waits for the TV app to leave the foreground.
     * Playback activity is checked by the worker separately before this gate.
     */
    public static boolean shouldDeferForegroundDiscovery(boolean appForeground,
            boolean manual, boolean initialDiscoveryChain, boolean hasPublishedChannels) {
        // Initial full rebuild may proceed only for an EMPTY published catalogue:
        // a previously saved catalogue must take priority even for manual initial
        // work. A true manual routine refresh can run in foreground if playback
        // itself is inactive.
        return appForeground && (!manual || initialDiscoveryChain)
                && !(initialDiscoveryChain && !hasPublishedChannels);
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
