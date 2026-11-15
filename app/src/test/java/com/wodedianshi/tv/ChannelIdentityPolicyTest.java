package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelIdentityPolicyTest {
    @Test public void recognizedCctvAndSatelliteKeepTheirCanonicalKeys() {
        assertEquals("CCTV-1|cctv", ChannelIdentityPolicy.identity("CCTV-1", "CCTV1", "fallback"));
        assertEquals("湖南卫视|satellite", ChannelIdentityPolicy.identity("湖南卫视", "HunanTV", "fallback"));
    }

    @Test public void stableTvgIdGroupsSameLocalChannelAcrossDifferentHosts() {
        String first = ChannelIdentityPolicy.identity("", "FJTV.FUZHOU", "福建综合|地方|host:a.example");
        String second = ChannelIdentityPolicy.identity("", " fjtv.fuzhou ", "福建综合|地方|host:b.example");
        assertEquals("tvgid:fjtv.fuzhou", first);
        assertEquals(first, second);
    }

    @Test public void differentTvgIdsDoNotMergeSameNamedChannels() {
        String first = ChannelIdentityPolicy.identity("", "FJTV.FUZHOU", "福建综合|地方|host:a.example");
        String second = ChannelIdentityPolicy.identity("", "FJTV.XIAMEN", "福建综合|地方|host:b.example");
        assertNotEquals(first, second);
    }

    @Test public void weakOrMissingTvgIdsKeepHostScopedFallback() {
        assertEquals("福建综合|地方|host:a.example",
                ChannelIdentityPolicy.identity("", "101", "福建综合|地方|host:a.example"));
        assertEquals("福建综合|地方|host:b.example",
                ChannelIdentityPolicy.identity("", "", "福建综合|地方|host:b.example"));
    }

    @Test public void keepsProviderNamespacePunctuation() {
        assertEquals("fjtv.fuzhou", ChannelIdentityPolicy.normalizeTvgId(" FJTV.FUZHOU "));
        assertEquals("", ChannelIdentityPolicy.normalizeTvgId("123"));
        assertEquals("", ChannelIdentityPolicy.normalizeTvgId("abc"));
    }
}
