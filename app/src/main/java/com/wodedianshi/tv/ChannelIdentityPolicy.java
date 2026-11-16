package com.wodedianshi.tv;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared channel identity rules for the foreground catalogue and background discovery.
 * Recognized CCTV and satellite aliases merge across source hosts; generic/local names
 * remain host-scoped to avoid merging unrelated stations with the same display label.
 */
public final class ChannelIdentityPolicy {
    private ChannelIdentityPolicy() { }

    public static String key(String name, String tvgName, String tvgId, String group, String url) {
        String broadcaster = ChannelNamePolicy.canonicalBroadcasterName(name, tvgName, tvgId, group);
        if (broadcaster.startsWith("CCTV-")) return broadcaster + "|cctv";
        if (broadcaster.endsWith("卫视")) return broadcaster.toLowerCase(Locale.ROOT) + "|satellite";

        String canonical = canonicalChannelName(name);
        if (canonical.startsWith("CCTV-")) return canonical + "|cctv";
        if (canonical.contains("卫视")) return canonical.toLowerCase(Locale.ROOT) + "|satellite";

        String base = canonical.toLowerCase(Locale.ROOT) + "|"
                + (group == null ? "" : group.trim().toLowerCase(Locale.ROOT));
        try {
            URI parsed = URI.create(url == null ? "" : url);
            String host = parsed.getHost();
            if (host != null && !host.isEmpty()) return base + "|host:" + host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) { }
        return base + "|url:" + (url == null ? "" : url.trim());
    }

    /** Canonical display-name normalization shared by all identity callers. */
    public static String canonicalChannelName(String name) {
        String satellite = ChannelNamePolicy.canonicalSatelliteName(name);
        if (!satellite.isEmpty()) return satellite;
        String value = name == null ? "" : name.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[\\s_—–·•]", "");
        Matcher matcher = Pattern.compile("(?:CCTV|中央电视台|央视)[-]?(\\d{1,2}\\+?|NEWS|4K|8K)")
                .matcher(value);
        if (matcher.find()) return "CCTV-" + matcher.group(1);
        String[] chinese = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
                "十一", "十二", "十三", "十四", "十五", "十六", "十七"};
        for (int i = 1; i < chinese.length; i++) {
            if (value.matches(".*中央(?:电视台)?" + chinese[i] + "套.*")) return "CCTV-" + i;
        }
        if (value.contains("中央电视台综合频道")) return "CCTV-1";
        if (value.contains("中央电视台财经频道")) return "CCTV-2";
        if (value.contains("中央电视台综艺频道")) return "CCTV-3";
        if (value.contains("中央电视台中文国际频道")) return "CCTV-4";
        if (value.contains("中央电视台体育频道")) return "CCTV-5";
        if (value.contains("中央电视台电影频道")) return "CCTV-6";
        value = value.replace("超高清", "").replace("高清", "").replace("超清", "")
                .replace("1080P", "").replace("720P", "").replace("4K高清", "")
                .replace("HD", "").replace("FHD", "").replace("直播", "")
                .replace("频道直播", "");
        if (value.endsWith("湖南台")) return "湖南卫视";
        if (value.endsWith("浙江台")) return "浙江卫视";
        if (value.endsWith("江苏台")) return "江苏卫视";
        if (value.endsWith("东方台")) return "东方卫视";
        if (value.endsWith("北京台")) return "北京卫视";
        if (value.endsWith("广东台")) return "广东卫视";
        return value;
    }
}
