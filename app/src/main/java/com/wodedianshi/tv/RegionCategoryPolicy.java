package com.wodedianshi;

import java.util.Locale;
import java.util.regex.Pattern;

/** Strict region matching for Hong Kong, Macau and Taiwan channel categories. */
final class RegionCategoryPolicy {
    private static final Pattern HK = Pattern.compile("(^|[^a-z])hk([^a-z]|$)");
    private static final Pattern MO = Pattern.compile("(^|[^a-z])mo([^a-z]|$)");
    private static final Pattern TW = Pattern.compile("(^|[^a-z])tw([^a-z]|$)");

    private RegionCategoryPolicy() {}

    static boolean isHongKongMacauTaiwan(String name, String group) {
        return !subcategory(name, group).isEmpty();
    }

    /** Returns a dedicated child category, or 港澳台 when metadata is only generic. */
    static String subcategory(String name, String group) {
        String n = normalize(name);
        String g = normalize(group);
        if (n.contains("香港") || g.contains("香港") || n.equals("hong kong")
                || n.equals("hongkong") || g.equals("hong kong") || g.equals("hongkong")
                || HK.matcher(n).find() || HK.matcher(g).find()) return "香港";
        if (n.contains("澳门") || g.contains("澳门") || n.equals("macau") || n.equals("macao")
                || g.equals("macau") || g.equals("macao")
                || MO.matcher(n).find() || MO.matcher(g).find()) return "澳门";
        if (n.contains("台湾") || g.contains("台湾") || n.equals("taiwan") || g.equals("taiwan")
                || TW.matcher(n).find() || TW.matcher(g).find()) return "台湾";
        if (n.contains("港澳台") || g.contains("港澳台") || n.contains("港澳") || g.contains("港澳")
                || n.contains("港台") || g.contains("港台")) return "港澳台";
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('臺', '台').replace("澳門", "澳门");
    }
}
