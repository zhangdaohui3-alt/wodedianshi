package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelIdentityPolicyTest {
    @Test public void normalizesCctvNumberAndCommonAliases() {
        assertEquals("CCTV-1", ChannelIdentityPolicy.canonicalName("CCTV1"));
        assertEquals("CCTV-1", ChannelIdentityPolicy.canonicalName("中央电视台一套"));
        assertEquals("CCTV-1", ChannelIdentityPolicy.canonicalName("中央电视台综合频道"));
        assertEquals("CCTV-5", ChannelIdentityPolicy.canonicalName("央视5"));
        assertEquals("CCTV-NEWS", ChannelIdentityPolicy.canonicalName("CCTV NEWS"));
    }

    @Test public void stripsQualityAndLiveSuffixesBeforeSatelliteMatching() {
        assertEquals("湖南卫视", ChannelIdentityPolicy.canonicalName("湖南台 1080P 高清直播"));
        assertEquals("浙江卫视", ChannelIdentityPolicy.canonicalName("浙江卫视超高清"));
        assertEquals("江苏卫视", ChannelIdentityPolicy.canonicalName("江苏台频道直播"));
    }

    @Test public void preservesUnknownChannelNamesAndNullSafety() {
        assertEquals("福建综合", ChannelIdentityPolicy.canonicalName("福建综合"));
        assertEquals("", ChannelIdentityPolicy.canonicalName(null));
    }
}
