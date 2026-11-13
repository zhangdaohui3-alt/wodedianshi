package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Resolves an app channel label to an XMLTV channel without guessing when
 * multiple XMLTV channel IDs share the same normalized alias.
 *
 * The caller supplies the application's canonical-name policy so this class
 * can be tested independently and can share the same identity rules as live
 * source discovery once that policy is available in the consuming build.
 */
public final class XmlTvChannelMatcher {
    public interface NameNormalizer {
        String normalize(String name);
    }

    public enum Status { MATCHED, AMBIGUOUS, UNMATCHED }

    public static final class Match {
        public final Status status;
        public final XmlTvChannel channel;
        public final List<String> candidateIds;

        private Match(Status status, XmlTvChannel channel, List<String> candidateIds) {
            this.status = status;
            this.channel = channel;
            this.candidateIds = candidateIds;
        }
    }

    private XmlTvChannelMatcher() {}

    public static Match match(String appChannelName, XmlTvParser.Catalog catalog,
                              NameNormalizer normalizer) {
        if (catalog == null || normalizer == null) {
            return new Match(Status.UNMATCHED, null, new ArrayList<>());
        }
        String wanted = normalize(normalizer, appChannelName);
        if (wanted.isEmpty()) return new Match(Status.UNMATCHED, null, new ArrayList<>());

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (Map.Entry<String, XmlTvChannel> entry : catalog.channels.entrySet()) {
            XmlTvChannel channel = entry.getValue();
            if (wanted.equals(normalize(normalizer, channel.id))) {
                candidates.add(entry.getKey());
                continue;
            }
            for (String displayName : channel.displayNames) {
                if (wanted.equals(normalize(normalizer, displayName))) {
                    candidates.add(entry.getKey());
                    break;
                }
            }
        }

        List<String> ids = new ArrayList<>(candidates);
        if (ids.isEmpty()) return new Match(Status.UNMATCHED, null, ids);
        if (ids.size() > 1) return new Match(Status.AMBIGUOUS, null, ids);
        XmlTvChannel channel = catalog.channels.get(ids.get(0));
        return new Match(Status.MATCHED, channel, ids);
    }

    private static String normalize(NameNormalizer normalizer, String value) {
        if (value == null) return "";
        String result = normalizer.normalize(value);
        return result == null ? "" : result.trim();
    }
}
