package com.wodedianshi.tv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure M3U parser shared by discovery and deterministic unit tests. */
public final class PlaylistParser {
    private static final Pattern TVG_NAME = Pattern.compile("(?i)(?:^|\\s)tvg-name\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))");
    private static final Pattern TVG_ID = Pattern.compile("(?i)(?:^|\\s)tvg-id\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))");
    private static final Pattern GROUP_TITLE = Pattern.compile("(?i)(?:^|\\s)group-title\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))");

    private PlaylistParser() {}

    public static final class ChannelSource {
        public final String name;
        public final String url;
        public final String group;
        public final String tvgId;
        public final String tvgName;

        private ChannelSource(String name, String url, String group, String tvgId, String tvgName) {
            this.name = name;
            this.url = url;
            this.group = group;
            this.tvgId = tvgId;
            this.tvgName = tvgName;
        }
    }

    public interface EntryConsumer {
        void accept(ChannelSource entry);
    }

    /** Recreate a parser record from the private on-disk playlist index. */
    public static ChannelSource indexedSource(String name, String url, String group,
                                              String tvgId, String tvgName) {
        return new ChannelSource(name, url, group, tvgId, tvgName);
    }

    public static List<ChannelSource> parse(String data, int maxEntries) {
        if (data == null || data.isEmpty() || maxEntries <= 0) return Collections.emptyList();
        ArrayList<ChannelSource> result = new ArrayList<>();
        parseEach(data, maxEntries, result::add);
        return result;
    }

    /** Compatibility overload for callers that already have an in-memory string. */
    public static int parseEach(String data, int maxEntries, EntryConsumer consumer) {
        if (data == null || data.isEmpty() || maxEntries <= 0 || consumer == null) return 0;
        try {
            return parseEach(new StringReader(data), maxEntries, consumer);
        } catch (IOException impossible) {
            // StringReader never performs I/O; keep the historical no-throws API.
            throw new IllegalStateException("Unexpected StringReader failure", impossible);
        }
    }

    /**
     * Parse directly from a stream without materializing the complete playlist or
     * a split-line array. The caller owns and closes the Reader.
     */
    public static int parseEach(Reader source, int maxEntries, EntryConsumer consumer)
            throws IOException {
        return parseEach(source, 0, maxEntries, consumer);
    }

    /** Stream a bounded page from a playlist; skipEntries counts valid HTTP entries. */
    public static int parseEach(Reader source, int skipEntries, int maxEntries,
                                EntryConsumer consumer) throws IOException {
        return parseEach(source, skipEntries, maxEntries, consumer, () -> false);
    }

    public static int parseEach(Reader source, int skipEntries, int maxEntries,
                                EntryConsumer consumer, java.util.function.BooleanSupplier paused)
            throws IOException {
        if (source == null || skipEntries < 0 || maxEntries <= 0 || consumer == null) return 0;
        SourceProbeTimePolicy.checkPaused(paused);
        BufferedReader reader = source instanceof BufferedReader
                ? (BufferedReader) source : new BufferedReader(source, 8192);
        String name = null;
        String group = "";
        String pendingGroup = "";
        boolean groupTitlePresent = false;
        String tvgId = "";
        String tvgName = "";
        ChannelSource lastEntry = null;
        int emitted = 0;
        int skipped = 0;
        String rawLine;
        while (emitted < maxEntries && (rawLine = reader.readLine()) != null) {
            SourceProbeTimePolicy.checkPaused(paused);
            String line = rawLine.replace("\uFEFF", "").trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTBURL:")) {
                // Some maintained IPTV aggregators publish backup URLs as a
                // non-standard tag immediately after the primary channel URL.
                // Emit them as separate candidates with the same channel identity
                // so the normal merge/quality policy can attach them as backups.
                String backupUrl = line.substring("#EXTBURL:".length()).trim();
                if (lastEntry != null && isHttpUrl(backupUrl)) {
                    ChannelSource backup = new ChannelSource(lastEntry.name, backupUrl,
                            lastEntry.group, lastEntry.tvgId, lastEntry.tvgName);
                    if (skipped < skipEntries) {
                        skipped++;
                    } else {
                        consumer.accept(backup);
                        emitted++;
                    }
                }
                continue;
            }
            if (line.startsWith("#EXTGRP:")) {
                String extGroup = line.substring(8).trim();
                if (name != null) {
                    if (!groupTitlePresent) group = extGroup;
                } else {
                    pendingGroup = extGroup;
                }
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                lastEntry = null;
                int comma = firstCommaOutsideQuotes(line);
                // The display name starts at the first unquoted comma. It is
                // not an attribute list and cannot supply EPG or group metadata.
                int attributeEnd = comma >= 0 ? comma : line.length();
                name = comma >= 0 ? line.substring(comma + 1).trim() : "";
                Matcher tvgNameMatcher = TVG_NAME.matcher(line).region(0,attributeEnd);
                tvgName = tvgNameMatcher.find() ? attributeValue(tvgNameMatcher).trim() : "";
                if (name.isEmpty()) name = tvgName;
                if (name.isEmpty()) name = "未命名频道";
                Matcher idMatcher = TVG_ID.matcher(line).region(0,attributeEnd);
                tvgId = idMatcher.find() ? attributeValue(idMatcher).trim() : "";
                group = pendingGroup;
                pendingGroup = "";
                groupTitlePresent = false;
                Matcher groupMatcher = GROUP_TITLE.matcher(line).region(0,attributeEnd);
                if (groupMatcher.find() && !attributeValue(groupMatcher).trim().isEmpty()) {
                    group = attributeValue(groupMatcher).trim();
                    groupTitlePresent = true;
                }
            } else if (!line.startsWith("#")
                    && (line.startsWith("http://") || line.startsWith("https://"))) {
                if (name != null && !name.trim().isEmpty()) {
                    lastEntry = new ChannelSource(name, line, group, tvgId, tvgName);
                    if (skipped < skipEntries) {
                        skipped++;
                    } else {
                        consumer.accept(lastEntry);
                        emitted++;
                    }
                }
                name = null;
                group = "";
                groupTitlePresent = false;
                tvgId = "";
                tvgName = "";
            }
        }
        SourceProbeTimePolicy.checkPaused(paused);
        return emitted;
    }

    private static String attributeValue(Matcher matcher) {
        String quoted = matcher.group(1);
        return quoted != null ? quoted : matcher.group(2);
    }

    private static boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private static int firstCommaOutsideQuotes(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) return i;
        }
        return -1;
    }
}
