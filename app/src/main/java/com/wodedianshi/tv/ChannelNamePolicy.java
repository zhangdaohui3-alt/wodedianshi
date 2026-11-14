package com.wodedianshi.tv;

import java.util.Locale;
import java.util.regex.Pattern;

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

    // English city aliases let international playlist metadata (tvg-name/group-title)
    // classify local Chinese stations even when the province name is omitted.
    private static final String[][] LOCAL_CITY_ALIASES = {
        {"北京", "BEIJING"}, {"天津", "TIANJIN"},
        {"河北", "SHIJIAZHUANG", "TANGSHAN", "QINHUANGDAO", "HANDAN", "XINGTAI", "BAODING", "ZHANGJIAKOU", "CHENGDE", "CANGZHOU", "LANGFANG", "HENGSHUI"},
        {"山西", "TAIYUAN", "DATONG", "SHUOZHOU", "XINZHOU", "YANGQUAN", "CHANGZHI", "JINCHENG", "JINZHONG", "YUNCHENG", "LINFEN", "LVLIANG"},
        {"内蒙古", "HOHHOT", "HUHHOT", "BAOTOU", "WUHAI", "CHIFENG", "TONGLIAO", "ORDOS", "HOHHOT", "HULUNBUIR", "BAYANNUUR", "ULANQAB"},
        {"辽宁", "SHENYANG", "DALIAN", "ANSHAN", "FUSHUN", "BENXI", "DANDONG", "JINZHOU", "YINGKOU", "FUXIN", "LIAOYANG", "PANJIN", "TIELING", "CHAOYANG", "HULUDAO"},
        {"吉林", "CHANGCHUN", "JILIN", "SIPING", "LIAOYUAN", "TONGHUA", "BAISHAN", "SONGYUAN", "BAICHENG", "YANBIAN"},
        {"黑龙江", "HARBIN", "QIQIHAR", "MUDANJIANG", "JIAMUSI", "DAQING", "JIXI", "HEGANG", "SHUANGYASHAN", "YICHUN", "QITAIHE", "HEIHE", "SUIHUA"},
        {"上海", "SHANGHAI"}, {"江苏", "NANJING", "SUZHOU", "WUXI", "CHANGZHOU", "NANTONG", "ZHENJIANG", "YANGZHOU", "HUAIAN", "LIANYUNGANG", "YANCHENG", "SUQIAN", "XUZHOU"},
        {"浙江", "HANGZHOU", "NINGBO", "WENZHOU", "JIAXING", "Huzhou", "SHAOXING", "JINHUA", "QUZHOU", "ZHOUSHAN", "LISHUI"},
        {"安徽", "HEFEI", "WUHU", "BENGBU", "HUAINAN", "MAANSHAN", "HUAIBEI", "TONGLING", "ANQING", "HUANGSHAN", "CHUZHOU", "Fuyang", "LIUAN", "BOZHOU", "CHIZHOU", "XUANCHENG"},
        {"福建", "FUZHOU", "XIAMEN", "PUTIAN", "SANMING", "QUANZHOU", "ZHANGZHOU", "NANPING", "LONGYAN", "NINGDE"},
        {"江西", "NANCHANG", "JINGDEZHEN", "PINGXIANG", "JIUJIANG", "XINYU", "YINGTAN", "GANZHOU", "JIAN", "YICHUN", "SHANGRAO"},
        {"山东", "JINAN", "QINGDAO", "ZIBO", "ZAOZHUANG", "DONGYING", "YANTAI", "WEIFANG", "JINING", "TAIAN", "WEIHAI", "RIZHAO", "LINYI", "DEZHOU", "LIAOCHENG", "BINZHOU", "HEZE"},
        {"河南", "ZHENGZHOU", "KAIFENG", "LUOYANG", "PINGDINGSHAN", "ANYANG", "HEBI", "XINXIANG", "JIAOZUO", "PUYANG", "XUCHANG", "LUOHE", "SANMENXIA", "NANYANG", "SHANGQIU", "XINYANG", "ZHOUKOU", "ZHUMADIAN"},
        {"湖北", "WUHAN", "HUANGSHI", "SHIYAN", "YICHANG", "XIANGYANG", "EZHOU", "JINGMEN", "XIAOGAN", "JINGZHOU", "HUANGGANG", "XIANNING", "SUIZHOU", "ENSHI"},
        {"湖南", "CHANGSHA", "ZHUZHOU", "XIANGTAN", "HENGYANG", "SHAOYANG", "YUEYANG", "CHANGDE", "ZHANGJIAJIE", "YIYANG", "CHENZHOU", "YONGZHOU", "HUAIHUA", "LOUDI"},
        {"广东", "GUANGZHOU", "SHENZHEN", "ZHUHAI", "SHANTOU", "FOSHAN", "SHAOGUAN", "ZHANJIANG", "ZHAOQING", "JIANGMEN", "MAOMING", "HUIZHOU", "MEIZHOU", "SHANWEI", "HEYUAN", "YANGJIANG", "QINGYUAN", "DONGGUAN", "ZHONGSHAN", "CHAOZHOU", "JIEYANG", "YUNFU"},
        {"广西", "NANNING", "LIUZHOU", "GUILIN", "WUZHOU", "BEIHAI", "QINZHOU", "GUIGANG", "BAISE", "HEZHOU", "HECHI", "LAIBIN", "CHONGZUO"},
        {"海南", "HAIKOU", "SANYA", "DANZHOU"}, {"重庆", "CHONGQING"},
        {"四川", "CHENGDU", "ZIGONG", "PZH", "LUZHOU", "DEYANG", "MIANYANG", "GUANGYUAN", "SUINING", "NEIJIANG", "LESHAN", "NANCHONG", "MEISHAN", "YIBIN", "GUANGAN", "DAZHOU", "YAAN", "BAZHONG", "Ziyang"},
        {"贵州", "GUIYANG", "LIUPANSHUI", "ZUNYI", "ANSHUN", "BIJIE", "TONGREN"},
        {"云南", "KUNMING", "QUJING", "YUXI", "BAOSHAN", "ZHAOTONG", "LIJIANG", "PUER", "LINCANG", "CHUXIONG", "HONGHE", "WENSHAN", "DALI"},
        {"西藏", "LHASA", "SHIGATSE", "QAMDO", "NYINGCHI", "SHANNAN", "NAGQU"},
        {"陕西", "XIAN", "TONGCHUAN", "BAOJI", "XIANYANG", "WEINAN", "YANAN", "HANZHONG", "ANKANG", "SHANGLUO"},
        {"甘肃", "LANZHOU", "JIAYUGUAN", "JINCHANG", "BAIYIN", "TIANSHUI", "Wuwei", "ZHANGYE", "PINGLIANG", "JIUQUAN", "QINGYANG", "DINGXI", "LONGNAN"},
        {"青海", "XINING", "HAIDONG", "HAIBEI", "HUANGNAN", "GUOLUO", "YUSHU", "HAIXI"},
        {"宁夏", "YINCHUAN", "SHIZUISHAN", "WUZHONG", "GUYUAN", "ZHONGWEI"},
        {"新疆", "URUMQI", "KARAMAY", "TURPAN", "HAMI", "KASHGAR", "HOTAN", "AKSU", "KORLA", "YINING", "ALTAY"}
    };

    // These names exist in more than one province. Without an explicit province
    // marker in the metadata, a city-only station name must not be guessed.
    private static final String[] AMBIGUOUS_LOCAL_CITY_ALIASES = {
        "TAIZHOU", "YULIN", "SUZHOU", "FUZHOU"
    };

    static String provinceForLocalChannel(String metadata) {
        String normalized = normalize(metadata);
        if (normalized.isEmpty()) return "";
        String matchedProvince = "";
        for (String[] aliases : LOCAL_CITY_ALIASES) {
            for (int i = 1; i < aliases.length; i++) {
                String alias = normalize(aliases[i]);
                if (!containsAlias(metadata, aliases[i], normalized)) continue;
                for (String ambiguous : AMBIGUOUS_LOCAL_CITY_ALIASES) {
                    if (alias.equals(ambiguous)) return "";
                }
                if (!matchedProvince.isEmpty() && !matchedProvince.equals(aliases[0])) return "";
                matchedProvince = aliases[0];
            }
        }
        return matchedProvince;
    }

    private static boolean containsAlias(String metadata, String alias, String normalizedMetadata) {
        String normalizedAlias = normalize(alias);
        if (normalizedAlias.isEmpty()) return false;
        // Chinese names are naturally contiguous. English city names must be
        // whole tokens so "Xiangyang" cannot accidentally match "Xian".
        if (normalizedAlias.matches(".*[\\u4E00-\\u9FFF].*")) {
            return normalizedMetadata.contains(normalizedAlias);
        }
        String upper = metadata == null ? "" : metadata.toUpperCase(Locale.ROOT);
        Pattern pattern = Pattern.compile("(^|[^A-Z])" + Pattern.quote(normalizedAlias) + "([^A-Z]|$)");
        return pattern.matcher(upper).find();
    }

    /** Resolve broadcaster identity from all M3U metadata, not just the display label. */
    static String canonicalBroadcasterName(String name, String tvgName, String tvgId, String group) {
        String metadata = ((name == null ? "" : name) + " "
                + (tvgName == null ? "" : tvgName) + " "
                + (tvgId == null ? "" : tvgId) + " "
                + (group == null ? "" : group)).trim();
        String satellite = canonicalSatelliteName(metadata);
        if (!satellite.isEmpty()) return satellite;
        String value = metadata.toUpperCase(Locale.ROOT)
                .replaceAll("[\\s_—–·•]", "");
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?:CCTV|中央电视台|央视)[-]?(\\d{1,2}\\+?|NEWS|4K|8K)")
                .matcher(value);
        if (matcher.find()) return "CCTV-" + matcher.group(1);
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
