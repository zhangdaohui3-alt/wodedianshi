package com.wodedianshi.tv;

/** Chooses a bounded buffering timeout for first startup, rebuffering and backup attempts. */
public final class BufferingFailoverPolicy {
    private static final int FIRST_STARTUP_MAX_SECONDS = 15;
    private static final int BACKUP_MAX_SECONDS = 6;
    private static final int NEXT_CHANNEL_MAX_SECONDS = 5;

    private BufferingFailoverPolicy() {}

    public static int timeoutSeconds(boolean sourceWasReady, int automaticSwitchCount,
                                      int configuredSeconds) {
        return timeoutSeconds(sourceWasReady, automaticSwitchCount, 0, configuredSeconds);
    }

    public static int timeoutSeconds(boolean sourceWasReady, int automaticSwitchCount,
                                      int automaticChannelSwitchCount, int configuredSeconds) {
        if (configuredSeconds <= 0) return 0;
        if (sourceWasReady) return configuredSeconds;
        if (automaticChannelSwitchCount > 0) return Math.min(configuredSeconds, NEXT_CHANNEL_MAX_SECONDS);
        if (automaticSwitchCount > 0) return Math.min(configuredSeconds, BACKUP_MAX_SECONDS);
        return Math.min(configuredSeconds, FIRST_STARTUP_MAX_SECONDS);
    }
}
