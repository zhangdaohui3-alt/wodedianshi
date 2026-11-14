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
}
