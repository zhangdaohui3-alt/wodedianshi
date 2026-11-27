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

    /** Once first watch and all domestic core categories are covered, keep
     * idle network slots busy even in a small catalogue. Otherwise preserve
     * the existing drained-wave boundary for first useful previews. */
    static boolean permitsRollingInitial(boolean initial,boolean early,boolean preferCore,
            int stagedRows,int priorCoreMask) {
        return initial&&!early&&!preferCore&&(stagedRows>512
                ||(priorCoreMask>=0&&(priorCoreMask&FullDiscoveryProgressPolicy.ALL_CORE)
                    ==FullDiscoveryProgressPolicy.ALL_CORE));
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

    /** An idle initial slice has separate position and network budgets. Once the
     * network allotment is exhausted, still consume the contiguous prefix that
     * requires no new probe; viewing and ordinary work keep their original cap. */
    static boolean canConsumeProbePosition(boolean initial,boolean viewing,
            int checked,int limit,boolean requiresNetwork) {
        return checked<limit||(initial&&!viewing&&!requiresNetwork);
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

    /** Freeze the prior-page coverage preference, not live staging state,
     * throughout every continuation of the same immutable page. */
    static <T> long coveredPositions(java.util.List<T> rows,
            java.util.function.Predicate<T> covered) {
        if(rows.size()>INITIAL_PAGE_SIZE)return 0L;
        long mask=0L;
        for(int i=0;i<rows.size();i++)if(covered.test(rows.get(i)))mask|=1L<<i;
        return mask;
    }

    static long readCoverageMask(String record,String rawFingerprint,int count) {
        if(record==null||count<0||count>INITIAL_PAGE_SIZE
                ||!record.startsWith(rawFingerprint+":"))return -1L;
        try{
            long mask=Long.parseLong(record.substring(rawFingerprint.length()+1));
            return mask>=0L&&(mask>>>count)==0L?mask:-1L;
        }catch(NumberFormatException invalid){return -1L;}
    }

    /** Existing geographic/category priority is stronger than coverage.
     * Keep the original page's host/station interleave within each partition. */
    static <T> void prioritizeUncovered(java.util.List<T> rows,long covered,
            java.util.function.ToIntFunction<T> priority) {
        if(covered<=0L||rows.size()>INITIAL_PAGE_SIZE)return;
        stablePriorityPartition(rows,covered,priority);
    }

    /** Stable geographic ordering with one classification per row, rather
     * than repeating metadata analysis during comparator calls. */
    static <T> void stablePrioritySort(java.util.List<T> rows,java.util.function.ToIntFunction<T> priority){
        if(rows.size()<2)return;
        stablePriorityPartition(rows,0L,priority);
    }

    private static <T> void stablePriorityPartition(java.util.List<T> rows,long covered,
            java.util.function.ToIntFunction<T> priority){
        java.util.TreeMap<Integer,java.util.ArrayList<T>> groups=new java.util.TreeMap<>();
        for(int i=0;i<rows.size();i++){
            T row=rows.get(i);
            int priorityRank=priority.applyAsInt(row);
            int rank=covered==0L?priorityRank:2*priorityRank+((covered&(1L<<i))!=0L?1:0);
            groups.computeIfAbsent(rank,k->new java.util.ArrayList<>()).add(row);
        }
        rows.clear();
        for(java.util.ArrayList<T> group:groups.values())rows.addAll(group);
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
        return transientRecheckRemainingMs(admittedAt,now)>0L;
    }
    static long transientRecheckRemainingMs(long admittedAt,long now) {
        if(admittedAt<=0L||now<admittedAt)return 0L;
        return Math.max(0L,TRANSIENT_RECHECK_COOLDOWN_MS-(now-admittedAt));
    }
    /** Only call when all unfinished feeds in the active stage are cooling.
     * Round up to avoid scheduling before the first eligible replay. */
    static long earliestStageCooldownSeconds(boolean[] completed,long[] remaining,
            int mainlandEnd,int regionEnd) {
        if(completed==null||remaining==null||completed.length!=remaining.length)return 30L;
        int size=completed.length;
        int mainland=Math.max(0,Math.min(mainlandEnd,size));
        int regions=Math.max(mainland,Math.min(regionEnd,size));
        int stage=SourceDiscoveryPriorityPolicy.activeStage(completed,mainland,regions);
        if(stage==3)return 30L;
        int from=stage==0?0:stage==1?mainland:regions;
        int to=stage==0?mainland:stage==1?regions:size;
        long earliest=TRANSIENT_RECHECK_COOLDOWN_MS;
        for(int i=from;i<to;i++)if(!completed[i]){
            if(remaining[i]<=0L)return 30L;
            earliest=Math.min(earliest,remaining[i]);
        }
        return (earliest+999L)/1000L;
    }
    /** When no region has eligible work, wake at the first cooling feed
     * anywhere. Selection will still prefer mainland when it is eligible.
     * A missing/inconsistent cooldown retains the conservative retry delay. */
    static long earliestEligibleCooldownSeconds(boolean[] completed,long[] remaining) {
        if(completed==null||remaining==null||completed.length!=remaining.length)return 30L;
        long earliest=TRANSIENT_RECHECK_COOLDOWN_MS;
        for(int i=0;i<completed.length;i++)if(!completed[i]){
            if(remaining[i]<=0L)return 30L;
            earliest=Math.min(earliest,remaining[i]);
        }
        return (earliest+999L)/1000L;
    }
    static int resumeTransientRecheck(boolean sameFingerprint,int marker,int scanned) {
        if(!sameFingerprint)return NO_TRANSIENT_RECHECK;
        if(marker==TRANSIENT_RECHECK_USED)return marker;
        return marker>=0&&marker<scanned?marker:NO_TRANSIENT_RECHECK;
    }
    /** Extra timeout replay is reserved for a missing domestic core category in the early build.
     * The existing immutable-page replay marker and cooldown still bound it to one suffix replay. */
    static boolean shouldRecheckCoreTimeout(boolean timedOut,int stagedRows,long checkedUrls,
            int observedMask,int candidateMask) {
        if(!timedOut||stagedRows<0||stagedRows>=96||checkedUrls<0||checkedUrls>=144)return false;
        if(observedMask<0&&!(stagedRows<16&&checkedUrls<48))return false;
        int knownMask=Math.max(0,observedMask);
        return (candidateMask&~knownMask&FullDiscoveryProgressPolicy.ALL_CORE)!=0;
    }
    static int noteTransientRecheck(int marker,int position) {
        return marker==NO_TRANSIENT_RECHECK&&position>=0?position:marker;
    }
    static boolean shouldReplayTransientSuffix(int marker,int scanned,int count) {
        return count>0&&scanned>=count&&marker>=0&&marker<count;
    }

    // One bounded mask per immutable page; -1 preserves conservative legacy
    // suffix replay when an older or malformed checkpoint lacks exact positions.
    static String initialReplayMaskKey(int feedIndex) {
        return initialBatchPrefix(feedIndex)+"replay_mask";
    }
    static long resumeReplayMask(String stored,int marker,int count) {
        if(marker==NO_TRANSIENT_RECHECK)return 0L;
        if(count<=0||count>INITIAL_PAGE_SIZE||count>62)return -1L;
        try {
            long mask=Long.parseLong(stored);
            if(mask<=0L||(mask>>>count)!=0L)return -1L;
            if(marker>=0&&(marker>=count||(mask&(1L<<marker))==0L))return -1L;
            return mask;
        }catch(NumberFormatException invalid){return -1L;}
    }
    static long noteReplayPosition(long mask,int position,int count) {
        if(mask<0L||count>INITIAL_PAGE_SIZE||position<0||position>=count)return -1L;
        return mask|(1L<<position);
    }
    // A deferred endpoint can have multiple distinct station/metadata rows.
    // Keep all of its aliases in the replay suffix, without replaying other URLs.
    static <T> long includeReplayAliases(long mask,java.util.List<T> page,
            java.util.function.Function<T,String> endpoint) {
        if(mask<0L||page.size()>INITIAL_PAGE_SIZE)return -1L;
        java.util.HashSet<String> needed=new java.util.HashSet<>();
        for(int i=0;i<page.size();i++)if((mask&(1L<<i))!=0L)needed.add(endpoint.apply(page.get(i)));
        for(int i=0;i<page.size();i++)if(needed.contains(endpoint.apply(page.get(i))))mask|=1L<<i;
        return mask;
    }
    static boolean skipReplayPosition(int marker,long mask,int position) {
        return marker==TRANSIENT_RECHECK_USED&&mask>0L&&position>=0&&position<INITIAL_PAGE_SIZE
                &&(mask&(1L<<position))==0L;
    }

    /** Persist with the exact partial-page checkpoint; never treat it as EOF. */
    static String initialIdleRecheckKey(int feedIndex) {
        return initialBatchPrefix(feedIndex)+"idle_recheck";
    }

    static boolean deferPageForIdle(boolean initial,boolean viewing,boolean pending) {
        return initial&&viewing&&pending;
    }

    /** Once the suffix has been rewound, its replay must remain idle-only.
     * A legacy hold (no replay marker) can resume its exact saved prefix under
     * the new policy; an incomplete forward scan may continue until page end. */
    static boolean deferInitialReplayForIdle(boolean viewing,int marker) {
        return viewing&&marker==TRANSIENT_RECHECK_USED;
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
