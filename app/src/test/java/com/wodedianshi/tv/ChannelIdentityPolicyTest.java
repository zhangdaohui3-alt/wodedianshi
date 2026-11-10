package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelIdentityPolicyTest {
    @Test public void cctvAliasesShareOneIdentityAcrossHosts() {
        assertEquals(ChannelIdentityPolicy.identity("CCTV-1 综合", "央视", "https://a.example/live"),
                ChannelIdentityPolicy.identity("央视一套", "频道", "https://b.example/live"));
        assertEquals("cctv-1|cctv", ChannelIdentityPolicy.identity(
                "中央电视台综合频道", "其他", "https://c.example/live"));
    }

    @Test public void satelliteAliasesShareOneIdentityAcrossHosts() {
        assertEquals(ChannelIdentityPolicy.identity("湖南台", "卫视", "https://a.example/live"),
                ChannelIdentityPolicy.identity("湖南卫视高清", "频道", "https://b.example/live"));
    }

    @Test public void unrelatedLocalChannelsRemainHostSeparated() {
        assertNotEquals(ChannelIdentityPolicy.identity("新闻综合", "地方", "https://a.example/live"),
                ChannelIdentityPolicy.identity("新闻综合", "地方", "https://b.example/live"));
    }

    @Test public void nonSatelliteIdentityRetainsGroupAndHost() {
        assertNotEquals(ChannelIdentityPolicy.identity("综合频道", "新闻", "https://a.example/live"),
                ChannelIdentityPolicy.identity("综合频道", "影视", "https://a.example/live"));
    }

    @Test public void qualitySuffixesDoNotChangeRecognizedSatelliteName() {
        assertEquals("湖南卫视", ChannelIdentityPolicy.canonicalName("湖南卫视 1080P 高清直播"));
    }
}
