package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Merges a prepared catalogue into the newest saved catalogue without replacing
 * user playback state. This class is deliberately Android-free so its policy
 * can be exercised by local JVM unit tests.
 */
final class ChannelCatalogueMergePolicy {
    static final int MAX_SOURCES_PER_CHANNEL = 5;

    static final class ChannelRecord {
        final String identity;
        final String name;
        final String group;
        final String primaryUrl;
        final List<String> sources;
        final int activeSource;

        ChannelRecord(String identity, String name, String group, String primaryUrl,
                      List<String> sources, int activeSource) {
            this.identity = clean(identity);
            this.name = clean(name);
            this.group = group == null ? "" : group;
            this.primaryUrl = clean(primaryUrl);
            this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
            this.activeSource = activeSource;
        }

        String activeUrl() {
            return activeSource >= 0 && activeSource < sources.size()
                    ? sources.get(activeSource) : primaryUrl;
        }
    }

    private ChannelCatalogueMergePolicy() {}

    /**
     * The current snapshot is always the base. Candidate metadata can add valid
     * channels and backup URLs, but cannot replace the current primary URL or
     * move the currently selected source. Returns null for an empty/invalid
     * candidate so callers can leave the active snapshot untouched.
     */
    static List<ChannelRecord> merge(List<ChannelRecord> current,
                                     List<ChannelRecord> candidate) {
        if (candidate == null || candidate.isEmpty()) return null;

        LinkedHashMap<String, ChannelRecord> merged = new LinkedHashMap<>();
        if (current != null) {
            for (ChannelRecord record : current) {
                if (record == null || record.identity.isEmpty() || record.name.isEmpty()
                        || !isHttp(record.primaryUrl)) continue;
                merged.put(record.identity, normalizeCurrent(record));
            }
        }

        int acceptedCandidateRecords = 0;
        for (ChannelRecord incoming : candidate) {
            if (incoming == null || incoming.identity.isEmpty() || incoming.name.isEmpty()) continue;
            LinkedHashSet<String> candidateUrls = validUrls(incoming.sources);
            if (isHttp(incoming.primaryUrl)) candidateUrls.add(incoming.primaryUrl);
            if (candidateUrls.isEmpty()) continue;

            ChannelRecord existing = merged.get(incoming.identity);
            if (existing == null) {
                ArrayList<String> sources = new ArrayList<>(candidateUrls);
                String primary = isHttp(incoming.primaryUrl) ? incoming.primaryUrl : sources.get(0);
                if (!sources.contains(primary)) sources.add(0, primary);
                if (sources.size() > MAX_SOURCES_PER_CHANNEL) {
                    sources = new ArrayList<>(sources.subList(0, MAX_SOURCES_PER_CHANNEL));
                }
                int active = indexOf(sources, incoming.activeUrl());
                if (active < 0) active = indexOf(sources, primary);
                merged.put(incoming.identity, new ChannelRecord(incoming.identity,
                        incoming.name, incoming.group, primary, sources, Math.max(0, active)));
            } else {
                ArrayList<String> sources = new ArrayList<>(existing.sources);
                for (String url : candidateUrls) {
                    if (sources.size() >= MAX_SOURCES_PER_CHANNEL) break;
                    if (!sources.contains(url)) sources.add(url);
                }
                String activeUrl = existing.activeUrl();
                int active = indexOf(sources, activeUrl);
                if (active < 0) active = indexOf(sources, existing.primaryUrl);
                merged.put(existing.identity, new ChannelRecord(existing.identity,
                        existing.name, existing.group, existing.primaryUrl, sources,
                        Math.max(0, active)));
            }
            acceptedCandidateRecords++;
        }

        if (acceptedCandidateRecords == 0 || merged.isEmpty()) return null;
        ArrayList<ChannelRecord> result = new ArrayList<>(merged.values());
        for (ChannelRecord record : result) {
            if (!isHttp(record.primaryUrl) || record.sources.isEmpty()
                    || !record.sources.contains(record.primaryUrl)
                    || record.activeSource < 0 || record.activeSource >= record.sources.size()) {
                return null;
            }
        }
        return result;
    }

    private static ChannelRecord normalizeCurrent(ChannelRecord record) {
        LinkedHashSet<String> urls = validUrls(record.sources);
        if (isHttp(record.primaryUrl)) urls.add(record.primaryUrl);
        ArrayList<String> sources = new ArrayList<>(urls);
        String activeUrl = record.activeUrl();
        int active = indexOf(sources, activeUrl);
        if (active < 0) active = indexOf(sources, record.primaryUrl);
        return new ChannelRecord(record.identity, record.name, record.group,
                record.primaryUrl, sources, Math.max(0, active));
    }

    private static LinkedHashSet<String> validUrls(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) for (String value : values) if (isHttp(value)) result.add(value);
        return result;
    }

    private static boolean isHttp(String value) {
        if (value == null) return false;
        String url = value.trim().toLowerCase(java.util.Locale.ROOT);
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private static int indexOf(List<String> values, String target) {
        return target == null ? -1 : values.indexOf(target);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
