package com.wodedianshi.tv;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative title identity for merging duplicate catalogue entries. */
public final class VodItemTitleIdentity {
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:18|19|20)\\d{2})(?!\\d)");
    private VodItemTitleIdentity() {}

    public static String key(String title) {
        if (title == null) return "";
        String value = title.trim();
        Matcher yearMatcher = YEAR.matcher(value);
        String year = yearMatcher.find() ? yearMatcher.group(1) : "unknown-year";

        value = value.replaceAll("(?i)(?<![a-z0-9])(?:1080p|720p|4k|2160p|hd|fhd)(?![a-z0-9])|高清|超清|超高清|国语|粤语|中字", " ");
        value = value.replaceAll("\\((?!(?:18|19|20)\\d{2}\\))[^)]*\\)|（[^）]*）|\\[[^]]*\\]|【[^】]*】", " ");
        value = value.replaceAll("(?<!\\d)(?:18|19|20)\\d{2}(?!\\d)", " ");
        value = value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{Z}\\s]+", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");
        return value.isEmpty() ? "" : value + "|" + year;
    }
}
