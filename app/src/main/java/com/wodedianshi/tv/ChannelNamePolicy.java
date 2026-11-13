package com.wodedianshi.tv;

import java.util.Locale;

/** Canonical Chinese names for well-known provincial satellite channels. */
final class ChannelNamePolicy {
    private ChannelNamePolicy() {}

    private static final String[][] SATELLITE_ALIASES = {
        {"湖南卫视", "HUNANSATELLITETV", "HUNANTV", "HUNANTELEVISION"},
        {"浙江卫视", "ZHEJIANGSATELLITETV", "ZHEJIANGTV", "ZHEJIANGTELEVISION"},
        {"江苏卫视", "JIANGSUSATELLITETV", "JIANGSUTV", "JIANGSUTELEVISION"},
        {"东方卫视", "DRAGONTV", "SHANGHAISATELLITETV", "ORIENTALTV"},
        {"北京卫视", "BEIJINGSATELLITETV", "BTVSATELLITE"},
        {"广东卫视", "GUANGDONGSATELLITETV", "GUANGDONGSATELLITETELEVISION"},
        {"深圳卫视", "SHENZHENSATELLITETV", "SHENZHENSATELLITE"},
        {"四川卫视", "SICHUANSATELLITETV"},
        {"安徽卫视", "ANHUISATELLITETV"},
        {"山东卫视", "SHANDONGSATELLITETV"},
        {"河南卫视", "HENANSATELLITETV"},
        {"湖北卫视", "HUBEISATELLITETV"},
        {"辽宁卫视", "LIAONINGSATELLITETV"},
        {"吉林卫视", "JILINSATELLITETV"},
        {"黑龙江卫视", "HEILONGJIANGSATELLITETV"},
        {"江西卫视", "JIANGXISATELLITETV"},
        {"福建东南卫视", "SOUTHEASTSATELLITETV", "FUJIANSATELLITETV"},
        {"广西卫视", "GUANGXISATELLITETV"},
        {"云南卫视", "YUNNANSATELLITETV"},
        {"贵州卫视", "GUIZHOUSATELLITETV"},
        {"重庆卫视", "CHONGQINGSATELLITETV"},
        {"天津卫视", "TIANJINSATELLITETV"},
        {"海南卫视", "HAINANSATELLITETV"},
        {"甘肃卫视", "GANSUSATELLITETV"},
        {"青海卫视", "QINGHAISATELLITETV"},
        {"宁夏卫视", "NINGXIASATELLITETV"},
        {"新疆卫视", "XINJIANGSATELLITETV"},
        {"内蒙古卫视", "INNERMONGOLIASATELLITETV"},
        {"山西卫视", "SHANXISATELLITETV"},
        {"陕西卫视", "SHAANXISATELLITETV"},
        {"西藏卫视", "TIBETSATELLITETV"}
    };

    static String canonicalSatelliteName(String name) {
        String normalized = normalize(name);
        if (normalized.isEmpty()) return "";
        for (String[] aliases : SATELLITE_ALIASES) {
            for (int i = 1; i < aliases.length; i++) {
                if (normalized.contains(normalize(aliases[i]))) return aliases[0];
            }
        }
        return name != null && name.contains("卫视") ? name.trim() : "";
    }

    static boolean isSatellite(String name, String group) {
        String n = canonicalSatelliteName(name);
        if (!n.isEmpty()) return true;
        String g = normalize(group);
        return g.equals("SATELLITE") || g.equals("SATELLITE TV") || g.equals("卫视");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9\\u4E00-\\u9FFF]", "");
    }
}
