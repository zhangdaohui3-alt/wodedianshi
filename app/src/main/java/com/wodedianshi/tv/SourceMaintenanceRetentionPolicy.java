package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The routine catalogue updater must never silently evict a saved source due
 * merely to one bad preflight. A verified new route replaces a full channel's
 * existing backup only after repeated independent probe failures.
 */
public final class SourceMaintenanceRetentionPolicy {
    private SourceMaintenanceRetentionPolicy() {}
    public static final int FAILURES_BEFORE_RETIREMENT = 3;

    /** Count independent failed saved-route probes; a success resets the streak. */
    public static int nextFailureStreak(int previous, boolean probeSucceeded) {
        if (probeSucceeded) return 0;
        if (previous >= FAILURES_BEFORE_RETIREMENT) return FAILURES_BEFORE_RETIREMENT;
        return Math.max(0, previous) + 1;
    }

    public static List<String> retainDuringRoutineUpdate(List<String> existing,
            String verifiedCandidate, int maxSources, Comparator<String> bestFirst,
            String primary, String active, Set<String> repeatedlyFailed) {
        // Normalize once; null comparator permits append but never replacement.
        List<String> old = SourceChannelMergePolicy.attachSharedEndpoint(
                existing, verifiedCandidate, maxSources, null, primary, active);
        if (old.size() < maxSources || bestFirst==null || verifiedCandidate==null
                || !(verifiedCandidate.startsWith("https://")
                      || verifiedCandidate.startsWith("http://"))) return old;
        String candidateKey=SourceSelectionPolicy.endpointKey(verifiedCandidate);
        for (String url:old)
            if (SourceSelectionPolicy.endpointKey(url).equals(candidateKey)) return old;
        Set<String> permittedToEvict = repeatedlyFailed==null?Collections.emptySet():repeatedlyFailed;
        // If the channel is already full, replace only a backup that has
        // independently failed at least three probes and only if the newly
        // preflighted candidate actually ranks better. The pinned source and
        // stable primary are never retired automatically.
        int retireIndex=retirementIndex(old,bestFirst,primary,active,permittedToEvict);
        if (retireIndex<0 || bestFirst.compare(verifiedCandidate,old.get(retireIndex))>=0) return old;
        ArrayList<String> updated=new ArrayList<>(old);
        updated.set(retireIndex,verifiedCandidate);
        return updated;
    }
    /** Shared selection of the worst eligible backup; callers own the commit
     * and can preserve additional protected endpoints by excluding them. */
    public static int retirementIndex(List<String> old, Comparator<String> bestFirst,
            String primary,String active,Set<String> permittedToEvict){
        if(permittedToEvict.isEmpty())return -1;
        String primaryKey=SourceSelectionPolicy.endpointKey(primary);
        String activeKey=SourceSelectionPolicy.endpointKey(active);
        int retireIndex=-1;
        for (int i=0;i<old.size();i++) {
            String url=old.get(i), key=SourceSelectionPolicy.endpointKey(url);
            if (!permittedToEvict.contains(key)
                    || key.equals(primaryKey) || key.equals(activeKey)) continue;
            if (retireIndex<0 || bestFirst!=null
                    && bestFirst.compare(url,old.get(retireIndex))>0) retireIndex=i;
        }
        return retireIndex;
    }

}
