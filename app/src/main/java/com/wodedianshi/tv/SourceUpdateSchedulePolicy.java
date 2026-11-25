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
    public static final long ROUTINE_REFRESH_DAYS = 30L;
    public static final long ROUTINE_ELIGIBILITY_CHECK_DAYS = 1L;
    private static final long ROUTINE_REFRESH_MS = ROUTINE_REFRESH_DAYS * 24L * 60L * 60L * 1000L;

    /** A skipped busy check must not advance the 30-day maintenance deadline. */
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

    /** Reuse only a completed probe from this process, never a historical score.
     * Failures expire quickly so temporary outages are not held for long. */
    public static boolean canReuseInitialProbe(boolean valid, long measuredAt, long now) {
        return measuredAt >= 0L && now >= measuredAt
                && now - measuredAt <= (valid ? 300_000L : 15_000L);
    }

    /** Continue immediately only after a successful committed discovery advance.
     * Keep the existing delay for failed/empty-progress slices to avoid retry churn. */
    static long manualContinuationDelaySeconds(boolean feedRead,int discoveryScanned,boolean pageComplete) {
        return feedRead&&(discoveryScanned>0||pageComplete)?0L:10L;
    }

    /** New-feed scanning and the bounded saved-route pass have separate checkpoints. */
    static int manualSavedWindow(int poolSize,int covered,boolean savedOnly) {
        return Math.min(savedOnly?DiscoveryBatchPolicy.MANUAL_PAGE_SIZE:12,
                Math.max(0,poolSize-Math.max(0,covered)));
    }
    static boolean manualRoundComplete(int nextFeed,int feedCount,int covered,int poolSize) {
        return feedCount>=0&&nextFeed>=feedCount&&poolSize>=0&&covered>=poolSize;
    }

    /** Manual refresh advances only at EOF, never just because a page was processed. */
    public static boolean manualFeedFinished(int cursor, int parsed, int indexedEntries,
            int pageSize) {
        if (cursor < 0 || parsed < 0 || pageSize <= 0) return false;
        return indexedEntries >= 0
                ? (long)cursor + parsed >= indexedEntries
                : parsed < pageSize;
    }

    /** A user-requested manual refresh checks saved routes while browsing too.
     * Playback admission is enforced separately, before pool construction. */
    static boolean shouldBuildSavedProbePool(boolean initial,boolean manual,boolean foreground) {
        return !initial&&(manual||!foreground);
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

    /** A retried predecessor may already have committed its successor in WorkManager.
     * Existing unfinished work owns the next slice; do not append another leaf. */
    public static boolean hasOtherUnfinishedWork(java.util.UUID currentId,
            java.util.Collection<java.util.UUID> unfinishedIds) {
        if (unfinishedIds==null) return false;
        for (java.util.UUID id:unfinishedIds)
            if (id!=null&&!id.equals(currentId)) return true;
        return false;
    }

    /** Normal viewing deferral is not a failed network attempt. */
    public static long initialViewingDelaySeconds(boolean stableButWaitingForIdle) {
        return stableButWaitingForIdle ? 60L : 30L;
    }

    /** Automatic catalogue work must not compete with foreground TV playback or input. */
    public static boolean shouldDeferForegroundAutoUpdate(boolean appForeground, boolean manual) {
        return appForeground && !manual;
    }

    /**
     * First-run setup must advance even when seed or previously saved channels exist.
     * Playback activity is checked separately by the worker before this gate.
     */
    public static boolean shouldDeferForegroundDiscovery(boolean appForeground,
            boolean manual, boolean initialDiscoveryChain, boolean hasPublishedChannels) {
        // Seed channels are not evidence of a completed initial rebuild.
        // Keep routine automatic maintenance idle-only, but allow initialization
        // or explicit manual work while browsing, regardless of catalogue size.
        return appForeground && !manual && !initialDiscoveryChain;
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
