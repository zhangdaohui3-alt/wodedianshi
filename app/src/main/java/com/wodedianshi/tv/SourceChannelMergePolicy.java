package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Adds a shared endpoint to a channel without losing its required or better sources. */
public final class SourceChannelMergePolicy {
    private SourceChannelMergePolicy() {}

    /**
     * The comparator must order better sources before worse sources.
     * Existing URLs are kept unless the channel is full and the candidate is better
     * than its current worst source. Endpoint aliases are deduplicated.
     */
    public static List<String> attachSharedEndpoint(List<String> current, String candidate,
            int limit, Comparator<String> bestFirst) {
        ArrayList<String> result = new ArrayList<>();
        if (limit <= 0) return result;
        HashSet<String> endpoints = new HashSet<>();
        if (current != null) {
            for (String url : current) {
                if (!isHttpUrl(url)) continue;
                String key = SourceSelectionPolicy.endpointKey(url);
                if (endpoints.add(key)) result.add(url);
            }
        }
        if (!isHttpUrl(candidate)) return result;
        String candidateKey = SourceSelectionPolicy.endpointKey(candidate);
        if (endpoints.contains(candidateKey)) return result;
        if (result.size() < limit) {
            result.add(candidate);
            return result;
        }
        if (bestFirst == null || result.isEmpty()) return result;
        int worstIndex = 0;
        for (int i = 1; i < result.size(); i++) {
            if (bestFirst.compare(result.get(i), result.get(worstIndex)) > 0) {
                worstIndex = i;
            }
        }
        if (bestFirst.compare(candidate, result.get(worstIndex)) < 0) {
            result.set(worstIndex, candidate);
        }
        return result;
    }

    private static boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }
}
