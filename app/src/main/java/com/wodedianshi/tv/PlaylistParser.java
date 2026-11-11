package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure M3U parser shared by discovery and deterministic unit tests. */
public final class PlaylistParser {
    private static final Pattern TVG_NAME = Pattern.compile("tvg-name=\"([^\"]+)\"");
    private static final Pattern GROUP_TITLE = Pattern.compile("group-title=\"([^\"]*)\"");
    private static final Pattern RESPONSE_TIME =
            Pattern.compile("response-time=\"(\\d+(?:\\.\\d+)?)\\s*(ms|s)?\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECLARED_HEIGHT = Pattern.compile("\\[(\\d{3,4})\\]");
    private static final Pattern DECLARED_STATUS = Pattern.compile("\\[([A-Za-z])\\]");

    private PlaylistParser() {}

    public static final class ChannelSource {
        public final String name;
        public final String url;
        public final String group;
        /** Height advertised by a directory, not verified by this device. */
        public final int declaredHeight;
        /** Response time reported by a directory, not this device's RTT. */
        public final long responseTimeMs;
        /** Opaque publisher status label; deliberately not treated as proof. */
        public final String declaredStatus;

        private ChannelSource(String name, String url, String group,
                              int declaredHeight, long responseTimeMs, String declaredStatus) {
            this.name = name;
            this.url = url;
            this.group = group;
            this.declaredHeight = declaredHeight;
            this.responseTimeMs = responseTimeMs;
            this.declaredStatus = declaredStatus;
        }
    }

    public static List<ChannelSource> parse(String data, int maxEntries) {
        if (data == null || data.isEmpty() || maxEntries <= 0) return Collections.emptyList();
        ArrayList<ChannelSource> result = new ArrayList<>();
        String name = null;
        String group = "";
        String pendingGroup = "";
        boolean groupTitlePresent = false;
        int declaredHeight = 0;
        long responseTimeMs = 0L;
        String declaredStatus = "";
        String normalized = data.replace("\uFEFF", "");
        String[] lines = normalized.split("\\r\\n|\\n|\\r");
        for (String raw : lines) {
            if (result.size() >= maxEntries) break;
            String line = raw.trim();
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
                group = pendingGroup;
                pendingGroup = "";
                groupTitlePresent = false;
                Matcher groupMatcher = GROUP_TITLE.matcher(line);
                if (groupMatcher.find() && !groupMatcher.group(1).trim().isEmpty()) {
                    group = groupMatcher.group(1).trim();
                    groupTitlePresent = true;
                }

                String tvgName = "";
                Matcher tvgMatcher = TVG_NAME.matcher(line);
                if (tvgMatcher.find()) tvgName = tvgMatcher.group(1);
                Matcher heightMatcher = DECLARED_HEIGHT.matcher(tvgName);
                if (heightMatcher.find()) {
                    try { declaredHeight = Integer.parseInt(heightMatcher.group(1)); }
                    catch (NumberFormatException ignored) { declaredHeight = 0; }
                }
                Matcher statusMatcher = DECLARED_STATUS.matcher(tvgName);
                if (statusMatcher.find()) declaredStatus = statusMatcher.group(1).toUpperCase(Locale.ROOT);
                Matcher responseMatcher = RESPONSE_TIME.matcher(line);
                if (responseMatcher.find()) {
                    try {
                        double value = Double.parseDouble(responseMatcher.group(1));
                        responseTimeMs = Math.max(0L, Math.round(
                                "s".equalsIgnoreCase(responseMatcher.group(2)) ? value * 1000.0d : value));
                    } catch (NumberFormatException ignored) { responseTimeMs = 0L; }
                }
            } else if (!line.startsWith("#")
                    && (line.startsWith("http://") || line.startsWith("https://"))) {
                if (name != null && !name.trim().isEmpty()) {
                    result.add(new ChannelSource(name, line, group,
                            declaredHeight, responseTimeMs, declaredStatus));
                }
                name = null;
                group = "";
                groupTitlePresent = false;
                declaredHeight = 0;
                responseTimeMs = 0L;
                declaredStatus = "";
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
