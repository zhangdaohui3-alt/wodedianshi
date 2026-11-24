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
    public static void checkPublicationPaused(java.util.function.BooleanSupplier paused) {
        if (Thread.currentThread().isInterrupted() || (paused != null && paused.getAsBoolean()))
            throw new PublicationPausedException();
    }
    public static void checkPaused(java.util.function.BooleanSupplier paused) throws PausedException {
        if (Thread.currentThread().isInterrupted() || (paused != null && paused.getAsBoolean()))
            throw new PausedException();
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
