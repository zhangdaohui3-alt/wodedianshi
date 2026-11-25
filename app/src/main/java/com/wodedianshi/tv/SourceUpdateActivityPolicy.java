package com.wodedianshi.tv;

/** In-process viewing gate. The manifest and default WorkManager use the same app process.
 * No persisted busy flag can survive process death, reboot, or a clock adjustment. */
public final class SourceUpdateActivityPolicy {
    private static volatile boolean activityActive;
    private static volatile boolean playbackActive;
    private static volatile long stableUntil;
    private static final java.util.concurrent.atomic.AtomicLong stableRevocations=new java.util.concurrent.atomic.AtomicLong();
    private SourceUpdateActivityPolicy() {}
    private static final java.util.concurrent.atomic.AtomicLong activityStarts = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong playbackStarts = new java.util.concurrent.atomic.AtomicLong();
    public static synchronized void setActivityActive(boolean active) {
        if(!active)revokeStableLive();
        if (active && !activityActive) activityStarts.incrementAndGet();
        activityActive = active;
    }
    public static synchronized void setPlaybackActive(boolean active) {
        revokeStableLive();
        if (active && !playbackActive) playbackStarts.incrementAndGet();
        playbackActive = active;
    }
    /** A fresh, short-lived confirmation from the existing live watchdog only. */
    public static synchronized void reportStableLive(long now) {
        if (activityActive && playbackActive && now > 0L && now < Long.MAX_VALUE-15000L)
            stableUntil=now+15000L;
    }
    public static synchronized void revokeStableLive() {
        if(stableUntil!=0L){stableUntil=0L;stableRevocations.incrementAndGet();}
    }
    public static boolean permitsInitialPlaybackWork(long now) {
        long until=stableUntil;
        return activityActive&&playbackActive&&now>0L&&until>now&&until-now<=15000L;
    }
    public static long stableRevocationGeneration(){return stableRevocations.get();}
    public static long activityStartGeneration() { return activityStarts.get(); }
    public static long playbackStartGeneration() { return playbackStarts.get(); }
    public static boolean isActivityActive() { return activityActive; }
    public static boolean isPlaybackActive() { return playbackActive; }
}
