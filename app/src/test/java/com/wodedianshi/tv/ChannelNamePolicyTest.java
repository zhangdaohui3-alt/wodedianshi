package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelNamePolicyTest {
    @Test public void reusedProvinceRetainsCityAndAmbiguitySemantics(){
        String[][] cases={
            {"深圳新闻综合","广东","深圳"},
            {"ShenzhenNews.cn","广东","深圳"},
            {"Fujian Fuzhou News","福建","福州"},
            {"Jiangxi Fuzhou News","福建","福州"},
            {"Fuzhou TV","福建","福州"},
            {"NotShenzhenNews","广东","深圳"},
            {"江苏台州新闻","江苏","泰州"},
            {"青海 海南州新闻","青海","海南州"},
            {"Ｘiamen News","福建","厦门"},
            {"深圳—新闻📺","广东","深圳"},
            {"Local News US","福建","福州"}
        };
        for(String[] row:cases){
            String resolved=ChannelNamePolicy.provinceForLocalChannel(row[0]);
            assertEquals(row[0],ChannelNamePolicy.matchesLocalCity(row[0],row[1],row[2]),
                ChannelNamePolicy.matchesLocalCityWithProvince(row[0],row[1],row[2],resolved));
        }
        assertTrue(ChannelNamePolicy.matchesLocalCity("深圳—新闻📺","广东","深圳"));
        assertFalse(ChannelNamePolicy.matchesLocalCity("NotShenzhenNews","广东","深圳"));
        assertFalse(ChannelNamePolicy.matchesLocalCity("Fuzhou TV","福建","福州"));
    }

    @Test public void recognizesCompactLocalServiceNamesAndEpgIdsConservatively() {
        assertEquals("广东", ChannelNamePolicy.provinceForLocalChannel("ShenzhenNews.cn"));
        assertEquals("四川", ChannelNamePolicy.provinceForLocalChannel("ChengduComprehensive"));
        assertEquals("湖北", ChannelNamePolicy.provinceForLocalChannel("XiangyangNews"));
        assertEquals("深圳新闻", ChannelNamePolicy.canonicalLocalDisplayName("ShenzhenNews","","","China"));
        assertTrue(ChannelNamePolicy.matchesLocalCity("ShenzhenNews.cn","广东","深圳"));
        assertFalse(ChannelNamePolicy.matchesLocalCity("XiangyangNews","陕西","西安"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("FuzhouNews.cn"));
        assertEquals("福建", ChannelNamePolicy.provinceForLocalChannel("Fujian FuzhouNews.cn"));
        assertEquals("江西", ChannelNamePolicy.provinceForLocalChannel("Jiangxi FuzhouNews.cn"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Fujian ShenzhenNews"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("ShenzhenMystery"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("NotShenzhenNews"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("ShenzhenNewsExtra"));
        assertEquals(FullDiscoveryProgressPolicy.CORE_LOCAL,
            FullDiscoveryProgressPolicy.coreCategoryMask("ShenzhenNews","China","","ShenzhenNews.cn"));
    }

    @Test public void localDisplayPrefersKnownChineseLabelsWithoutGuessingUnknownServices() {
        assertEquals("深圳新闻",ChannelNamePolicy.canonicalLocalDisplayName("Shenzhen News","","","China"));
        assertEquals("成都综合",ChannelNamePolicy.canonicalLocalDisplayName("Chengdu Comprehensive","","","China"));
        assertEquals("福州新闻",ChannelNamePolicy.canonicalLocalDisplayName("Fuzhou News","","","福建"));
        assertEquals("抚州新闻",ChannelNamePolicy.canonicalLocalDisplayName("Fuzhou News","","","江西"));
        assertEquals("Fuzhou News",ChannelNamePolicy.canonicalLocalDisplayName("Fuzhou News","","","China"));
        assertEquals("深圳新闻综合",ChannelNamePolicy.canonicalLocalDisplayName("Shenzhen News","深圳新闻综合","","China"));
        assertEquals("Shenzhen Mystery",ChannelNamePolicy.canonicalLocalDisplayName("Shenzhen Mystery","","","China"));
        assertEquals("厦门新闻",ChannelNamePolicy.canonicalLocalDisplayName("厦门新闻","","","China"));
        assertEquals("Local News",ChannelNamePolicy.canonicalLocalDisplayName("Local News","","","US"));
    }

    @Test public void chinesePresentationAliasesKeepUnknownAndLocalNamesConservative(){
        assertEquals("凤凰卫视中文台",ChannelNamePolicy.chineseDisplayName("Phoenix Chinese Channel","","","Hong Kong"));
        assertEquals("翡翠台",ChannelNamePolicy.chineseDisplayName("TVB Jade","","","Hong Kong"));
        assertEquals("BBC新闻",ChannelNamePolicy.chineseDisplayName("BBC News","","","UK"));
        assertEquals("BBC News Arabic",ChannelNamePolicy.chineseDisplayName("BBC News Arabic","","",""));
        assertEquals("深圳新闻",ChannelNamePolicy.chineseDisplayName("Shenzhen News","","","China"));
        assertEquals("Fuzhou News",ChannelNamePolicy.chineseDisplayName("Fuzhou News","","","China"));
        assertEquals("厦门新闻",ChannelNamePolicy.chineseDisplayName("厦门新闻","","","China"));
    }

    @Test public void genericForeignLocalGroupIsNotDomesticEvidence() {
        assertFalse(ChannelNamePolicy.isDomesticLocalLabel("Local News US",false));
        assertFalse(ChannelNamePolicy.isDomesticLocalLabel("Local News",false));
        assertTrue(ChannelNamePolicy.isDomesticLocalLabel("Local News",true));
        assertTrue(ChannelNamePolicy.isDomesticLocalLabel("Shenzhen News",false));
    }

    @Test public void mapsCommonEnglishSatelliteNamesToChinese() {
        assertEquals("湖南卫视", ChannelNamePolicy.canonicalSatelliteName("Hunan TV"));
        assertEquals("浙江卫视", ChannelNamePolicy.canonicalSatelliteName("Zhejiang Satellite TV"));
        assertEquals("江苏卫视", ChannelNamePolicy.canonicalSatelliteName("Jiangsu TV"));
        assertEquals("东方卫视", ChannelNamePolicy.canonicalSatelliteName("Dragon TV"));
        assertEquals("福建东南卫视", ChannelNamePolicy.canonicalSatelliteName("Southeast Satellite TV"));
    }

    @Test public void collapsesRepeatedChineseSatelliteNamesToOneCanonicalLabel() {
        assertEquals("四川卫视", ChannelNamePolicy.canonicalSatelliteName(
                "四川卫视 四川卫视 卫视台 四川卫视 卫视台"));
        assertEquals("天津卫视", ChannelNamePolicy.canonicalSatelliteName(
                "天津卫视 天津卫视 卫视频道"));
        assertEquals("湖南卫视", ChannelNamePolicy.canonicalSatelliteName(
                "高清 湖南卫视 湖南卫视 直播"));
    }

    @Test public void fujianSatelliteEpGLabelDoesNotRepeatAcrossRows() {
        assertEquals("福建东南卫视", ChannelNamePolicy.canonicalSatelliteName(
                "E卫视 福建卫视 FUJIANWEISHI.SATELLITE"));
        assertEquals("福建东南卫视", ChannelNamePolicy.canonicalBroadcasterName(
                "E卫视", "福建卫视", "FUJIANWEISHI.SATELLITE", "卫视"));
    }

    @Test public void resolvesSatelliteIdentityFromTvgMetadataEvenWhenDisplayNameIsGeneric() {
        assertEquals("湖南卫视", ChannelNamePolicy.canonicalBroadcasterName(
                "高清频道", "Hunan TV", "HunanSatelliteTV", "China"));
        assertEquals("浙江卫视", ChannelNamePolicy.canonicalBroadcasterName(
                "HD", "", "ZhejiangSatelliteTV", "Live"));
    }

    @Test public void unknownSatelliteGroupDoesNotBecomeConcatenatedDisplayName() {
        assertEquals("", ChannelNamePolicy.canonicalBroadcasterName(
                "城市新闻", "EPG Channel", "regional.123", "卫视"));
        assertEquals("某地卫视", ChannelNamePolicy.canonicalBroadcasterName(
                "某地卫视", "EPG Channel", "regional.123", "卫视"));
        assertEquals("某地卫视", ChannelNamePolicy.canonicalBroadcasterName(
                "高清频道", "某地卫视", "regional.123", "卫视"));
        assertEquals("湖南卫视", ChannelNamePolicy.canonicalBroadcasterName(
                "高清频道", "Hunan TV", "regional.123", "卫视"));
    }

    @Test public void cctvFivePlusKeepsDistinctIdentity() {
        assertEquals("CCTV-5+", ChannelNamePolicy.canonicalBroadcasterName(
                "CCTV-5+ 体育赛事", "", "CCTV5Plus", "央视"));
        assertEquals("CCTV-5", ChannelNamePolicy.canonicalBroadcasterName(
                "CCTV-5 体育", "", "CCTV5", "央视"));
    }

    @Test public void resolvesCctvIdentityFromTvgMetadata() {
        assertEquals("CCTV-1", ChannelNamePolicy.canonicalBroadcasterName(
                "高清频道", "央视综合", "CCTV-1", "中国"));
        assertEquals("CCTV-4", ChannelNamePolicy.canonicalBroadcasterName(
                "HD", "", "CCTV-4", "CCTV"));
    }

    @Test public void keepsCctvUltraHdAndAsiaVariantsDistinctFromCctv4() {
        assertEquals("CCTV-4K", ChannelNamePolicy.canonicalBroadcasterName(
                "CCTV-4K (1080p)", "", "CCTV4K.cn@HD", "General"));
        assertEquals("CCTV-8K", ChannelNamePolicy.canonicalBroadcasterName(
                "CCTV-8K", "", "CCTV8K.cn@HD", "General"));
        assertEquals("CCTV-4 Asia", ChannelNamePolicy.canonicalBroadcasterName(
                "CCTV-4 Asia (720p)", "", "CCTV4Asia.cn@SD", "General"));
        assertEquals("CCTV-4", ChannelNamePolicy.canonicalBroadcasterName(
                "CCTV-4 (1080p)", "", "CCTV4.cn@HD", "General"));
    }

    @Test public void recognizesSatelliteGroupsWithoutMisclassifyingGenericTv() {
        assertTrue(ChannelNamePolicy.isSatellite("Hunan TV", "China"));
        assertTrue(ChannelNamePolicy.isSatellite("湖南卫视", ""));
        assertTrue(ChannelNamePolicy.isSatellite("Unknown channel", "Satellite"));
        assertFalse(ChannelNamePolicy.isSatellite("Fuzhou TV", "China"));
        assertFalse(ChannelNamePolicy.isSatellite("Local News", "Local"));
    }

    @Test public void mapsEnglishCityStationsToTheirChineseProvince() {
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Fuzhou TV"));
        assertEquals("四川", ChannelNamePolicy.provinceForLocalChannel("Chengdu News"));
        assertEquals("广东", ChannelNamePolicy.provinceForLocalChannel("Shenzhen City Channel"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("International News"));
    }

    @Test public void recognizesMoreEnglishLocalCityAliasesAcrossChina() {
        assertEquals("河北", ChannelNamePolicy.provinceForLocalChannel("Qinhuangdao TV"));
        assertEquals("黑龙江", ChannelNamePolicy.provinceForLocalChannel("Qiqihar News"));
        assertEquals("山东", ChannelNamePolicy.provinceForLocalChannel("Weihai TV"));
        assertEquals("河南", ChannelNamePolicy.provinceForLocalChannel("Zhumadian TV"));
        assertEquals("湖北", ChannelNamePolicy.provinceForLocalChannel("Xiangyang News"));
        assertEquals("广西", ChannelNamePolicy.provinceForLocalChannel("Nanning TV"));
        assertEquals("新疆", ChannelNamePolicy.provinceForLocalChannel("Kashgar TV"));
    }

    @Test public void explicitProvinceDisambiguatesCityOnlyAliases() {
        assertEquals("福建", ChannelNamePolicy.provinceForLocalChannel("福建 Fuzhou TV"));
        assertEquals("江苏", ChannelNamePolicy.provinceForLocalChannel("江苏 Suzhou TV"));
    }

    @Test public void recognizesChineseCityNamesAndKeepsAmbiguousNamesConservative() {
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("福州综合频道"));
        assertEquals("福建", ChannelNamePolicy.provinceForLocalChannel("福建福州综合频道"));
        assertEquals("广东", ChannelNamePolicy.provinceForLocalChannel("深圳都市频道"));
        assertEquals("湖北", ChannelNamePolicy.provinceForLocalChannel("襄阳新闻综合"));
        assertEquals("新疆", ChannelNamePolicy.provinceForLocalChannel("喀什电视台"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("台州新闻频道"));
        assertEquals("浙江", ChannelNamePolicy.provinceForLocalChannel("浙江台州新闻频道"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("玉林电视台"));
        assertEquals("广西", ChannelNamePolicy.provinceForLocalChannel("广西玉林电视台"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("朝阳电视台"));
        assertEquals("辽宁", ChannelNamePolicy.provinceForLocalChannel("辽宁朝阳电视台"));
    }

    @Test public void recognizesCountyLevelLocalStationNamesFromAggregatedFeeds() {
        assertEquals("浙江", ChannelNamePolicy.provinceForLocalChannel("上虞新闻综合"));
        assertEquals("浙江", ChannelNamePolicy.provinceForLocalChannel("余姚新闻综合"));
        assertEquals("浙江", ChannelNamePolicy.provinceForLocalChannel("兰溪新闻综合"));
        assertEquals("浙江", ChannelNamePolicy.provinceForLocalChannel("庆元新闻综合"));
        assertEquals("浙江", ChannelNamePolicy.provinceForLocalChannel("开化新闻综合"));
        assertEquals("浙江", ChannelNamePolicy.provinceForLocalChannel("文成新闻综合"));
        assertEquals("浙江", ChannelNamePolicy.provinceForLocalChannel("新昌新闻综合"));
        assertEquals("安徽", ChannelNamePolicy.provinceForLocalChannel("固镇新闻"));
        assertEquals("四川", ChannelNamePolicy.provinceForLocalChannel("旺苍新闻综合"));
        assertEquals("上虞", ChannelNamePolicy.localCityForChannel("上虞新闻综合"));
    }

    @Test public void pickerUsesSameLocalitiesAsClassifier() {
        String[][] groups = {
            {"浙江","上虞","余姚","兰溪","庆元","开化","文成","新昌"},
            {"安徽","固镇"},{"四川","旺苍"},{"新疆","库尔勒","伊宁"},
            {"湖北","仙桃","潜江","天门"},{"北京","北京"},{"上海","上海"}
        };
        for(String[] group:groups){
            java.util.List<String> picker=java.util.Arrays.asList(ChannelNamePolicy.localitiesForProvince(group[0]));
            for(int i=1;i<group.length;i++){
                assertTrue(picker.contains(group[i]));
                assertTrue(ChannelNamePolicy.isKnownLocality(group[0],group[i]));
                assertEquals(group[0],ChannelNamePolicy.provinceForLocalChannel(group[i]+"新闻"));
                assertTrue(ChannelNamePolicy.matchesLocalCity(group[i]+"新闻",group[0],group[i]));
            }
        }
        assertFalse(ChannelNamePolicy.isKnownLocality("湖南","仙桃"));
        assertFalse(ChannelNamePolicy.isKnownLocality("广东","广东"));
        assertEquals(0,ChannelNamePolicy.localitiesForProvince("unknown").length);
        assertEquals("湖北",ChannelNamePolicy.provinceForLocalChannel("Xiantao News"));
        assertEquals("潜江新闻",ChannelNamePolicy.canonicalLocalDisplayName("Qianjiang News","","","湖北"));
        assertTrue(ChannelNamePolicy.matchesLocalCity("Tianmen News","湖北","天门"));
    }

    @Test public void doesNotGuessProvinceForAmbiguousCityAliases() {
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Taizhou TV"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Yulin TV"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Suzhou TV"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Fuzhou TV"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Yichun TV"));
    }

    @Test public void taizhouChineseAliasAndSharedPinyinRequireConsistentProvince() {
        assertEquals("江苏", ChannelNamePolicy.provinceForLocalChannel("泰州新闻综合"));
        assertEquals("泰州", ChannelNamePolicy.localCityForChannel("泰州新闻综合"));
        assertTrue(ChannelNamePolicy.matchesLocalCity("泰州新闻综合", "江苏", "泰州"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Taizhou News"));
        assertEquals("江苏", ChannelNamePolicy.provinceForLocalChannel("Jiangsu Taizhou News"));
        assertEquals("浙江", ChannelNamePolicy.provinceForLocalChannel("Zhejiang Taizhou News"));
        assertEquals("泰州新闻", ChannelNamePolicy.canonicalLocalDisplayName("Taizhou News","","","江苏"));
        assertEquals("台州新闻", ChannelNamePolicy.canonicalLocalDisplayName("Taizhou News","","","浙江"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("河北 Fuzhou News"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("江苏台州新闻"));
        assertEquals("江西", ChannelNamePolicy.provinceForLocalChannel("Jiangxi Fuzhou News"));
        assertEquals("福建", ChannelNamePolicy.provinceForLocalChannel("Fujian Fuzhou News"));
    }

    @Test public void explicitProvinceIsResolvedBeforeAmbiguousCityAliases() {
        assertEquals("江西", ChannelNamePolicy.provinceForLocalChannel("Jiangxi Fuzhou TV"));
        assertEquals("江苏", ChannelNamePolicy.provinceForLocalChannel("Jiangsu Suzhou TV"));
        assertEquals("福建", ChannelNamePolicy.provinceForLocalChannel("Fujian Fuzhou TV"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("福建 广东 深圳"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Fuzhou TV"));
    }

    @Test public void previouslyUiOnlyCitiesRemainInProvinceDirectories() {
        assertEquals("西藏", ChannelNamePolicy.provinceForLocalChannel("拉萨新闻综合"));
        assertEquals("海南", ChannelNamePolicy.provinceForLocalChannel("三沙电视台"));
        assertEquals("四川", ChannelNamePolicy.provinceForLocalChannel("甘孜新闻"));
        assertEquals("青海", ChannelNamePolicy.provinceForLocalChannel("海南州新闻"));
        assertTrue(ChannelNamePolicy.matchesLocalCity("青海 海南州新闻", "青海", "海南州"));
    }

    @Test public void englishCityMetadataMatchesChineseCityPicker() {
        assertTrue(ChannelNamePolicy.matchesLocalCity("Fujian Fuzhou TV", "福建", "福州"));
        assertTrue(ChannelNamePolicy.matchesLocalCity("Xiamen News", "福建", "厦门"));
        assertTrue(ChannelNamePolicy.matchesLocalCity("Shenzhen News", "广东", "深圳"));
        assertTrue(ChannelNamePolicy.matchesLocalCity("Jiangxi Fuzhou TV", "江西", "抚州"));
        assertFalse(ChannelNamePolicy.matchesLocalCity("Jiangxi Fuzhou TV", "福建", "福州"));
        assertFalse(ChannelNamePolicy.matchesLocalCity("Fuzhou TV", "福建", "福州"));
        assertFalse(ChannelNamePolicy.matchesLocalCity("Xiangyang News", "陕西", "西安"));
    }

    @Test public void leavesUnknownEnglishChannelNamesUnchanged() {
        assertEquals("", ChannelNamePolicy.canonicalSatelliteName("Fuzhou TV"));
        assertEquals("", ChannelNamePolicy.canonicalSatelliteName("CCTV-1"));
    }

    @Test public void satelliteClassificationToleratesNullGroupMetadata() {
        assertFalse(ChannelNamePolicy.isSatellite("International News", null));
        assertTrue(ChannelNamePolicy.isSatellite("湖南卫视", null));
        assertFalse(ChannelNamePolicy.isSatellite(null, null));
    }

    @Test public void sharedBroadcasterConflictRejectsContradictoryRecognizedLabels() {
        assertNull(ChannelNamePolicy.consistentBroadcaster("CCTV-1","CCTV-10",""));
        assertNull(ChannelNamePolicy.consistentBroadcaster("中央一套","CCTV-10",""));
        assertNull(ChannelNamePolicy.consistentBroadcaster("湖南卫视","浙江卫视",""));
        assertNull(ChannelNamePolicy.consistentBroadcaster("CCTV-4","CCTV-4K",""));
        assertNull(ChannelNamePolicy.consistentBroadcaster("CCTV-4","CCTV-4 Asia",""));
        assertEquals("CCTV-5+",ChannelNamePolicy.consistentBroadcaster("CCTV-5+","CCTV5Plus.cn",""));
        assertEquals("CCTV-1",ChannelNamePolicy.consistentBroadcaster("高清频道","CCTV-1",""));
        assertEquals("",ChannelNamePolicy.consistentBroadcaster("未识别频道","",""));
    }

    @Test public void explicitStationIdentityOutranksConflictingDirectoryGroup(){
        assertEquals("CCTV-1",ChannelNamePolicy.canonicalBroadcasterName(
                "CCTV-1","CCTV-1","CCTV1.cn","湖南卫视"));
        assertEquals("CCTV-4",ChannelNamePolicy.canonicalBroadcasterName(
                "CCTV-4","", "CCTV4.cn","CCTV-4K"));
        assertEquals("湖南卫视",ChannelNamePolicy.canonicalBroadcasterName(
                "高清频道","Hunan TV","HunanSatelliteTV","浙江卫视"));
        assertEquals("CCTV-5+",ChannelNamePolicy.canonicalBroadcasterName(
                "CCTV-5+","", "CCTV5Plus.cn","CCTV-5"));
        assertEquals("CCTV-1",SourceDiscoveryIdentityPolicy.canonicalName("CCTV-1"));
        assertEquals("CCTV-1|cctv",SourceDiscoveryIdentityPolicy.identity(
                "CCTV-1","湖南卫视","https://example.invalid/live","CCTV1.cn","CCTV-1"));
    }

    @Test public void legacyGroupHintRemainsAvailableWhenExplicitStationIsAbsent(){
        assertEquals("湖南卫视",ChannelNamePolicy.canonicalBroadcasterName(
                "高清频道","","","湖南卫视"));
        assertEquals("",ChannelNamePolicy.canonicalBroadcasterName(
                "城市新闻","EPG Channel","regional.123","卫视"));
    }
    @Test public void canonicalBroadcasterDisplayPreservesSpecialChannelIdentity() {
        assertEquals("CCTV-4K 超高清",ChannelNamePolicy.broadcasterDisplayName(
                ChannelNamePolicy.canonicalBroadcasterName("CCTV-4K","","","")));
        assertEquals("CCTV-8K 超高清",ChannelNamePolicy.broadcasterDisplayName("CCTV-8K"));
        assertEquals("CCTV-4 亚洲版",ChannelNamePolicy.broadcasterDisplayName("CCTV-4 Asia"));
        assertEquals("CCTV-4 中文国际",ChannelNamePolicy.broadcasterDisplayName("CCTV-4"));
        assertEquals("CCTV-5+ 体育赛事",ChannelNamePolicy.broadcasterDisplayName("CCTV-5+"));
        assertEquals("CCTV-5 体育",ChannelNamePolicy.broadcasterDisplayName("CCTV-5"));
        assertEquals("湖南卫视",ChannelNamePolicy.broadcasterDisplayName("湖南卫视"));
    }

    @Test public void cctvOrderingUsesOnlyRecognizedPrefixAndRetainsVariants() {
        assertEquals(4,ChannelNamePolicy.cctvSortOrder("CCTV-4 Asia"));
        assertEquals(5,ChannelNamePolicy.cctvSortOrder("CCTV-5+"));
        assertEquals(90,ChannelNamePolicy.cctvSortOrder("CCTV-NEWS"));
        assertEquals(91,ChannelNamePolicy.cctvSortOrder("CCTV-4K"));
        assertEquals(92,ChannelNamePolicy.cctvSortOrder("CCTV-8K"));
        assertEquals(999,ChannelNamePolicy.cctvSortOrder("ABCDE5"));
        assertEquals(999,ChannelNamePolicy.cctvSortOrder("央视"));
        assertEquals(999,ChannelNamePolicy.cctvSortOrder("CCTV-"));
        assertEquals(999,ChannelNamePolicy.cctvSortOrder("CCTV-123"));
    }

    @Test public void discoveryFallbackUsesTheSameSpecialCctvIdentity() {
        for(String label:new String[]{"CCTV-4K","CCTV-8K","CCTV-4 Asia","CCTV5Plus","CCTV-4","CCTV-5+"}){
            String canonical=ChannelNamePolicy.canonicalBroadcasterName(label,"","","");
            assertEquals(canonical,SourceDiscoveryIdentityPolicy.canonicalName(label));
            assertEquals(canonical+"|cctv",SourceDiscoveryIdentityPolicy.identity(label,"","https://example.invalid/live","",""));
        }
        assertEquals("CCTV-1",SourceDiscoveryIdentityPolicy.canonicalName("中央电视台一套"));
    }

}
