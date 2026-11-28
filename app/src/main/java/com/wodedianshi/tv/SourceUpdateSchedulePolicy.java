package com.wodedianshi.tv;

/** Controls the one-time independent full-catalogue rebuild lifecycle. */
public final class SourceUpdateSchedulePolicy {
    private SourceUpdateSchedulePolicy() {}

    /**
     * Schedule the independent full rebuild only when automatic updates are
     * enabled and no full rebuild has been published. Older incremental-update
     * timestamps must not suppress the first complete rebuild.
     */
    public static boolean shouldRunInitialDiscovery(boolean automaticUpdatesEnabled,
                                                    long fullRebuildCompletedAtMillis) {
        return automaticUpdatesEnabled && fullRebuildCompletedAtMillis <= 0L;
    }

    // A completed Room snapshot is the default directory. Periodic discovery is
    // deliberately infrequent; WorkManager's timing is inexact by design.
    public static final long ROUTINE_REFRESH_DAYS = 30L;
    public static final long ROUTINE_ELIGIBILITY_CHECK_DAYS = 1L;
    private static final long ROUTINE_REFRESH_MS = ROUTINE_REFRESH_DAYS * 24L * 60L * 60L * 1000L;

    /** A skipped busy check must not advance the 30-day maintenance deadline. */
    public static boolean isRoutineMaintenanceDue(long fullRebuildAt, long lastRoutineAt, long now) {
        if (fullRebuildAt <= 0L || now <= 0L) return false;
        long last = Math.max(fullRebuildAt, lastRoutineAt);
        return now < last || now - last >= ROUTINE_REFRESH_MS;
    }

    public static final String PROVISIONAL_RECHECK_KEY="source_probe_provisional_recheck_v1";
    static final int PROVISIONAL_RECHECK_CAP=2048;
    static final String PROVISIONAL_RECHECK_LAST_KEY="source_probe_provisional_recheck_last_v1";
    static final String QUALITY_ONLY_REVISION_KEY="source_probe_quality_only_revision_v1";
    static boolean needsIndexedQualityReload(long cachedCatalogue,long currentCatalogue,
            String cachedQualityRevision,String currentQualityRevision){
        return cachedCatalogue!=currentCatalogue
                ||!java.util.Objects.equals(cachedQualityRevision,currentQualityRevision);
    }
    // The existing 90-second stage and 15-second address admission remain
    // authoritative. Fast confirmations may use more of the same idle window;
    // a slow origin cannot force a new request into the final partial budget.
    static final int PROVISIONAL_DAILY_LIMIT=DiscoveryBatchPolicy.INITIAL_PAGE_SIZE;
    static final int PROVISIONAL_FAST_LIMIT=2*PROVISIONAL_DAILY_LIMIT;
    // Extend only a demonstrably cheap, healthy idle batch. These are
    // admission thresholds, not hard wire-byte limits; an admitted probe
    // may consume additional bytes before returning.
    static boolean permitsFastRecheckExtension(int attempted,int positive,long elapsedMs,
            long requests,long bytes){
        return attempted>=PROVISIONAL_DAILY_LIMIT&&positive>=3*attempted/4
                &&elapsedMs>=0L&&elapsedMs<=30000L
                &&requests>=0L&&requests<=192L&&bytes>=0L&&bytes<=2L*1024L*1024L;
    }
    static boolean permitsExtendedRecheckProbe(long requests,long bytes){
        return requests>=0L&&requests<384L&&bytes>=0L&&bytes<4L*1024L*1024L;
    }

    /** Admit at most one shortened final full-quality probe after a healthy
     * batch. Preserve two seconds for the owner and never extend the stage. */
    static long qualityRecheckDeadline(long stageDeadline,long now,boolean tailUsed,
            boolean lastHealthy,int attempted,int positive,long requests,long bytes){
        long ordinary=SourceProbeTimePolicy.initialAddressDeadline(stageDeadline,now);
        if(ordinary!=0L)return ordinary;
        if(now<0L||stageDeadline<now||stageDeadline-now<8000L||tailUsed||!lastHealthy
                ||attempted<4||positive<4||positive>attempted
                ||4L*positive<3L*attempted||!permitsExtendedRecheckProbe(requests,bytes))return 0L;
        return stageDeadline-2000L;
    }

    // Real stable output is stronger availability evidence than another
    // structural sample, but cannot replace a measured throughput score.
    // Defer only the extra quality pass, for a short freshness window.
    static final long STABLE_RECHECK_GRACE_MS=6L*60L*60L*1000L;
    static boolean deferRecentlyStableRecheck(String lastEvent,long lastStable,long updated,long now){
        return "stable".equals(lastEvent)&&lastStable>0L&&lastStable==updated
                &&now>=lastStable&&now-lastStable<STABLE_RECHECK_GRACE_MS;
    }

    /** Actual playback failure or buffering is a scheduling hint, not a
     * failed preflight. Decoder errors and stale/future evidence do not qualify. */
    static boolean recentPlaybackProblemForRecheck(String event,long updated,long now){
        return ("failure".equals(event)||"buffer".equals(event))
                &&updated>0L&&now>=updated&&now-updated<STABLE_RECHECK_GRACE_MS;
    }

    /** Preserve the oldest three normal turns, then allow at most three
     * priority turns (positions 4, 8, 12), at most one per origin.
     * All other rows retain relative order, including same-origin siblings.
     * The durable pending queue and normal network/cooldown gates are unchanged. */
    static <T> void promotePlaybackRechecks(java.util.List<T> ordered,
            java.util.function.Predicate<T> urgent,java.util.function.Function<T,String> originOf){
        java.util.HashSet<String> priorityOrigins=new java.util.HashSet<>();
        int searchFrom=3;
        for(int slot=3;slot<12&&slot<ordered.size();slot+=4){
            int found=Math.max(slot,searchFrom);
            while(found<ordered.size()){
                T candidate=ordered.get(found);
                if(urgent.test(candidate)&&priorityOrigins.add(originOf.apply(candidate)))break;
                found++;
            }
            if(found>=ordered.size())break;
            if(found!=slot)ordered.add(slot,ordered.remove(found));
            searchFrom=found+1;
        }
    }

    static java.util.ArrayList<String> selectProvisionalRechecks(
            java.util.Set<String> pending,java.util.Map<String,String> current) {
        return selectProvisionalRechecks(pending,current,key->false);
    }

    static java.util.ArrayList<String> selectProvisionalRechecks(
            java.util.Set<String> pending,java.util.Map<String,String> current,
            java.util.function.Predicate<String> temporarilyDeferred) {
        return provisionalRecheckOrder(pending,current,temporarilyDeferred,PROVISIONAL_DAILY_LIMIT);
    }

    /** Only a completed positive full sample can be shared by equivalent
     * endpoint aliases in the same bounded idle recheck. Never share failures,
     * provisional evidence or a cancelled/expired observation. */
    static boolean reusableFullRecheck(boolean valid,boolean inconclusive,boolean paced,
            boolean provisional,long finishedAt,long deadline){
        return valid&&!inconclusive&&!paced&&!provisional&&finishedAt>=0L&&finishedAt<deadline;
    }

    /** The owner can refill skipped slots from the bounded pending backlog;
     * the network admission count, not the candidate count, caps work. */
    static java.util.ArrayList<String> provisionalRecheckOrder(
            java.util.Set<String> pending,java.util.Map<String,String> current,
            java.util.function.Predicate<String> temporarilyDeferred,int limit) {
        java.util.ArrayList<String> selected=new java.util.ArrayList<>();
        limit=Math.max(0,Math.min(PROVISIONAL_RECHECK_CAP,limit));
        // Oldest route gets the first opportunity; thereafter take one route
        // per origin per round, preserving FIFO within each origin. A cluster
        // of slow siblings cannot occupy every early slot of the idle budget.
        java.util.LinkedHashMap<String,java.util.ArrayDeque<String>> byOrigin=new java.util.LinkedHashMap<>();
        for(String key:pending){
            String url=current.get(key);
            if(url==null||url.isEmpty()||temporarilyDeferred.test(key))continue;
            String origin=InitialOriginCooldownPolicy.origin(url);
            byOrigin.computeIfAbsent(origin,ignored->new java.util.ArrayDeque<>()).addLast(url);
        }
        java.util.ArrayDeque<java.util.ArrayDeque<String>> rounds=new java.util.ArrayDeque<>(byOrigin.values());
        while(!rounds.isEmpty()&&selected.size()<limit){
            java.util.ArrayDeque<String> routes=rounds.removeFirst();
            selected.add(routes.removeFirst());
            if(!routes.isEmpty())rounds.addLast(routes);
        }
        return selected;
    }

    /** One retry of an interrupted, already-started quality pass can consume
     * fresh cached evidence. Never retry an initial busy admission or keep
     * waking an active viewer; the next attempt still uses all idle gates. */
    static boolean retryInterruptedQualityPass(boolean provisionalOnly,int runAttempt,
            boolean stopped,boolean interrupted){
        return provisionalOnly&&runAttempt==0&&!stopped&&!interrupted;
    }

    /** Only the existing idle eligibility worker may run this extra small
     * quality-only batch. Ordinary full maintenance takes precedence. */
    // Daily WorkManager dispatch is inexact and the recorded timestamp is
    // the previous completion, not dispatch. A small eligibility tolerance
    // avoids skipping an entire daily opportunity for a slightly early run.
    // This does not schedule any new wakeup or alter the per-pass quota.
    static final long PROVISIONAL_RECHECK_MIN_INTERVAL_MS=23L*60L*60L*1000L;
    static boolean provisionalRecheckDue(boolean pending,long last,long now){
        return pending&&now>0L&&(last<=0L||now<last||now-last>=PROVISIONAL_RECHECK_MIN_INTERVAL_MS);
    }


    /** Only a complete, version-guarded publication can prove a pending URL
     * no longer belongs to the directory. Preserve queue order for survivors. */
    static int pruneUnretainedProvisional(java.util.Set<String> pending,
            java.util.Set<String> retained,boolean complete){
        if(!complete||pending.isEmpty())return 0;
        int before=pending.size();
        pending.retainAll(retained);
        return before-pending.size();
    }

    /** Reserve room for every candidate position, including endpoint aliases.
     * Admission is fixed for the slice so speculative parallel work cannot
     * overflow the durable provisional queue. */
    static boolean permitsProvisionalScreen(int pending,int positions){
        return pending>=0&&positions>0&&pending<=PROVISIONAL_RECHECK_CAP
                &&positions<=PROVISIONAL_RECHECK_CAP-pending;
    }

    /** Reserve all eligible raw positions, not distinct endpoints. Positions
     * that are guaranteed to bypass probing cannot add pending evidence. */
    static <T> boolean permitsProvisionalScreen(int pending,java.util.List<T> positions,
            java.util.function.Predicate<T> eligible){
        int reserved=0;
        for(T position:positions)if(eligible.test(position))reserved++;
        return permitsProvisionalScreen(pending,reserved);
    }

    /** Bounded insertion-order evidence list; retry attempts can move a key to
     * the tail without extending the normal saved-source rotation. */
    static void rememberProvisional(java.util.LinkedHashSet<String> keys,String key){
        if(key==null||key.isEmpty())return;
        keys.remove(key);keys.add(key);
        while(keys.size()>PROVISIONAL_RECHECK_CAP)keys.remove(keys.iterator().next());
    }

    /** Reserve at most one quarter of an existing saved window for evidence
     * upgrades outside its ordinary contiguous prefix. Never skip normal rows. */
    static java.util.ArrayList<Integer> provisionalRecheckPositions(
            java.util.List<String> savedKeys,int start,int window,java.util.Set<String> pending){
        return provisionalRecheckPositions(savedKeys.size(),savedKeys::get,start,window,pending);
    }

    /** Generate exact keys only when visited; stop once the oldest reserved
     * candidates are all located. Missing keys still require a full scan. */
    static java.util.ArrayList<Integer> provisionalRecheckPositions(
            int size,java.util.function.IntFunction<String> keyAt,int start,int window,
            java.util.Set<String> pending){
        java.util.ArrayList<Integer> result=new java.util.ArrayList<>();
        int limit=Math.min(3,window/4);
        if(size<=window||window<=0||limit<=0||pending.isEmpty())return result;
        java.util.HashSet<String> oldest=new java.util.HashSet<>();
        for(String key:pending){
            oldest.add(key);
            if(oldest.size()>=limit)break;
        }
        java.util.HashMap<String,Integer> outside=new java.util.HashMap<>();
        for(int i=window;i<size;i++){
            int position=(start+i)%size;
            String key=keyAt.apply(position);
            if(pending.contains(key)){
                outside.putIfAbsent(key,position);
                oldest.remove(key);
                if(oldest.isEmpty())break;
            }
        }
        for(String key:pending){
            Integer position=outside.get(key);
            if(position!=null)result.add(position);
            if(result.size()>=limit)break;
        }
        return result;
    }

    // Three 4-entry feed pages fit the 12 discovery slots even when saved
    // routes consume half of the 24-probe budget. No wrap inside one slice.
    public static final int ROUTINE_FEEDS_PER_SLICE = 3;

    public static int routineSliceEnd(int start, int feedCount) {
        if (feedCount <= 0) return 0;
        int safe = InitialFeedRotationPolicy.normalizeStart(start, feedCount);
        return Math.min(feedCount, safe + ROUTINE_FEEDS_PER_SLICE);
    }

    public static int routineNextFeed(int start, int feedCount, boolean batchComplete) {
        if (feedCount <= 0) return 0;
        int safe = InitialFeedRotationPolicy.normalizeStart(start, feedCount);
        return batchComplete ? routineSliceEnd(safe, feedCount) % feedCount : safe;
    }

    /** Match the same endpoint equivalence used for within-slice deduplication.
     * Keep path, query, credentials and nondefault ports exact. */
    static String initialProbeCacheKey(String url) {
        return SourceSelectionPolicy.endpointKey(url);
    }

    /** Reuse only a completed probe from this process, never a historical score.
     * Failures expire quickly so temporary outages are not held for long. */
    public static boolean canReuseInitialProbe(boolean valid, long measuredAt, long now) {
        return canReuseInitialProbe(valid,false,measuredAt,now);
    }

    /** A provisional direct-stream screen may deduplicate nearby candidates,
     * but never inherit the longer lifetime of a full preflight. */
    static boolean canReuseInitialProbe(boolean valid,boolean provisional,long measuredAt,long now) {
        return measuredAt >= 0L && now >= measuredAt
                && now <= initialProbeExpiry(valid,provisional,measuredAt);
    }

    /** Inclusive last eligible instant, using the same lifetime as lookup. */
    static long initialProbeExpiry(boolean valid,boolean provisional,long measuredAt){
        if(measuredAt<0L)return -1L;
        long ttl=valid?(provisional?60_000L:300_000L):15_000L;
        return measuredAt>Long.MAX_VALUE-ttl?Long.MAX_VALUE:measuredAt+ttl;
    }

    /** Reuse only fresh evidence of the same durable manual refresh session. */
    static boolean manualProbeCacheMatches(boolean resume,String manualId,String cachedOwner){
        return resume&&manualId!=null&&!manualId.isEmpty()&&manualId.equals(cachedOwner);
    }

    /** Single rule for the owner, initial wave admission and rolling refill.
     * Paced observations cannot stand in for an unpaced quality measurement. */
    static boolean reusableInitialObservation(boolean valid,boolean provisional,boolean paced,
            boolean viewing,long measuredAt,long now,boolean independentHealth) {
        // A definitive negative can suppress duplicate discovery, but only a
        // fresh health measurement may contribute a saved-source failure.
        return (valid||!independentHealth)
                &&SourceQualityScorer.reusableProbeMode(paced,viewing)
                &&canReuseInitialProbe(valid,provisional,measuredAt,now);
    }

    /** At capacity discard expired observations before evicting a still reusable
     * least-recently-used result. The owner retains the original hard cap. */
    static <K,V> void trimInitialProbeCache(java.util.LinkedHashMap<K,V> cache,int capacity,
            java.util.function.Predicate<V> reusable) {
        int limit=Math.max(0,capacity);
        if(cache.size()<=limit)return;
        java.util.Iterator<V> values=cache.values().iterator();
        while(values.hasNext()&&cache.size()>limit){
            if(!reusable.test(values.next()))values.remove();
        }
        while(cache.size()>limit)cache.remove(cache.keySet().iterator().next());
    }

    /** The owner supplies a conservative earliest-expiry hint. Before that
     * instant, eviction requires no linear expiry scan. When it elapses,
     * sweep once and recompute the hint from the remaining entries.
     * The monotonic elapsed-realtime clock and process-local ownership make
     * the lower bound safe even when a prior earliest entry was overwritten. */
    static <K,V> long trimInitialProbeCacheByExpiry(java.util.LinkedHashMap<K,V> cache,int capacity,
            long earliestExpiry,long now,java.util.function.ToLongFunction<V> expiry){
        int limit=Math.max(0,capacity);
        if(cache.size()<=limit)return earliestExpiry;
        if(now>earliestExpiry){
            earliestExpiry=Long.MAX_VALUE;
            java.util.Iterator<V> values=cache.values().iterator();
            while(values.hasNext()){
                long end=expiry.applyAsLong(values.next());
                if(now>end)values.remove();
                else earliestExpiry=Math.min(earliestExpiry,end);
            }
        }
        while(cache.size()>limit)cache.remove(cache.keySet().iterator().next());
        return earliestExpiry;
    }

    /** A positive completed media preflight can survive owner cancellation,
     * but never use a timed-out, unfinished or cancelled result as evidence. */
    static boolean mayRetainCompletedInitialSuccess(boolean valid,boolean socketTimedOut,
            long finishedAt,long addressDeadline,long batchDeadline) {
        return valid&&!socketTimedOut&&finishedAt>=0L
                &&finishedAt<addressDeadline&&finishedAt<batchDeadline;
    }

    /** Do not make a different, unfailed directory inherit the failed one's
     * retry delay. Retain backoff for repeated failure, no eligible successor,
     * and every viewing slice. Called after the durable outcome commits. */
    static long initialContinuationDelaySeconds(boolean viewing,boolean feedRead,
            boolean freshDifferentNextFeed) {
        if(feedRead)return viewing?DiscoveryBatchPolicy.VIEWING_SUCCESS_DELAY_SECONDS:0L;
        return !viewing&&freshDifferentNextFeed?0L:30L;
    }

    /** A readable but nonempty page that consumes no candidate is not
     * progress. Avoid an immediate scheduling loop even when the stop was
     * before wave admission (e.g. the remaining stage budget is too small).
     * A completed empty page or another eligible directory may still advance. */
    static long initialCommittedContinuationDelay(boolean viewing,boolean feedRead,
            boolean freshDifferentNextFeed,boolean pageComplete,int scanned,boolean waveTimedOut) {
        if(!viewing&&feedRead&&scanned<=0&&(!pageComplete||waveTimedOut))return 30L;
        return initialContinuationDelaySeconds(viewing,feedRead,freshDifferentNextFeed);
    }

    // Idle slices normally have a 90s probe stage plus bounded acquisition.
    // Do not apply this guard to the separately paced 300s viewing download.
    static final long INITIAL_IDLE_SLICE_LIMIT_MS=240000L;
    static boolean initialIdleSliceExpired(boolean initial,boolean viewing,long started,long now) {
        return initial&&!viewing&&started>=0L&&now>=started
                &&now-started>=INITIAL_IDLE_SLICE_LIMIT_MS;
    }
    static final long INITIAL_STALL_OBSERVATION_MS=180000L;
    /** A missing chain can be restored with KEEP, never by cancelling a live
     * owner. Failed empty-build resets and disabled auto updates stay excluded. */
    static boolean mayRecoverMissingInitialChain(long unchangedMs,boolean observed,
            boolean unfinished,boolean ownerActive,boolean playbackActive,
            boolean autoEnabled,boolean buildActive,boolean alreadyRequested) {
        return unchangedMs>=INITIAL_STALL_OBSERVATION_MS&&observed&&!unfinished
                &&!ownerActive&&!playbackActive&&autoEnabled&&buildActive&&!alreadyRequested;
    }

    // Reuse the existing exclusive owner for up to four independently committed
    // idle slices. Admit a successor only in the first two minutes: with the
    // existing 240s slice guard this leaves substantial WorkManager time margin.
    // Each slice rechecks playback, cancellation, network and durable progress.
    static final int INITIAL_INLINE_MAX_SLICES=4;
    static final long INITIAL_INLINE_ADMISSION_MS=120000L;
    static boolean canContinueInitialInline(int completedSlices,long elapsedMs,
            boolean viewing,boolean playbackActive,boolean paused,boolean progressed,long delaySeconds) {
        return completedSlices>=1&&completedSlices<INITIAL_INLINE_MAX_SLICES
                &&elapsedMs>=0L&&elapsedMs<INITIAL_INLINE_ADMISSION_MS
                &&!viewing&&!playbackActive&&!paused&&progressed&&delaySeconds==0L;
    }

    /** Continue immediately only after a successful committed discovery advance.
     * Keep the existing delay for failed/empty-progress slices to avoid retry churn. */
    static long manualContinuationDelaySeconds(boolean feedRead,int discoveryScanned,boolean pageComplete) {
        return feedRead&&(discoveryScanned>0||pageComplete)?0L:10L;
    }

    /** New-feed scanning and the bounded saved-route pass have separate checkpoints. */
    static int manualSavedWindow(int poolSize,int covered,boolean savedOnly) {
        return Math.min(savedOnly?DiscoveryBatchPolicy.MANUAL_PAGE_SIZE:12,
                Math.max(0,poolSize-Math.max(0,covered)));
    }
    static boolean manualRoundComplete(boolean savedOnly,int nextFeed,int feedCount,int covered,int poolSize) {
        return savedOnly&&feedCount>=0&&nextFeed>=feedCount&&poolSize>=0&&covered>=poolSize;
    }

    /** Clear an earlier unavailable-feed warning only at the end of a
     * nonempty, successfully scanned indexed directory. A readable first page
     * or a terminal failure is not completion of the recovery pass. */
    static boolean recoveredDirectoryScanned(boolean success,boolean unavailable,
            int cursor,int parsed,int indexedEntries) {
        return success&&!unavailable&&cursor>=0&&parsed>=0&&indexedEntries>0
                &&(long)cursor+parsed==indexedEntries;
    }

    /** First-build inspection can finish with an explicit unavailable outcome,
     * but only a successfully read page may supply EOF evidence. */
    static boolean initialFeedInspected(boolean acceptedOutcome,boolean unavailable,
            int cursor,int parsed,int indexedEntries,int pageSize) {
        return acceptedOutcome&&(unavailable||manualFeedFinished(cursor,parsed,indexedEntries,pageSize));
    }

    /** Manual refresh advances only at EOF, never just because a page was processed. */
    public static boolean manualFeedFinished(int cursor, int parsed, int indexedEntries,
            int pageSize) {
        if (cursor < 0 || parsed < 0 || pageSize <= 0) return false;
        return indexedEntries >= 0
                ? (long)cursor + parsed >= indexedEntries
                : parsed < pageSize;
    }

    /** A user-requested manual refresh checks saved routes while browsing too.
     * Playback admission is enforced separately, before pool construction. */
    static boolean shouldBuildSavedProbePool(boolean initial,boolean manual,boolean foreground) {
        return !initial&&(manual||!foreground);
    }

    /** Only a completed first rebuild enables low-priority routine maintenance. */
    public static boolean shouldScheduleRoutineMaintenance(boolean automaticEnabled,
            long fullRebuildCompletedAtMillis) {
        return automaticEnabled && fullRebuildCompletedAtMillis > 0L;
    }

    /**
     * A routine busy check is skipped until the next daily eligibility check. Never build a 10-minute retry loop while the TV is busy.
     * First-run and explicitly requested work should retain their queued intent.
     */
    public static boolean shouldRetryWhenBusy(boolean manual, boolean initialDiscoveryChain) {
        return manual || initialDiscoveryChain;
    }

    /** A retried predecessor may already have committed its successor in WorkManager.
     * Existing unfinished work owns the next slice; do not append another leaf. */
    public static boolean hasOtherUnfinishedWork(java.util.UUID currentId,
            java.util.Collection<java.util.UUID> unfinishedIds) {
        if (unfinishedIds==null) return false;
        for (java.util.UUID id:unfinishedIds)
            if (id!=null&&!id.equals(currentId)) return true;
        return false;
    }

    /** Retry a cooperative yield without the full viewing delay once playback
     * is inactive. A generic yield gets a longer quiet window than known input;
     * the next worker still rechecks all playback and concurrency gates. */
    static long initialPressureDelaySeconds(boolean playbackActive,boolean idleWaveInterrupted) {
        return playbackActive?30L:idleWaveInterrupted?5L:10L;
    }

    /** Normal viewing deferral is not a failed network attempt. */
    public static long initialViewingDelaySeconds(boolean stableButWaitingForIdle) {
        return stableButWaitingForIdle ? 60L : 30L;
    }

    /** Automatic catalogue work must not compete with foreground TV playback or input. */
    public static boolean shouldDeferForegroundAutoUpdate(boolean appForeground, boolean manual) {
        return appForeground && !manual;
    }

    /**
     * First-run setup must advance even when seed or previously saved channels exist.
     * Playback activity is checked separately by the worker before this gate.
     */
    public static boolean shouldDeferForegroundDiscovery(boolean appForeground,
            boolean manual, boolean initialDiscoveryChain, boolean hasPublishedChannels) {
        // Seed channels are not evidence of a completed initial rebuild.
        // Keep routine automatic maintenance idle-only, but allow initialization
        // or explicit manual work while browsing, regardless of catalogue size.
        return appForeground && !manual && !initialDiscoveryChain;
    }

    /** A first-run rebuild is complete only after every candidate and feed was covered. */
    public static boolean isInitialDiscoveryComplete(int candidateCount, int scannedCount,
                                                      int failedFeedCount) {
        return candidateCount > 0 && scannedCount >= candidateCount && failedFeedCount == 0;
    }

    /** At least one newly discovered source must pass the media preflight. */
    public static boolean hasValidatedDiscoverySources(int validatedSourceCount) {
        return validatedSourceCount > 0;
    }
}
