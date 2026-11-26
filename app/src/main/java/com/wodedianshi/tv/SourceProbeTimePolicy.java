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

    public static final long LIVE_RESUMABLE_MAX_BYTES=8L*1024L*1024L;
    // A non-range response may be downloaded in one bounded, paced session.
    // Never join bodies without a strong validator. Keep the 256 KiB range cap.
    public static final long LIVE_FULL_DIRECTORY_BUDGET_MS=300000L;
    public static boolean fitsLiveFullDirectory(long bytes) {
        return bytes<=LIVE_RESUMABLE_MAX_BYTES;
    }
    public static long liveFullDirectoryPacingMillis(long copied,long elapsed) {
        long bounded=Math.max(0L,Math.min(LIVE_RESUMABLE_MAX_BYTES,copied));
        return Math.max(0L,(bounded*1000L+LIVE_DIRECTORY_BYTES_PER_SECOND-1L)
                /LIVE_DIRECTORY_BYTES_PER_SECOND-Math.max(0L,elapsed));
    }

    /** A fully downloaded range is also a valid durable checkpoint, pending local finalization. */
    public static boolean validDirectoryCheckpoint(long offset,long total,long fileLength,
            String etag,String location) {
        return offset>0L&&total>=offset&&total<=LIVE_RESUMABLE_MAX_BYTES
                &&fileLength>=offset&&strongDirectoryValidator(etag)
                &&location!=null&&!location.isEmpty();
    }
    public static boolean strongDirectoryValidator(String etag) {
        return etag!=null&&etag.length()>=2&&etag.startsWith("\"")&&etag.endsWith("\"")
                &&etag.substring(1,etag.length()-1).indexOf('"')<0
                &&etag.indexOf('\r')<0&&etag.indexOf('\n')<0;
    }
    /** Strict Content-Range check; reject unknown totals and changed offsets. */
    public static long[] liveDirectoryRange(String header,long offset,long priorTotal) {
        return directoryRange(header,offset,priorTotal,LIVE_DIRECTORY_MAX_BYTES);
    }

    public static long[] directoryRange(String header,long offset,long priorTotal,long chunkLimit) {
        if(chunkLimit<=0L||chunkLimit>LIVE_RESUMABLE_MAX_BYTES)return null;
        if(header==null||offset<0L||!header.matches("bytes [0-9]+-[0-9]+/[0-9]+"))return null;
        try{
            String[] values=header.substring(6).split("[-/]");
            long start=Long.parseLong(values[0]),end=Long.parseLong(values[1]),total=Long.parseLong(values[2]);
            if(start!=offset||end<start||total<=end||total>LIVE_RESUMABLE_MAX_BYTES
                    ||end-start>=chunkLimit
                    ||(priorTotal>0L&&total!=priorTotal))return null;
            return new long[]{end-start+1,total};
        }catch(NumberFormatException invalid){return null;}
    }

    // Only stable-viewing initial discovery uses tighter socket inactivity limits.
    // They are not a hard bound on DNS, redirects, or the total request duration.
    static int sampleConnectTimeoutCap(boolean viewing) { return viewing ? 1500 : 2500; }
    static int sampleReadTimeoutCap(boolean viewing) { return viewing ? 1000 : 3000; }

    // Viewing probes are serial; pace each chunk without accumulating credit
    // during a slow response or across HLS requests. This is application-level
    // body consumption, not a TCP/OS bandwidth guarantee.
    static final int VIEWING_SAMPLE_CHUNK_BYTES=4096;
    static final long VIEWING_SAMPLE_BYTES_PER_SECOND=32L*1024L;
    static final long VIEWING_SAMPLE_PAUSE_POLL_MS=25L;
    static long viewingSampleDelayMillis(int bytes) {
        if(bytes<=0)return 0L;
        return (bytes*1000L+VIEWING_SAMPLE_BYTES_PER_SECOND-1L)
                /VIEWING_SAMPLE_BYTES_PER_SECOND;
    }

    /** Read at most the retained sample plus one sentinel, never a full extra buffer. */
    static int sampleReadLength(int maxBytes,int received,int bufferLength) {
        if(maxBytes<=0||received<0||bufferLength<=0)return 0;
        long remaining=(long)maxBytes+1L-received;
        return remaining<=0L?0:(int)Math.min(remaining,(long)bufferLength);
    }

    /** Incomplete HTTP ranges can be shorter than the cap; never synthesize zero bytes. */
    static int sampleRetainedLength(int received,int maxBytes) {
        return Math.max(0,Math.min(received,maxBytes));
    }

    /** A socket timeout is not proof that a stream is invalid, even before the total budget. */
    public static boolean initialProbeInconclusive(boolean socketTimedOut,long finishedAt,long addressDeadline) {
        return socketTimedOut || finishedAt>=addressDeadline;
    }

    /** One additional attempt for a duplicate timed-out endpoint in the same
     * slice. Never persist this budget as a negative score or a cache entry. */
    static boolean retryInconclusiveWithinSlice(int attempts) {
        return attempts==1;
    }

    /** Reserve a small owner-merge margin rather than starting a last-second parallel wave. */
    public static boolean hasWaveDrainMargin(long batchDeadline,long addressDeadline) {
        return addressDeadline>=0L && batchDeadline>=addressDeadline
                && batchDeadline-addressDeadline>=2000L;
    }

    /** Give the best rendition a substantial window, but reserve five seconds
     * for a distinct sibling when the remaining first-build idle budget allows.
     * Never shorten the only/last rendition or the ordinary/viewing probe. */
    static long initialRenditionDeadline(boolean initialIdle,boolean hasSibling,
            long now,long addressDeadline) {
        if(!initialIdle||!hasSibling||now<0L||addressDeadline<now
                ||addressDeadline-now<11000L)return addressDeadline;
        return addressDeadline-5000L;
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
