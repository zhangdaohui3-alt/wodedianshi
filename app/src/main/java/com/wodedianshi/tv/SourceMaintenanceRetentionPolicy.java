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
        // Construct a comparator that makes only *eligible* old backups
        // replaceable. The existing merge helper also refuses to evict the
        // stable primary or the user's current route.
        Comparator<String> protectedWorstFirst = (a,b) -> {
            boolean aCanRetire = permittedToEvict.contains(SourceSelectionPolicy.endpointKey(a));
            boolean bCanRetire = permittedToEvict.contains(SourceSelectionPolicy.endpointKey(b));
            if (aCanRetire != bCanRetire) return aCanRetire ? 1 : -1;
            return bestFirst == null ? 0 : bestFirst.compare(a,b);
        };
        // If no existing backup is eligible, decline to evict anything.
        boolean canRetire = false;
        for (String url : old) {
            if (permittedToEvict.contains(SourceSelectionPolicy.endpointKey(url))
                    && !SourceSelectionPolicy.endpointKey(url).equals(SourceSelectionPolicy.endpointKey(primary))
                    && !SourceSelectionPolicy.endpointKey(url).equals(SourceSelectionPolicy.endpointKey(active))) {
                canRetire=true;break;
            }
        }
        if (!canRetire) return old;
        return SourceChannelMergePolicy.attachSharedEndpoint(
                old, verifiedCandidate, maxSources, protectedWorstFirst, primary, active);
    }
}
