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
    public static final long ROUTINE_ELIGIBILITY_CHECK_DAYS = 1L;
    private static final long ROUTINE_REFRESH_MS = ROUTINE_REFRESH_DAYS * 24L * 60L * 60L * 1000L;

    /** A skipped busy check must not advance the weekly maintenance deadline. */
    public static boolean isRoutineMaintenanceDue(long fullRebuildAt, long lastRoutineAt, long now) {
        if (fullRebuildAt <= 0L || now <= 0L) return false;
        long last = Math.max(fullRebuildAt, lastRoutineAt);
        return now < last || now - last >= ROUTINE_REFRESH_MS;
    }

    // Three 4-entry feed pages fit the 12 discovery slots even when saved
    // routes consume half of the 24-probe budget. No wrap inside one slice.
    public static final int ROUTINE_FEEDS_PER_SLICE = 3;

    public static int routineSliceEnd(int start, int feedCount) {
        if (feedCount <= 0) return 0;
        int safe = InitialFeedRotationPolicy.normalizeStart(start, feedCount);
        return Math.min(feedCount, safe + ROUTINE_FEEDS_PER_SLICE);
    }

    public static int routineNextFeed(int start, int feedCount, boolean batchComplete) {
        if (feedCount <= 0) return 0;
        int safe = InitialFeedRotationPolicy.normalizeStart(start, feedCount);
        return batchComplete ? routineSliceEnd(safe, feedCount) % feedCount : safe;
    }

    /** Manual refresh advances only at EOF, never just because a page was processed. */
    public static boolean manualFeedFinished(int cursor, int parsed, int indexedEntries,
            int pageSize) {
        if (cursor < 0 || parsed < 0 || pageSize <= 0) return false;
        return indexedEntries >= 0
                ? (long)cursor + parsed >= indexedEntries
                : parsed < pageSize;
    }

    /** Only a completed first rebuild enables low-priority routine maintenance. */
    public static boolean shouldScheduleRoutineMaintenance(boolean automaticEnabled,
            long fullRebuildCompletedAtMillis) {
        return automaticEnabled && fullRebuildCompletedAtMillis > 0L;
    }

    /**
     * A routine busy check is skipped until the next daily eligibility check. Never build a 10-minute retry loop while the TV is busy.
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
