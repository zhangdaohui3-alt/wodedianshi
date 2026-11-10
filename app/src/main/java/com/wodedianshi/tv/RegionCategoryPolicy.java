package com.wodedianshi.tv;

import java.util.Locale;
import java.util.regex.Pattern;

/** Strict region matching for Hong Kong, Macau and Taiwan channel categories. */
final class RegionCategoryPolicy {
    private static final Pattern HK = Pattern.compile("(^|[^a-z])hk([^a-z]|$)");
    private static final Pattern MO = Pattern.compile("(^|[^a-z])mo([^a-z]|$)");
    private static final Pattern TW = Pattern.compile("(^|[^a-z])tw([^a-z]|$)");

    private RegionCategoryPolicy() {}

    static boolean isHongKongMacauTaiwan(String name, String group) {
        String n = normalize(name);
        String g = normalize(group);
        if (containsRegionName(n) || containsRegionName(g)) return true;
        if (g.equals("hong kong") || g.equals("hongkong") || g.equals("macau")
                || g.equals("macao") || g.equals("taiwan")) return true;
        return HK.matcher(n).find() || HK.matcher(g).find()
                || MO.matcher(n).find() || MO.matcher(g).find()
                || TW.matcher(n).find() || TW.matcher(g).find();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('臺', '台').replace('灣', '湾').replace("澳門", "澳门");
    }

    private static boolean containsRegionName(String value) {
        return value.contains("香港") || value.contains("澳门")
                || value.contains("台湾") || value.contains("港澳台")
                || value.contains("港澳") || value.contains("港台");
    }
}
