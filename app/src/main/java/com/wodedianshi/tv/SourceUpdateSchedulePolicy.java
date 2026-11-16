package com.wodedianshi.tv;

/** Prevents repeating the one-time China-first catalogue discovery on every app launch. */
public final class SourceUpdateSchedulePolicy {
    private SourceUpdateSchedulePolicy() {}

    /**
     * Schedule initial discovery only when automatic updates are enabled and no
     * configured feed has ever completed a successful read. Failed attempts do
     * not set the success timestamp, so they remain eligible for retry.
     */
    public static boolean shouldRunInitialDiscovery(boolean automaticUpdatesEnabled,
                                                    long lastSuccessfulUpdateMillis) {
        return automaticUpdatesEnabled && lastSuccessfulUpdateMillis <= 0L;
    }

    /** At least one newly discovered source must pass the media preflight. */
    public static boolean hasValidatedDiscoverySources(int validatedSourceCount) {
        return validatedSourceCount > 0;
    }
}
