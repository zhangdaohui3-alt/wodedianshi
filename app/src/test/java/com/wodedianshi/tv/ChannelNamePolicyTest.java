package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelNamePolicyTest {
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

    @Test public void resolvesSatelliteIdentityFromTvgMetadataEvenWhenDisplayNameIsGeneric() {
        assertEquals("湖南卫视", ChannelNamePolicy.canonicalBroadcasterName(
                "高清频道", "Hunan TV", "HunanSatelliteTV", "China"));
        assertEquals("浙江卫视", ChannelNamePolicy.canonicalBroadcasterName(
                "HD", "", "ZhejiangSatelliteTV", "Live"));
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
        assertEquals("福建", ChannelNamePolicy.provinceForLocalChannel("福州综合频道"));
        assertEquals("广东", ChannelNamePolicy.provinceForLocalChannel("深圳都市频道"));
        assertEquals("湖北", ChannelNamePolicy.provinceForLocalChannel("襄阳新闻综合"));
        assertEquals("新疆", ChannelNamePolicy.provinceForLocalChannel("喀什电视台"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("台州新闻频道"));
        assertEquals("浙江", ChannelNamePolicy.provinceForLocalChannel("浙江台州新闻频道"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("玉林电视台"));
        assertEquals("广西", ChannelNamePolicy.provinceForLocalChannel("广西玉林电视台"));
    }

    @Test public void doesNotGuessProvinceForAmbiguousCityAliases() {
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Taizhou TV"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Yulin TV"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Suzhou TV"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Fuzhou TV"));
        assertEquals("", ChannelNamePolicy.provinceForLocalChannel("Yichun TV"));
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
}
