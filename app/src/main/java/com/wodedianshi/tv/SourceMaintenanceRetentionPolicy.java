package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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

    public static List<String> retainDuringRoutineUpdate(List<String> existing,
            String verifiedCandidate, int maxSources, Comparator<String> bestFirst,
            String primary, String active, Set<String> repeatedlyFailed) {
        List<String> old = SourceChannelMergePolicy.attachSharedEndpoint(
                existing, "", maxSources, bestFirst, primary, active);
        if (old.size() < maxSources) {
            return SourceChannelMergePolicy.attachSharedEndpoint(
                    old, verifiedCandidate, maxSources, bestFirst, primary, active);
        }
        HashSet<String> permittedToEvict = new HashSet<>();
        if (repeatedlyFailed != null) permittedToEvict.addAll(repeatedlyFailed);
        // If the channel is already full, replace only a backup that has
        // independently failed at least three probes and only if the newly
        // preflighted candidate actually ranks better. The pinned source and
        // stable primary are never retired automatically.
        int retireIndex=-1;
        for (int i=0;i<old.size();i++) {
            String url=old.get(i), key=SourceSelectionPolicy.endpointKey(url);
            if (!permittedToEvict.contains(key)
                    || key.equals(SourceSelectionPolicy.endpointKey(primary))
                    || key.equals(SourceSelectionPolicy.endpointKey(active))) continue;
            if (retireIndex<0 || bestFirst!=null
                    && bestFirst.compare(url,old.get(retireIndex))>0) retireIndex=i;
        }
        if (retireIndex<0 || bestFirst==null || verifiedCandidate==null
                || bestFirst.compare(verifiedCandidate,old.get(retireIndex))>=0) return old;
        // Deduplicate before replacing to avoid losing a source whose candidate
        // spelling differs from an already retained endpoint.
        String candidateKey=SourceSelectionPolicy.endpointKey(verifiedCandidate);
        for (String url:old)
            if (SourceSelectionPolicy.endpointKey(url).equals(candidateKey)) return old;
        ArrayList<String> updated=new ArrayList<>(old);
        updated.set(retireIndex,verifiedCandidate);
        return updated;
    }
}
