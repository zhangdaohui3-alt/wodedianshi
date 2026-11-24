package com.wodedianshi.tv;

/** In-process viewing gate. The manifest and default WorkManager use the same app process.
 * No persisted busy flag can survive process death, reboot, or a clock adjustment. */
public final class SourceUpdateActivityPolicy {
    private static volatile boolean activityActive;
    private static volatile boolean playbackActive;
    private SourceUpdateActivityPolicy() {}
    private static final java.util.concurrent.atomic.AtomicLong activityStarts = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong playbackStarts = new java.util.concurrent.atomic.AtomicLong();
    public static synchronized void setActivityActive(boolean active) {
        if (active && !activityActive) activityStarts.incrementAndGet();
        activityActive = active;
    }
    public static synchronized void setPlaybackActive(boolean active) {
        if (active && !playbackActive) playbackStarts.incrementAndGet();
        playbackActive = active;
    }
    public static long activityStartGeneration() { return activityStarts.get(); }
    public static long playbackStartGeneration() { return playbackStarts.get(); }
    public static boolean isActivityActive() { return activityActive; }
    public static boolean isPlaybackActive() { return playbackActive; }
}
