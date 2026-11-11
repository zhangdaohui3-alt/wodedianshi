package com.wodedianshi.tv;

import java.net.URI;
import java.util.Locale;

/** Conservative channel naming and identity rules shared by source discovery. */
public final class ChannelIdentityPolicy {
    private ChannelIdentityPolicy() {}

    public static String canonicalName(String name) {
        String value = name == null ? "" : name.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[\\s_—–·•]", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:CCTV|中央电视台|央视)-?(\\d{1,2}\\+?|NEWS|4K|8K)")
                .matcher(value);
        if (matcher.find()) return "CCTV-" + matcher.group(1);

        String[] chineseNumbers = {"", "一", "二", "三", "四", "五", "六", "七",
                "八", "九", "十", "十一", "十二", "十三", "十四", "十五", "十六", "十七"};
        for (int i = 1; i < chineseNumbers.length; i++) {
            String number = chineseNumbers[i];
            if (value.matches(".*(?:中央(?:电视台)?|" + "央视)" + number + "套.*")) {
                return "CCTV-" + i;
            }
        }
        if (value.contains("中央电视台综合频道")) return "CCTV-1";
        if (value.contains("中央电视台财经频道")) return "CCTV-2";
        if (value.contains("中央电视台综艺频道")) return "CCTV-3";
        if (value.contains("中央电视台中文国际频道")) return "CCTV-4";
        if (value.contains("中央电视台体育频道")) return "CCTV-5";
        if (value.contains("中央电视台电影频道")) return "CCTV-6";

        value = value.replace("超高清", "").replace("高清", "").replace("超清", "")
                .replace("1080P", "").replace("720P", "").replace("HD", "")
                .replace("FHD", "").replace("直播", "");
        if (value.endsWith("湖南台")) return "湖南卫视";
        if (value.endsWith("浙江台")) return "浙江卫视";
        if (value.endsWith("江苏台")) return "江苏卫视";
        if (value.endsWith("东方台")) return "东方卫视";
        if (value.endsWith("北京台")) return "北京卫视";
        if (value.endsWith("广东台")) return "广东卫视";
        return value;
    }

    public static String identity(String name, String group, String url) {
        String canonical = canonicalName(name);
        if (canonical.startsWith("CCTV-")) {
            return canonical.toLowerCase(Locale.ROOT) + "|cctv";
        }
        if (canonical.contains("卫视")) {
            return canonical.toLowerCase(Locale.ROOT) + "|satellite";
        }
        String base = canonical.toLowerCase(Locale.ROOT) + "|"
                + (group == null ? "" : group.trim().toLowerCase(Locale.ROOT));
        try {
            URI uri = URI.create(url == null ? "" : url);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                return base + "|host:" + host.toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
            // Preserve the original URL as a last-resort identity when it is malformed.
        }
        return base + "|url:" + (url == null ? "" : url.trim());
    }
}
