package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure M3U parser shared by discovery and deterministic unit tests. */
public final class PlaylistParser {
    private static final Pattern TVG_NAME = Pattern.compile("tvg-name=\"([^\"]+)\"");
    private static final Pattern GROUP_TITLE = Pattern.compile("group-title=\"([^\"]*)\"");

    private PlaylistParser() {}

    public static final class ChannelSource {
        public final String name;
        public final String url;
        public final String group;

        private ChannelSource(String name, String url, String group) {
            this.name = name;
            this.url = url;
            this.group = group;
        }
    }

    public static List<ChannelSource> parse(String data, int maxEntries) {
        if (data == null || data.isEmpty() || maxEntries <= 0) return Collections.emptyList();
        ArrayList<ChannelSource> result = new ArrayList<>();
        String name = null;
        String group = "";
        String pendingGroup = "";
        String normalized = data.replace("\uFEFF", "");
        String[] lines = normalized.split("\\r\\n|\\n|\\r");
        for (String raw : lines) {
            if (result.size() >= maxEntries) break;
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTGRP:")) {
                pendingGroup = line.substring(8).trim();
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
                group = pendingGroup;
                pendingGroup = "";
                Matcher groupMatcher = GROUP_TITLE.matcher(line);
                if (groupMatcher.find() && !groupMatcher.group(1).trim().isEmpty()) {
                    group = groupMatcher.group(1).trim();
                }
            } else if (!line.startsWith("#")
                    && (line.startsWith("http://") || line.startsWith("https://"))) {
                if (name != null && !name.trim().isEmpty()) {
                    result.add(new ChannelSource(name, line, group));
                }
                name = null;
                group = "";
            }
        }
        return result;
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