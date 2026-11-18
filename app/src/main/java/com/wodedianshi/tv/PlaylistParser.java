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
    private static final Pattern TVG_NAME = Pattern.compile("tvg-name=\"([^\"]*)\"");
    private static final Pattern TVG_ID = Pattern.compile("tvg-id=\"([^\"]*)\"");
    private static final Pattern GROUP_TITLE = Pattern.compile("group-title=\"([^\"]*)\"");

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
        if (source == null || skipEntries < 0 || maxEntries <= 0 || consumer == null) return 0;
        BufferedReader reader = source instanceof BufferedReader
                ? (BufferedReader) source : new BufferedReader(source, 8192);
        String name = null;
        String group = "";
        String pendingGroup = "";
        boolean groupTitlePresent = false;
        String tvgId = "";
        String tvgName = "";
        int emitted = 0;
        int skipped = 0;
        String rawLine;
        while (emitted < maxEntries && (rawLine = reader.readLine()) != null) {
            String line = rawLine.replace("\uFEFF", "").trim();
            if (line.isEmpty()) continue;
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
                int comma = firstCommaOutsideQuotes(line);
                name = comma >= 0 ? line.substring(comma + 1).trim() : "";
                if (name.isEmpty()) {
                    Matcher matcher = TVG_NAME.matcher(line);
                    if (matcher.find()) name = matcher.group(1).trim();
                }
                if (name.isEmpty()) name = "未命名频道";
                Matcher idMatcher = TVG_ID.matcher(line);
                tvgId = idMatcher.find() ? idMatcher.group(1).trim() : "";
                Matcher tvgNameMatcher = TVG_NAME.matcher(line);
                tvgName = tvgNameMatcher.find() ? tvgNameMatcher.group(1).trim() : "";
                group = pendingGroup;
                pendingGroup = "";
                groupTitlePresent = false;
                Matcher groupMatcher = GROUP_TITLE.matcher(line);
                if (groupMatcher.find() && !groupMatcher.group(1).trim().isEmpty()) {
                    group = groupMatcher.group(1).trim();
                    groupTitlePresent = true;
                }
            } else if (!line.startsWith("#")
                    && (line.startsWith("http://") || line.startsWith("https://"))) {
                if (name != null && !name.trim().isEmpty()) {
                    if (skipped < skipEntries) {
                        skipped++;
                    } else {
                        consumer.accept(new ChannelSource(name, line, group, tvgId, tvgName));
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
        return emitted;
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
