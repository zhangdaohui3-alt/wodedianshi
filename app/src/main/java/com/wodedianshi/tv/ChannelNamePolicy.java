package com.wodedianshi.tv;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

/** Canonical Chinese names for well-known provincial satellite channels. */
final class ChannelNamePolicy {
    private ChannelNamePolicy() {}

    // Matchers run for every channel during catalogue filtering. Cache the
    // immutable whole-token patterns instead of recompiling one for each alias.
    private static final ConcurrentHashMap<String, Pattern> ENGLISH_LOCAL_PATTERNS =
            new ConcurrentHashMap<>();

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
        {"福建东南卫视", "福建卫视", "FUJIANWEISHI", "SOUTHEASTSATELLITETV", "FUJIANSATELLITETV"},
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
        {"河北", "石家庄", "唐山", "秦皇岛", "邯郸", "邢台", "保定", "张家口", "承德", "沧州", "廊坊", "衡水", "SHIJIAZHUANG", "TANGSHAN", "QINHUANGDAO", "HANDAN", "XINGTAI", "BAODING", "ZHANGJIAKOU", "CHENGDE", "CANGZHOU", "LANGFANG", "HENGSHUI"},
        {"山西", "太原", "大同", "朔州", "忻州", "阳泉", "长治", "晋城", "晋中", "运城", "临汾", "吕梁", "TAIYUAN", "DATONG", "SHUOZHOU", "XINZHOU", "YANGQUAN", "CHANGZHI", "JINCHENG", "JINZHONG", "YUNCHENG", "LINFEN", "LVLIANG"},
        {"内蒙古", "呼和浩特", "包头", "乌海", "赤峰", "通辽", "鄂尔多斯", "呼伦贝尔", "巴彦淖尔", "乌兰察布", "HOHHOT", "HUHHOT", "BAOTOU", "WUHAI", "CHIFENG", "TONGLIAO", "ORDOS", "HULUNBUIR", "BAYANNUUR", "ULANQAB", "兴安", "锡林郭勒", "阿拉善"},
        {"辽宁", "沈阳", "大连", "鞍山", "抚顺", "本溪", "丹东", "锦州", "营口", "阜新", "辽阳", "盘锦", "铁岭", "朝阳", "葫芦岛", "SHENYANG", "DALIAN", "ANSHAN", "FUSHUN", "BENXI", "DANDONG", "JINZHOU", "YINGKOU", "FUXIN", "LIAOYANG", "PANJIN", "TIELING", "CHAOYANG", "HULUDAO"},
        {"吉林", "长春", "吉林市", "四平", "辽源", "通化", "白山", "松原", "白城", "延边", "CHANGCHUN", "JILIN", "SIPING", "LIAOYUAN", "TONGHUA", "BAISHAN", "SONGYUAN", "BAICHENG", "YANBIAN"},
        {"黑龙江", "哈尔滨", "齐齐哈尔", "牡丹江", "佳木斯", "大庆", "鸡西", "鹤岗", "双鸭山", "伊春", "七台河", "黑河", "绥化", "HARBIN", "QIQIHAR", "MUDANJIANG", "JIAMUSI", "DAQING", "JIXI", "HEGANG", "SHUANGYASHAN", "YICHUN", "QITAIHE", "HEIHE", "SUIHUA", "大兴安岭"},
        {"上海", "SHANGHAI"}, {"江苏", "南京", "苏州", "无锡", "常州", "南通", "镇江", "扬州", "淮安", "连云港", "盐城", "宿迁", "徐州", "TAIZHOU", "NANJING", "SUZHOU", "WUXI", "CHANGZHOU", "NANTONG", "ZHENJIANG", "YANGZHOU", "HUAIAN", "LIANYUNGANG", "YANCHENG", "SUQIAN", "XUZHOU"},
        {"浙江", "杭州", "宁波", "温州", "嘉兴", "湖州", "绍兴", "金华", "衢州", "舟山", "丽水", "台州", "上虞", "余姚", "兰溪", "庆元", "开化", "文成", "新昌", "TAIZHOU", "HANGZHOU", "NINGBO", "WENZHOU", "JIAXING", "HUZHOU", "SHAOXING", "JINHUA", "QUZHOU", "ZHOUSHAN", "LISHUI", "SHANGYU", "YUYAO", "LANXI", "QINGYUAN", "KAIHUA", "WENCHENG", "XINCHANG"},
        {"安徽", "合肥", "芜湖", "蚌埠", "淮南", "马鞍山", "淮北", "铜陵", "安庆", "黄山", "滁州", "阜阳", "六安", "亳州", "池州", "宣城", "固镇", "宿州", "SUZHOU", "HEFEI", "WUHU", "BENGBU", "HUAINAN", "MAANSHAN", "HUAIBEI", "TONGLING", "ANQING", "HUANGSHAN", "CHUZHOU", "Fuyang", "LIUAN", "BOZHOU", "CHIZHOU", "XUANCHENG", "GUZHEN"},
        {"福建", "福州", "厦门", "莆田", "三明", "泉州", "漳州", "南平", "龙岩", "宁德", "FUZHOU", "XIAMEN", "PUTIAN", "SANMING", "QUANZHOU", "ZHANGZHOU", "NANPING", "LONGYAN", "NINGDE"},
        {"江西", "南昌", "景德镇", "萍乡", "九江", "新余", "鹰潭", "赣州", "吉安", "宜春", "抚州", "上饶", "FUZHOU", "NANCHANG", "JINGDEZHEN", "PINGXIANG", "JIUJIANG", "XINYU", "YINGTAN", "GANZHOU", "JIAN", "YICHUN", "SHANGRAO"},
        {"山东", "济南", "青岛", "淄博", "枣庄", "东营", "烟台", "潍坊", "济宁", "泰安", "威海", "日照", "临沂", "德州", "聊城", "滨州", "菏泽", "JINAN", "QINGDAO", "ZIBO", "ZAOZHUANG", "DONGYING", "YANTAI", "WEIFANG", "JINING", "TAIAN", "WEIHAI", "RIZHAO", "LINYI", "DEZHOU", "LIAOCHENG", "BINZHOU", "HEZE"},
        {"河南", "郑州", "开封", "洛阳", "平顶山", "安阳", "鹤壁", "新乡", "焦作", "濮阳", "许昌", "漯河", "三门峡", "南阳", "商丘", "信阳", "周口", "驻马店", "ZHENGZHOU", "KAIFENG", "LUOYANG", "PINGDINGSHAN", "ANYANG", "HEBI", "XINXIANG", "JIAOZUO", "PUYANG", "XUCHANG", "LUOHE", "SANMENXIA", "NANYANG", "SHANGQIU", "XINYANG", "ZHOUKOU", "ZHUMADIAN"},
        {"湖北", "武汉", "黄石", "十堰", "宜昌", "襄阳", "鄂州", "荆门", "孝感", "荆州", "黄冈", "咸宁", "随州", "恩施", "WUHAN", "HUANGSHI", "SHIYAN", "YICHANG", "XIANGYANG", "EZHOU", "JINGMEN", "XIAOGAN", "JINGZHOU", "HUANGGANG", "XIANNING", "SUIZHOU", "ENSHI"},
        {"湖南", "长沙", "株洲", "湘潭", "衡阳", "邵阳", "岳阳", "常德", "张家界", "益阳", "郴州", "永州", "怀化", "娄底", "CHANGSHA", "ZHUZHOU", "XIANGTAN", "HENGYANG", "SHAOYANG", "YUEYANG", "CHANGDE", "ZHANGJIAJIE", "YIYANG", "CHENZHOU", "YONGZHOU", "HUAIHUA", "LOUDI", "湘西"},
        {"广东", "广州", "深圳", "珠海", "汕头", "佛山", "韶关", "湛江", "肇庆", "江门", "茂名", "惠州", "梅州", "汕尾", "河源", "阳江", "清远", "东莞", "中山", "潮州", "揭阳", "云浮", "GUANGZHOU", "SHENZHEN", "ZHUHAI", "SHANTOU", "FOSHAN", "SHAOGUAN", "ZHANJIANG", "ZHAOQING", "JIANGMEN", "MAOMING", "HUIZHOU", "MEIZHOU", "SHANWEI", "HEYUAN", "YANGJIANG", "QINGYUAN", "DONGGUAN", "ZHONGSHAN", "CHAOZHOU", "JIEYANG", "YUNFU"},
        {"广西", "南宁", "柳州", "桂林", "梧州", "北海", "防城港", "钦州", "贵港", "玉林", "百色", "贺州", "河池", "来宾", "崇左", "YULIN", "NANNING", "LIUZHOU", "GUILIN", "WUZHOU", "BEIHAI", "QINZHOU", "GUIGANG", "BAISE", "HEZHOU", "HECHI", "LAIBIN", "CHONGZUO"},
        {"海南", "海口", "三亚", "儋州", "HAIKOU", "SANYA", "DANZHOU", "三沙"}, {"重庆", "CHONGQING"},
        {"四川", "成都", "自贡", "攀枝花", "泸州", "德阳", "绵阳", "广元", "遂宁", "内江", "乐山", "南充", "眉山", "宜宾", "广安", "达州", "雅安", "巴中", "资阳", "旺苍", "CHENGDU", "ZIGONG", "PZH", "LUZHOU", "DEYANG", "MIANYANG", "GUANGYUAN", "SUINING", "NEIJIANG", "LESHAN", "NANCHONG", "MEISHAN", "YIBIN", "GUANGAN", "DAZHOU", "YAAN", "BAZHONG", "ZIYANG", "WANGCANG", "阿坝", "甘孜", "凉山"},
        {"贵州", "贵阳", "六盘水", "遵义", "安顺", "毕节", "铜仁", "GUIYANG", "LIUPANSHUI", "ZUNYI", "ANSHUN", "BIJIE", "TONGREN", "黔西南", "黔东南", "黔南"},
        {"云南", "昆明", "曲靖", "玉溪", "保山", "昭通", "丽江", "普洱", "临沧", "楚雄", "红河", "文山", "大理", "KUNMING", "QUJING", "YUXI", "BAOSHAN", "ZHAOTONG", "LIJIANG", "PUER", "LINCANG", "CHUXIONG", "HONGHE", "WENSHAN", "DALI", "西双版纳", "德宏", "怒江", "迪庆"},
        {"西藏", "LHASA", "SHIGATSE", "QAMDO", "NYINGCHI", "SHANNAN", "NAGQU", "拉萨", "日喀则", "昌都", "林芝", "山南", "那曲", "阿里"},
        {"陕西", "西安", "铜川", "宝鸡", "咸阳", "渭南", "延安", "汉中", "榆林", "安康", "商洛", "YULIN", "XIAN", "TONGCHUAN", "BAOJI", "XIANYANG", "WEINAN", "YANAN", "HANZHONG", "ANKANG", "SHANGLUO"},
        {"甘肃", "兰州", "嘉峪关", "金昌", "白银", "天水", "武威", "张掖", "平凉", "酒泉", "庆阳", "定西", "陇南", "LANZHOU", "JIAYUGUAN", "JINCHANG", "BAIYIN", "TIANSHUI", "Wuwei", "ZHANGYE", "PINGLIANG", "JIUQUAN", "QINGYANG", "DINGXI", "LONGNAN", "临夏", "甘南"},
        {"青海", "西宁", "海东", "海北", "黄南", "海南州", "果洛", "玉树", "海西", "XINING", "HAIDONG", "HAIBEI", "HUANGNAN", "GUOLUO", "YUSHU", "HAIXI"},
        {"宁夏", "银川", "石嘴山", "吴忠", "固原", "中卫", "YINCHUAN", "SHIZUISHAN", "WUZHONG", "GUYUAN", "ZHONGWEI"},
        {"新疆", "乌鲁木齐", "克拉玛依", "吐鲁番", "哈密", "喀什", "和田", "阿克苏", "库尔勒", "伊宁", "阿勒泰", "URUMQI", "KARAMAY", "TURPAN", "HAMI", "KASHGAR", "HOTAN", "AKSU", "KORLA", "YINING", "ALTAY", "昌吉", "博尔塔拉", "巴音郭楞", "克孜勒苏", "伊犁", "塔城", "兵团"}
    };

    // These names exist in more than one province. Without an explicit province
    // marker in the metadata, a city-only station name must not be guessed.
    // Pre-normalize static alias tables once. Filtering a 10k-channel catalogue
    // must not run the same replaceAll regex on every alias for every channel.
    private static final String[][] NORMALIZED_SATELLITE_ALIASES = normalizedAliases(SATELLITE_ALIASES);
    private static final String[][] NORMALIZED_LOCAL_CITY_ALIASES = normalizedAliases(LOCAL_CITY_ALIASES);
    private static final Pattern CCTV_ULTRA_HD = Pattern.compile(
            "(?:CCTV|中央电视台|央视)[-]?(4K|8K)");
    private static final Pattern CCTV_ASIA = Pattern.compile(
            "(?:CCTV|中央电视台|央视)[-]?4(?:ASIA|亚洲)");
    private static final Pattern CCTV_STANDARD = Pattern.compile(
            "(?:CCTV|中央电视台|央视)[-]?(\\d{1,2}\\+?|NEWS)");

    private static String[][] normalizedAliases(String[][] aliases) {
        String[][] result = new String[aliases.length][];
        for (int i = 0; i < aliases.length; i++) {
            result[i] = new String[aliases[i].length];
            for (int j = 0; j < aliases[i].length; j++) {
                result[i][j] = normalize(aliases[i][j]);
            }
        }
        return result;
    }

    private static final String[] AMBIGUOUS_LOCAL_CITY_ALIASES = {
        "TAIZHOU", "YULIN", "SUZHOU", "FUZHOU", "CHAOYANG", "YICHUN", "台州", "玉林", "苏州", "福州", "朝阳"
    };

    // Explicit province tokens are evaluated before city aliases, independently
    // of the order of the province table. Do not guess when explicit tokens conflict.
    private static final String[] PROVINCE_ENGLISH = {
        "BEIJING","TIANJIN","HEBEI","SHANXI","INNER MONGOLIA","LIAONING","JILIN",
        "HEILONGJIANG","SHANGHAI","JIANGSU","ZHEJIANG","ANHUI","FUJIAN","JIANGXI",
        "SHANDONG","HENAN","HUBEI","HUNAN","GUANGDONG","GUANGXI","HAINAN","CHONGQING",
        "SICHUAN","GUIZHOU","YUNNAN","TIBET","SHAANXI","GANSU","QINGHAI","NINGXIA","XINJIANG"
    };

    static String provinceForLocalChannel(String metadata) {
        String normalized = normalize(metadata);
        if (normalized.isEmpty()) return "";
        String upper = metadata.toUpperCase(Locale.ROOT);
        String explicit = "";
        for (int row = 0; row < LOCAL_CITY_ALIASES.length; row++) {
            String province = LOCAL_CITY_ALIASES[row][0];
            String english = PROVINCE_ENGLISH[row];
            if ((containsNormalizedAlias(upper, NORMALIZED_LOCAL_CITY_ALIASES[row][0], normalized)
                    && !("海南".equals(province) && normalized.contains("海南州")))
                    || (english.contains(" ") ? upper.contains(english)
                    : containsNormalizedAlias(upper, english, normalized))) {
                if (!explicit.isEmpty() && !explicit.equals(province)) return "";
                explicit = province;
            }
        }
        String inferred = "";
        boolean ambiguous = false;
        for (int row = 0; row < LOCAL_CITY_ALIASES.length; row++) {
            String province = LOCAL_CITY_ALIASES[row][0];
            String[] aliases = NORMALIZED_LOCAL_CITY_ALIASES[row];
            for (int i = 1; i < aliases.length; i++) {
                String alias = aliases[i];
                if (!containsNormalizedAlias(upper, alias, normalized)) continue;
                boolean shared = false;
                for (String candidate : AMBIGUOUS_LOCAL_CITY_ALIASES) {
                    if (alias.equals(candidate)) { shared = true; break; }
                }
                if (shared) { ambiguous = true; continue; }
                // A province marker is not evidence for a contradictory unique city.
                if (!explicit.isEmpty() && !explicit.equals(province)) return "";
                if (!inferred.isEmpty() && !inferred.equals(province)) return "";
                inferred = province;
            }
        }
        if (!explicit.isEmpty()) return explicit;
        return ambiguous ? "" : inferred;
    }

    // Explicitly paired city aliases, not positional guesses between unequal
    // Chinese and English alias arrays. Other cities still match Chinese metadata.
    private static final String[][] CITY_FILTER_ALIASES = {
        {"福建","福州","FUZHOU"},{"福建","厦门","XIAMEN"},{"福建","莆田","PUTIAN"},
        {"福建","三明","SANMING"},{"福建","泉州","QUANZHOU"},{"福建","漳州","ZHANGZHOU"},
        {"福建","南平","NANPING"},{"福建","龙岩","LONGYAN"},{"福建","宁德","NINGDE"},
        {"江西","抚州","FUZHOU"},{"江西","南昌","NANCHANG"},
        {"江苏","苏州","SUZHOU"},{"江苏","南京","NANJING"},{"江苏","无锡","WUXI"},
        {"江苏","泰州","TAIZHOU"},{"浙江","台州","TAIZHOU"},{"浙江","杭州","HANGZHOU"},
        {"浙江","宁波","NINGBO"},{"浙江","温州","WENZHOU"},{"浙江","绍兴","SHAOXING"},
        {"广东","广州","GUANGZHOU"},{"广东","深圳","SHENZHEN"},{"广东","珠海","ZHUHAI"},
        {"广东","佛山","FOSHAN"},{"四川","成都","CHENGDU"},{"四川","广元","GUANGYUAN"},
        {"湖北","武汉","WUHAN"},{"湖北","襄阳","XIANGYANG"},{"湖南","长沙","CHANGSHA"},
        {"山东","济南","JINAN"},{"山东","青岛","QINGDAO"},{"河南","郑州","ZHENGZHOU"},
        {"陕西","西安","XIAN"},{"陕西","榆林","YULIN"},{"广西","玉林","YULIN"},
        {"安徽","合肥","HEFEI"},{"安徽","宿州","SUZHOU"},{"辽宁","朝阳","CHAOYANG"},
        {"辽宁","沈阳","SHENYANG"},{"辽宁","大连","DALIAN"},{"河北","石家庄","SHIJIAZHUANG"},
        {"黑龙江","哈尔滨","HARBIN"},{"吉林","长春","CHANGCHUN"},{"云南","昆明","KUNMING"},
        {"贵州","贵阳","GUIYANG"},{"甘肃","兰州","LANZHOU"},{"青海","西宁","XINING"},
        {"宁夏","银川","YINCHUAN"},{"新疆","乌鲁木齐","URUMQI"},{"海南","海口","HAIKOU"},
        {"内蒙古","呼和浩特","HOHHOT"},{"西藏","拉萨","LHASA"}
    };

    static boolean matchesLocalCity(String metadata, String province, String city) {
        if (metadata == null || province == null || city == null || city.isEmpty()) return false;
        if (!province.equals(provinceForLocalChannel(metadata))) return false;
        String normalized = normalize(metadata);
        String upper = metadata.toUpperCase(Locale.ROOT);
        if (containsAlias(metadata, city, normalized)) return true;
        for (String[] pair : CITY_FILTER_ALIASES) {
            if (province.equals(pair[0]) && city.equals(pair[1])
                    && containsNormalizedAlias(upper, pair[2], normalized)) return true;
        }
        return false;
    }

    static String localCityForChannel(String metadata) {
        String province = provinceForLocalChannel(metadata);
        if (province.isEmpty()) return "";
        String normalized = normalize(metadata);
        final String uppercaseMetadata = metadata == null ? "" : metadata.toUpperCase(Locale.ROOT);
        // Municipalities are both province-level regions and city identities.
        if (("北京".equals(province) || "天津".equals(province)
                || "上海".equals(province) || "重庆".equals(province))
                && containsAlias(metadata, province, normalized)) {
            return normalize(province);
        }
        java.util.LinkedHashSet<String> chineseMatches = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> englishMatches = new java.util.LinkedHashSet<>();
        for (int row = 0; row < LOCAL_CITY_ALIASES.length; row++) {
            String[] aliases = LOCAL_CITY_ALIASES[row];
            String[] normalizedAliases = NORMALIZED_LOCAL_CITY_ALIASES[row];
            if (!province.equals(aliases[0])) continue;
            for (int i = 1; i < aliases.length; i++) {
                if (!containsNormalizedAlias(uppercaseMetadata, normalizedAliases[i], normalized)) continue;
                String alias = normalizedAliases[i];
                if (alias.matches(".*[\\u4E00-\\u9FFF].*")) chineseMatches.add(alias);
                else englishMatches.add(alias);
            }
        }
        // Prefer a single Chinese city token when feeds include both Chinese and
        // English aliases for that same city. Multiple distinct city tokens stay
        // ambiguous; English-only metadata is accepted only when unique.
        if (chineseMatches.size() == 1) return chineseMatches.iterator().next();
        if (!chineseMatches.isEmpty()) return "";
        return englishMatches.size() == 1 ? englishMatches.iterator().next() : "";
    }

    static boolean hasSpecificLocalCity(String metadata) {
        return !localCityForChannel(metadata).isEmpty();
    }

    private static boolean containsAlias(String metadata, String alias, String normalizedMetadata) {
        return containsNormalizedAlias(
                metadata == null ? "" : metadata.toUpperCase(Locale.ROOT),
                normalize(alias), normalizedMetadata);
    }

    private static boolean hasCjkCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value >= 0x4E00 && value <= 0x9FFF) return true;
        }
        return false;
    }

    private static boolean containsNormalizedAlias(String uppercaseMetadata, String normalizedAlias, String normalizedMetadata) {
        if (normalizedAlias.isEmpty()) return false;
        // Chinese names are naturally contiguous. English city names must be
        // whole tokens so "Xiangyang" cannot accidentally match "Xian".
        if (hasCjkCharacter(normalizedAlias)) {
            return normalizedMetadata.contains(normalizedAlias);
        }
        Pattern pattern = ENGLISH_LOCAL_PATTERNS.get(normalizedAlias);
        if (pattern == null) {
            Pattern compiled = Pattern.compile("(^|[^A-Z])"
                    + Pattern.quote(normalizedAlias) + "([^A-Z]|$)");
            Pattern previous = ENGLISH_LOCAL_PATTERNS.putIfAbsent(normalizedAlias, compiled);
            pattern = previous == null ? compiled : previous;
        }
        return pattern.matcher(uppercaseMetadata).find();
    }

    /** Resolve broadcaster identity from all M3U metadata, not just the display label. */
    static String canonicalBroadcasterName(String name, String tvgName, String tvgId, String group) {
        String metadata = ((name == null ? "" : name) + " "
                + (tvgName == null ? "" : tvgName) + " "
                + (tvgId == null ? "" : tvgId) + " "
                + (group == null ? "" : group)).trim();
        String satellite = canonicalSatelliteName(metadata);
        if (!satellite.isEmpty()) {
            // Recognized aliases return a short canonical broadcaster name.
            // For an unknown satellite, the fallback returns the input unchanged.
            // Never turn the whole concatenated name + EPG + group metadata into
            // a displayed channel name (e.g. "News tvg-id group 卫视").
            if (!satellite.equals(metadata)) return satellite;
            String namedSatellite = canonicalSatelliteName(name);
            if (!namedSatellite.isEmpty()) return namedSatellite;
            String epgSatellite = canonicalSatelliteName(tvgName);
            if (!epgSatellite.isEmpty()) return epgSatellite;
        }
        String value = metadata.toUpperCase(Locale.ROOT)
                .replaceAll("[\\s_—–·•]", "");
        // Match special variants before the generic numeric form: otherwise
        // CCTV-4K and CCTV-4 Asia collapse into the ordinary CCTV-4 identity.
        java.util.regex.Matcher ultraHd = CCTV_ULTRA_HD.matcher(value);
        if (ultraHd.find()) return "CCTV-" + ultraHd.group(1);
        java.util.regex.Matcher asia = CCTV_ASIA.matcher(value);
        if (asia.find()) return "CCTV-4 Asia";
        java.util.regex.Matcher matcher = CCTV_STANDARD.matcher(value);
        if (matcher.find()) return "CCTV-" + matcher.group(1);
        return "";
    }

    static String canonicalSatelliteName(String name) {
        String normalized = normalize(name);
        if (normalized.isEmpty()) return "";
        for (int row = 0; row < SATELLITE_ALIASES.length; row++) {
            String[] aliases = SATELLITE_ALIASES[row];
            String[] normalizedAliases = NORMALIZED_SATELLITE_ALIASES[row];
            // Match the canonical Chinese label as well as English aliases.
            // Public M3U feeds sometimes repeat a station name many times in
            // one display label (for example "四川卫视 四川卫视 ...").
            // Returning that raw label creates a visibly corrupted channel row.
            String canonical = normalizedAliases[0];
            if (normalized.contains(canonical)) return aliases[0];
            for (int i = 1; i < aliases.length; i++) {
                if (normalized.contains(normalizedAliases[i])) return aliases[0];
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
