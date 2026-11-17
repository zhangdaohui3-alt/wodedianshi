package com.wodedianshi.tv;

/** Startup scheduling policy for the first automatic live-source discovery. */
public final class SourceUpdateSchedulePolicy {
    private SourceUpdateSchedulePolicy() {}

    /**
     * Run one immediate discovery only when automatic updates are enabled and
     * this installation has no previous source-update record. Existing users
     * keep their saved catalogue and continue using the periodic refresh.
     */
    public static boolean shouldRunInitialDiscovery(boolean automaticUpdatesEnabled,
                                                    String lastUpdateRecord) {
        if (!automaticUpdatesEnabled) return false;
        return lastUpdateRecord == null
                || lastUpdateRecord.trim().isEmpty()
                || "尚未更新".equals(lastUpdateRecord.trim());
    }
}
