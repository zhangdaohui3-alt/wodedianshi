package com.wodedianshi.tv;

/** In-process viewing gate. The manifest and default WorkManager use the same app process.
 * No persisted busy flag can survive process death, reboot, or a clock adjustment. */
public final class SourceUpdateActivityPolicy {
    private static volatile boolean activityActive;
    private static volatile boolean playbackActive;
    private SourceUpdateActivityPolicy() {}
    public static void setActivityActive(boolean active) { activityActive = active; }
    public static void setPlaybackActive(boolean active) { playbackActive = active; }
    public static boolean isActivityActive() { return activityActive; }
    public static boolean isPlaybackActive() { return playbackActive; }
}
