package com.wodedianshi.tv;

/** Computes request timeouts without allowing a network read to ignore the probe-stage budget. */
public final class SourceProbeTimePolicy {
    private SourceProbeTimePolicy() {}
    public static final class PausedException extends java.io.IOException {
        public PausedException() { super("Source discovery paused for viewing"); }
    }
    /** Unchecked so Room's transaction callback rolls back on a viewing pause. */
    public static final class PublicationPausedException extends RuntimeException {
        public PublicationPausedException() { super("Catalogue publication paused for viewing"); }
    }
    /** Once this execution observes cancellation it cannot resume or score it as failure. */
    public static java.util.function.BooleanSupplier latchPause(java.util.function.BooleanSupplier requested) {
        java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (Thread.currentThread().isInterrupted() || requested.getAsBoolean()) paused.set(true);
            return paused.get();
        };
    }

    public static void checkPublicationPaused(java.util.function.BooleanSupplier paused) {
        if (Thread.currentThread().isInterrupted() || (paused != null && paused.getAsBoolean()))
            throw new PublicationPausedException();
    }
    public static void checkPaused(java.util.function.BooleanSupplier paused) throws PausedException {
        if (Thread.currentThread().isInterrupted() || (paused != null && paused.getAsBoolean()))
            throw new PausedException();
    }

    public static final long LIVE_DIRECTORY_MAX_BYTES=256L*1024L;
    public static final long LIVE_DIRECTORY_BYTES_PER_SECOND=32L*1024L;
    public static final long LIVE_DIRECTORY_BUDGET_MS=15000L;
    /** Negative content lengths are unknown; enforce the same cap as bytes arrive. */
    public static boolean fitsLiveDirectory(long bytes) {
        return bytes<=LIVE_DIRECTORY_MAX_BYTES;
    }
    public static long liveDirectoryPacingMillis(long copied,long elapsed) {
        long bounded=Math.max(0L,Math.min(LIVE_DIRECTORY_MAX_BYTES,copied));
        return Math.max(0L,(bounded*1000L+LIVE_DIRECTORY_BYTES_PER_SECOND-1L)
                /LIVE_DIRECTORY_BYTES_PER_SECOND-Math.max(0L,elapsed));
    }

    // First build prioritizes breadth. Keep one difficult HLS address from
    // monopolizing an entire slice. A timed-out probe is inconclusive.
    public static final long INITIAL_ADDRESS_BUDGET_MS = 15_000L;
    public static long initialAddressDeadline(long batchDeadline, long now) {
        if (now < 0L || batchDeadline < now
                || batchDeadline - now < INITIAL_ADDRESS_BUDGET_MS) return 0L;
        return now + INITIAL_ADDRESS_BUDGET_MS;
    }

    /**
     * Returns zero when the deadline has expired; otherwise caps the request timeout
     * at both the per-request maximum and the remaining elapsed-realtime budget.
     */
    public static int boundedTimeoutMillis(long deadlineElapsedRealtime,
                                           long nowElapsedRealtime,
                                           int maximumTimeoutMillis) {
        if (maximumTimeoutMillis <= 0) return 0;
        long remaining = deadlineElapsedRealtime - nowElapsedRealtime;
        if (remaining <= 0L) return 0;
        return (int) Math.min(remaining, (long) maximumTimeoutMillis);
    }
}
