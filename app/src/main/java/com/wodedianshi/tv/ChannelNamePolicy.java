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

    private static final String[][] LOCAL_CITY_ALIASES = {
        {"北京", "BEIJING"}, {"天津", "TIANJIN"}, {"河北", "SHIJIAZHUANG", "TANGSHAN"},
        {"山西", "TAIYUAN", "DATONG"}, {"内蒙古", "HOHHOT", "BAOTOU"},
        {"辽宁", "SHENYANG", "DALIAN"}, {"吉林", "CHANGCHUN"}, {"黑龙江", "HARBIN"},
        {"上海", "SHANGHAI"}, {"江苏", "NANJING", "SUZHOU", "WUXI"},
        {"浙江", "HANGZHOU", "NINGBO", "WENZHOU"}, {"安徽", "HEFEI", "WUHU"},
        {"福建", "FUZHOU", "XIAMEN", "QUANZHOU", "ZHANGZHOU"},
        {"江西", "NANCHANG"}, {"山东", "JINAN", "QINGDAO"}, {"河南", "ZHENGZHOU", "LUOYANG"},
        {"湖北", "WUHAN"}, {"湖南", "CHANGSHA"}, {"广东", "GUANGZHOU", "SHENZHEN", "DONGGUAN"},
        {"广西", "NANNING", "GUILIN"}, {"海南", "HAIKOU", "SANYA"}, {"重庆", "CHONGQING"},
        {"四川", "CHENGDU"}, {"贵州", "GUIYANG"}, {"云南", "KUNMING"},
        {"西藏", "LHASA"}, {"陕西", "XIAN"}, {"甘肃", "LANZHOU"},
        {"青海", "XINING"}, {"宁夏", "YINCHUAN"}, {"新疆", "URUMQI"}
    };

    static String provinceForLocalChannel(String metadata) {
        String normalized = normalize(metadata);
        if (normalized.isEmpty()) return "";
        for (String[] aliases : LOCAL_CITY_ALIASES) {
            for (int i = 1; i < aliases.length; i++) {
                if (normalized.contains(normalize(aliases[i]))) return aliases[0];
            }
        }
        return "";
    }

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
        return g.equals("SATELLITE") || g.equals("SATELLITETV") || g.equals("卫视");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9\\u4E00-\\u9FFF]", "");
    }
}
