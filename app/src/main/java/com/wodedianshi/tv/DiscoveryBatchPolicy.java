package com.wodedianshi.tv;

/**
 * Tracks progress inside an immutable discovery batch by position, not URL.
 * A playlist may legitimately list the same endpoint under several channel
 * identities, so URL-based resume keys can jump over unprocessed entries.
 */
public final class DiscoveryBatchPolicy {
    private DiscoveryBatchPolicy() {}

    // Fixed across idle/viewing transitions so partial page fingerprints stay
    // stable. Only the number of probes admitted per slice changes.
    static final int INITIAL_PAGE_SIZE=48;
    // Manual refresh processes more new candidates without reducing the existing
    // saved-route health-check allotment or changing the routine maintenance page.
    static final int MANUAL_PAGE_SIZE=INITIAL_PAGE_SIZE;
    /** Revalidate at a new directory, not on every continuation of its page. */
    static boolean manualRevalidateDirectory(int cursor,String partialFingerprint) {
        return cursor<=0&&(partialFingerprint==null||partialFingerprint.isEmpty());
    }
    static final int VIEWING_PROBES_PER_SLICE=2;
    static final long VIEWING_SUCCESS_DELAY_SECONDS=5L;
    static int probeLimit(boolean initial,boolean viewing,int ordinaryLimit) {
        return initial?(viewing?VIEWING_PROBES_PER_SLICE:INITIAL_PAGE_SIZE):ordinaryLimit;
    }

    // Bound the initial wait for a durable first-watch preview. This changes
    // only the admitted window, not the immutable page size or fingerprint.
    // After a small initial network budget, resume full throughput even if
    // no usable channels have been found.
    static boolean isEarlyFirstWatch(int stagedChannels,int checkedUrls) {
        return stagedChannels<16&&checkedUrls<48;
    }

    // Preserve the domestic family rotation a little longer when a core
    // category is still missing. This is separate from the six-request
    // first-watch limit: later slices retain full throughput.
    static boolean preferCoreCoverage(int stagedChannels,int checkedUrls,int stagedCoreMask) {
        return isEarlyFirstWatch(stagedChannels,checkedUrls)
                ||(stagedCoreMask>=0
                    &&(stagedCoreMask&FullDiscoveryProgressPolicy.ALL_CORE)!=FullDiscoveryProgressPolicy.ALL_CORE
                    &&checkedUrls<144&&stagedChannels<96);
    }

    // A quickly acquired nonempty directory can enter the ordinary bounded
    // probe path in the same idle slice, including after first watch. Slow
    // acquisition and stable viewing retain separate work requests.
    static boolean canProbeAfterQuickAcquisition(boolean viewing,
            int indexedEntries,long elapsedMs) {
        return !viewing&&indexedEntries>0&&elapsedMs>=0L&&elapsedMs<=10000L;
    }

    /** Stop only at a drained wave boundary after a first useful mainland
     * preview has been merged in memory. Commit the contiguous prefix through
     * the normal staging and publication transaction; never discard a suffix. */
    static boolean yieldForFirstMainlandPreview(boolean early,boolean viewing,
            int previousMainlandValidated,int newlyValidated,int stagedRows,boolean wavePending) {
        return early&&!viewing&&previousMainlandValidated<=0
                &&newlyValidated>0&&stagedRows>0&&!wavePending;
    }

    /** Publish the first newly covered core category at a drained wave
     * boundary instead of waiting for the ordinary 48-request slice.
     * Keep the full-size staging and stable-viewing paths unchanged. */
    static boolean yieldForNewCorePreview(boolean viewing,int stagedRows,int priorMask,
            int observedMask,int mainlandValidated,boolean wavePending) {
        return !viewing&&!wavePending&&stagedRows>0&&stagedRows<=512
                &&priorMask>=0&&mainlandValidated>0
                &&(observedMask&~priorMask&FullDiscoveryProgressPolicy.ALL_CORE)!=0;
    }

    static int firstWatchProbeLimit(boolean viewing,int stagedChannels,int checkedUrls) {
        if(viewing)return VIEWING_PROBES_PER_SLICE;
        return isEarlyFirstWatch(stagedChannels,checkedUrls)?6:INITIAL_PAGE_SIZE;
    }

    /** A cached or shared endpoint does not consume a fresh network request.
     * Allow a bounded longer contiguous prefix for the early idle slice, while
     * keeping the six-request limit and the existing two-position viewing cap. */
    static int discoveryPositionWindow(boolean early,boolean viewing,int remaining,int ordinaryWindow) {
        if(viewing)return Math.min(Math.max(0,remaining),VIEWING_PROBES_PER_SLICE);
        return Math.min(Math.max(0,remaining),early?24:Math.max(0,ordinaryWindow));
    }

    /** Includes the current request, whose counter was incremented before
     * asking to admit a wave. Never launch speculative requests over budget. */
    static int waveRequestAllowance(int limit,int checkedIncludingCurrent) {
        return Math.max(0,limit-checkedIncludingCurrent+1);
    }

    /** Remove only exact duplicate records within one immutable raw page.
     * Keep different station metadata, URL spellings and scopes separate.
     * The caller retains the original raw parsed count for its feed cursor. */
    static <T> java.util.ArrayList<T> distinctExactPageRecords(java.util.List<T> rows,
            java.util.function.Function<T,java.util.List<String>> fields) {
        java.util.HashSet<java.util.List<String>> seen=new java.util.HashSet<>();
        java.util.ArrayList<T> result=new java.util.ArrayList<>(rows.size());
        for(T row:rows)if(seen.add(new java.util.ArrayList<>(fields.apply(row))))result.add(row);
        return result;
    }

    /** Independent partial page checkpoints enable fair rotation. */
    public static String initialBatchPrefix(int feedIndex) {
        return "source_initial_page_"+feedIndex+"_";
    }

    // A single durable replay of the suffix of an unchanged initial page.
    // -1 means no transient failure; -2 means the replay has been admitted.
    static final int NO_TRANSIENT_RECHECK=-1, TRANSIENT_RECHECK_USED=-2;
    static String initialTransientRecheckKey(int feedIndex) {
        return initialBatchPrefix(feedIndex)+"transient_recheck";
    }
    static final long TRANSIENT_RECHECK_COOLDOWN_MS=30000L;
    static String initialTransientRecheckAtKey(int feedIndex) {
        return initialBatchPrefix(feedIndex)+"transient_recheck_at";
    }
    // Persist wall time across process restarts. A clock rollback cannot
    // indefinitely block the page; invalid/missing timestamps are eligible.
    static boolean transientRecheckCooling(long admittedAt,long now) {
        return admittedAt>0L&&now>=admittedAt
                &&now-admittedAt<TRANSIENT_RECHECK_COOLDOWN_MS;
    }
    static int resumeTransientRecheck(boolean sameFingerprint,int marker,int scanned) {
        if(!sameFingerprint)return NO_TRANSIENT_RECHECK;
        if(marker==TRANSIENT_RECHECK_USED)return marker;
        return marker>=0&&marker<scanned?marker:NO_TRANSIENT_RECHECK;
    }
    static int noteTransientRecheck(int marker,int position) {
        return marker==NO_TRANSIENT_RECHECK&&position>=0?position:marker;
    }
    static boolean shouldReplayTransientSuffix(int marker,int scanned,int count) {
        return count>0&&scanned>=count&&marker>=0&&marker<count;
    }

    /** Persist with the exact partial-page checkpoint; never treat it as EOF. */
    static String initialIdleRecheckKey(int feedIndex) {
        return initialBatchPrefix(feedIndex)+"idle_recheck";
    }

    static boolean deferPageForIdle(boolean initial,boolean viewing,boolean pending) {
        return initial&&viewing&&pending;
    }

    /** A paced inconclusive candidate has not had its unrestricted probe turn. */
    static boolean holdInconclusiveCandidate(boolean initial,boolean viewing,boolean savedSource) {
        return initial&&viewing&&!savedSource;
    }

    /** A prepared index belongs to one exact body, not simply its URL. */
    public static boolean isPreparedInitialFeed(String preparedVersion,String currentVersion) {
        return currentVersion!=null&&!currentVersion.isEmpty()&&currentVersion.equals(preparedVersion);
    }

    /** Never apply an offset to a different or unidentified feed body. */
    public static int cursorForVersion(int cursor, String savedVersion, String currentVersion) {
        return cursor > 0 && currentVersion != null && !currentVersion.isEmpty()
                && currentVersion.equals(savedVersion) ? cursor : 0;
    }

    public static int scannedForBatch(String fingerprint, String previousFingerprint,
            int previousScanned, int candidateCount) {
        if (candidateCount <= 0 || fingerprint == null
                || !fingerprint.equals(previousFingerprint)) return 0;
        return Math.max(0, Math.min(previousScanned, candidateCount));
    }

    public static int startIndex(int scanned, int candidateCount) {
        if (candidateCount <= 0 || scanned >= candidateCount) return 0;
        return Math.max(0, scanned);
    }

    public static int remaining(int scanned, int candidateCount) {
        return Math.max(0, candidateCount - Math.max(0, scanned));
    }
}
