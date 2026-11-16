package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelIdentityPolicyTest {
    @Test public void cctvIdentityUsesCanonicalAliasAcrossDifferentHosts() {
        assertEquals(
                ChannelIdentityPolicy.key("CCTV1 HD", "", "", "News", "https://one.example/live"),
                ChannelIdentityPolicy.key("中央电视台综合频道", "", "", "央视", "https://two.example/live"));
    }

    @Test public void satelliteIdentityUsesEnglishAliasAcrossDifferentHosts() {
        assertEquals(
                ChannelIdentityPolicy.key("Hunan TV", "", "", "Satellite", "https://one.example/live"),
                ChannelIdentityPolicy.key("湖南卫视", "", "", "卫视", "https://two.example/live"));
        assertEquals(
                ChannelIdentityPolicy.key("Dragon TV", "", "", "Satellite", "https://one.example/live"),
                ChannelIdentityPolicy.key("东方卫视", "", "", "卫视", "https://two.example/live"));
    }

    @Test public void broadcasterMetadataCanIdentifyAChannelWhenDisplayNameIsGeneric() {
        assertEquals(
                ChannelIdentityPolicy.key("HD", "Zhejiang Satellite TV", "zhejiang.satellite", "Local", "https://one.example/live"),
                ChannelIdentityPolicy.key("浙江卫视", "", "", "卫视", "https://two.example/live"));
    }

    @Test public void genericLocalChannelsRemainSeparatedBySourceHost() {
        assertNotEquals(
                ChannelIdentityPolicy.key("Chengdu News", "", "", "Local", "https://one.example/live"),
                ChannelIdentityPolicy.key("Chengdu News", "", "", "Local", "https://two.example/live"));
    }

    @Test public void genericIdentityRetainsGroupSeparation() {
        assertNotEquals(
                ChannelIdentityPolicy.key("Local News", "", "", "Local", "https://one.example/live"),
                ChannelIdentityPolicy.key("Local News", "", "", "Sports", "https://one.example/live"));
    }
}
