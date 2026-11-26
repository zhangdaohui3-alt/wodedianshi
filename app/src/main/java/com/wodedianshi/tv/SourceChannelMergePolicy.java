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
        return attachSharedEndpoint(current, candidate, limit, bestFirst, "", "");
    }

    public static List<String> attachSharedEndpoint(List<String> current, String candidate,
            int limit, Comparator<String> bestFirst, String primaryUrl, String activeUrl) {
        ArrayList<String> result = new ArrayList<>();
        if (limit <= 0) return result;
        java.util.HashMap<String, Integer> endpointIndexes = new java.util.HashMap<>();
        if (current != null) {
            for (String url : current) {
                if (!isHttpUrl(url)) continue;
                String key = SourceSelectionPolicy.endpointKey(url);
                Integer existingIndex = endpointIndexes.get(key);
                if (existingIndex == null) {
                    endpointIndexes.put(key, result.size());
                    result.add(url);
                } else if (url.equals(primaryUrl) || url.equals(activeUrl)) {
                    // If two equivalent URL spellings exist, keep the exact signed/
                    // selected URL that the user or channel has already chosen.
                    result.set(existingIndex, url);
                }
            }
        }
        HashSet<String> endpoints = new HashSet<>(endpointIndexes.keySet());
        if (!isHttpUrl(candidate)) return result;
        String candidateKey = SourceSelectionPolicy.endpointKey(candidate);
        if (endpoints.contains(candidateKey)) return result;
        if (result.size() < limit) {
            result.add(candidate);
            return result;
        }
        if (bestFirst == null || result.isEmpty()) return result;
        String primaryKey = SourceSelectionPolicy.endpointKey(primaryUrl);
        String activeKey = SourceSelectionPolicy.endpointKey(activeUrl);
        int worstIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            String existingKey = SourceSelectionPolicy.endpointKey(result.get(i));
            if (existingKey.equals(primaryKey) || existingKey.equals(activeKey)) continue;
            if (worstIndex < 0
                    || bestFirst.compare(result.get(i), result.get(worstIndex)) > 0) {
                worstIndex = i;
            }
        }
        // Never evict the stable primary or the user's last selected route.
        if (worstIndex >= 0 && bestFirst.compare(candidate, result.get(worstIndex)) < 0) {
            result.set(worstIndex, candidate);
        }
        return result;
    }

    private static boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }
}
