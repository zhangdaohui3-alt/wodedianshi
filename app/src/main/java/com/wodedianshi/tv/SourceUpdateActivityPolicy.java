package com.wodedianshi.tv;

/** In-process viewing gate. The manifest and default WorkManager use the same app process.
 * No persisted busy flag can survive process death, reboot, or a clock adjustment. */
public final class SourceUpdateActivityPolicy {
    private static volatile boolean activityActive;
    private static volatile boolean playbackActive;
    private static volatile long stableUntil;
    private static long firstHealthyAt;
    private static long bufferSampleAt;
    private static long ampleBufferSince;
    private static long viewingSampleRate=32768L;
    private static volatile long lastPressureAt;
    private static final java.util.concurrent.atomic.AtomicLong pressureGeneration=new java.util.concurrent.atomic.AtomicLong();
    static long pressureGeneration(){return pressureGeneration.get();}
    static boolean idleWavePressureChanged(long admitted){
        return admitted>=0L&&pressureGeneration.get()!=admitted;
    }
    private static final long LEASE_MS=15000L;
    private static final long MIN_HEALTHY_INTERVAL_MS=8000L;
    private static final long LOW_BUFFER_MS=1000L;
    private static final long RECOVERED_BUFFER_MS=3000L;
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
    /** A healthy watchdog observation is not permission until another
     * independent observation arrives after a recovery interval. Expired
     * leases cannot silently revive an already admitted worker generation. */
    public static synchronized void reportStableLive(long now) {
        if(!activityActive||!playbackActive||now<=0L||now>=Long.MAX_VALUE-LEASE_MS){
            revokeStableLive();return;
        }
        if(stableUntil!=0L){
            if(stableUntil>now&&stableUntil-now<=LEASE_MS){stableUntil=now+LEASE_MS;return;}
            revokeStableLive();
        }
        if(firstHealthyAt==0L||now<firstHealthyAt||now-firstHealthyAt>LEASE_MS){
            firstHealthyAt=now;return;
        }
        if(now-firstHealthyAt>=MIN_HEALTHY_INTERVAL_MS){
            stableUntil=now+LEASE_MS;
            firstHealthyAt=0L;
        }
    }
    /** VLC's caller already checks two consecutive independent clock and
     * output-counter advances. Do not impose a third healthy poll solely to
     * obtain a discovery lease. Expiry still invalidates the old epoch. */
    static synchronized void reportConfirmedVlcStableLive(long now,int consecutiveChecks) {
        if(consecutiveChecks<2||!activityActive||!playbackActive
                ||now<=0L||now>=Long.MAX_VALUE-LEASE_MS){
            revokeStableLive();return;
        }
        if(stableUntil!=0L&&(stableUntil<=now||stableUntil-now>LEASE_MS))
            revokeStableLive();
        firstHealthyAt=0L;
        clearDiscoveryBuffer();
        stableUntil=now+LEASE_MS;
    }

    private static void clearDiscoveryBuffer(){
        bufferSampleAt=0L;ampleBufferSince=0L;viewingSampleRate=32768L;
    }
    /** Playback buffer is a headroom proxy, not a link bandwidth measurement.
     * Only the Media3 healthy watchdog may supply these observations. */
    static synchronized void reportMedia3DiscoveryBuffer(long now,long bufferedMs){
        if(!permitsInitialPlaybackWork(now)||bufferedMs<0L){
            clearDiscoveryBuffer();return;
        }
        if(bufferSampleAt!=0L&&(now<bufferSampleAt||now-bufferSampleAt>=LEASE_MS))
            clearDiscoveryBuffer();
        if(bufferedMs<LOW_BUFFER_MS
                ||(viewingSampleRate==16384L&&bufferedMs<RECOVERED_BUFFER_MS)){
            // Reduce immediately, but require actual buffer recovery before
            // leaving the low rate. Avoid oscillation around the low threshold.
            ampleBufferSince=0L;viewingSampleRate=16384L;
        }else if(bufferedMs>=10000L){
            // Measure sustained ample headroom from its first observation, not
            // the immediately previous watchdog tick. Freshness is tracked separately.
            if(ampleBufferSince==0L)ampleBufferSince=now;
            viewingSampleRate=now-ampleBufferSince>=MIN_HEALTHY_INTERVAL_MS?49152L:32768L;
        }else{
            ampleBufferSince=0L;viewingSampleRate=32768L;
        }
        bufferSampleAt=now;
    }
    static synchronized long viewingSampleBytesPerSecond(long now){
        if(!permitsInitialPlaybackWork(now)||bufferSampleAt<=0L
                ||now<bufferSampleAt||now-bufferSampleAt>=LEASE_MS)return 32768L;
        return viewingSampleRate;
    }
    public static synchronized void revokeStableLive() {
        firstHealthyAt=0L;
        clearDiscoveryBuffer();
        if(stableUntil!=0L){stableUntil=0L;stableRevocations.incrementAndGet();}
    }
    /** Called on playback transitions and user input, never from a network thread.
     * This does not wait for an in-flight connection to close. */
    public static synchronized void notePressure(long now){
        if(now>0L)lastPressureAt=now;
        pressureGeneration.incrementAndGet();
        revokeStableLive();
    }
    /** Called only after release continuations have run. Keep the worker
     * gated if any playback owner or queued native teardown remains. */
    static boolean mayClearAfterDecoderHandoff(boolean liveOwner,boolean vodOwner,
            boolean pendingContinuation,boolean releaseBusy,boolean quarantined,
            boolean decoderAvailable) {
        return !liveOwner&&!vodOwner&&!pendingContinuation&&!releaseBusy
                &&!quarantined&&decoderAvailable;
    }
    public static int idleProbeConcurrency(long now){
        return idleProbeConcurrency(now,false);
    }
    static int idleProbeConcurrency(long now,boolean initial){
        if(playbackActive)return 1;
        int ceiling=initial?IdleProbeWavePolicy.MAX_CONCURRENT:IdleProbeWavePolicy.ORDINARY_CONCURRENT;
        long last=lastPressureAt;
        if(last==0L)return ceiling;
        if(now<last||now-last<5000L)return 1;
        if(now-last<20000L)return 2;
        return ceiling;
    }
    public static synchronized boolean permitsInitialPlaybackWork(long now) {
        long until=stableUntil;
        if(until!=0L&&now>0L&&until<=now) {
            revokeStableLive();
            return false;
        }
        return activityActive&&playbackActive&&now>0L&&until>now&&until-now<=LEASE_MS;
    }
    /** Test permission and the worker's original admission epoch under one
     * lock; a revoked worker may not borrow a replacement viewing lease. */
    static synchronized boolean permitsInitialPlaybackWork(long now,long admittedGeneration) {
        return permitsInitialPlaybackWork(now)&&stableRevocations.get()==admittedGeneration;
    }
    public static long stableRevocationGeneration(){return stableRevocations.get();}
    public static long activityStartGeneration() { return activityStarts.get(); }
    public static long playbackStartGeneration() { return playbackStarts.get(); }
    public static boolean isActivityActive() { return activityActive; }
    public static boolean isPlaybackActive() { return playbackActive; }
}
