package com.wodedianshi.tv;

/** Chooses a bounded buffering timeout for first startup, rebuffering and backup attempts. */
public final class BufferingFailoverPolicy {
    private static final int FIRST_STARTUP_MAX_SECONDS = 15;
    private static final int BACKUP_MAX_SECONDS = 6;

    private BufferingFailoverPolicy() {}

    public static int timeoutSeconds(boolean sourceWasReady, int automaticSwitchCount,
                                      int configuredSeconds) {
        if (configuredSeconds <= 0) return 0;
        if (sourceWasReady) return configuredSeconds;
        if (automaticSwitchCount > 0) return Math.min(configuredSeconds, BACKUP_MAX_SECONDS);
        return Math.min(configuredSeconds, FIRST_STARTUP_MAX_SECONDS);
    }
}
