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

    @Test public void mergesSameRecognizedCityStationAcrossHostsAndProviderIds() {
        String local = ChannelIdentityPolicy.localStationIdentity("深圳都市频道", "");
        String first = ChannelIdentityPolicy.identity("", "PROVIDER_A_SHENZHEN", "host:a", local);
        String second = ChannelIdentityPolicy.identity("", "PROVIDER_B_SHENZHEN", "host:b",
                ChannelIdentityPolicy.localStationIdentity("深圳都市频道 高清", ""));
        assertEquals("local:广东|深圳|深圳都市频道", local);
        assertEquals(first, second);
    }

    @Test public void bilingualMetadataKeepsOneCityAndRejectsMultipleCities() {
        assertEquals("local:广东|深圳|深圳都市频道",
                ChannelIdentityPolicy.localStationIdentity("深圳都市频道", "Shenzhen City Channel"));
        assertEquals("", ChannelIdentityPolicy.localStationIdentity("深圳广州新闻频道", ""));
    }

    @Test public void prefersChineseGuideLabelForEnglishDisplayName() {
        assertEquals("local:广东|深圳|深圳都市频道",
                ChannelIdentityPolicy.localStationIdentity(
                        "Shenzhen City Channel", "深圳都市频道"));
    }

    @Test public void recognizesMunicipalitiesAsTheirOwnCityIdentity() {
        assertEquals("local:北京|北京|北京新闻频道",
                ChannelIdentityPolicy.localStationIdentity("北京新闻频道", ""));
        assertEquals("local:上海|上海|上海纪实频道",
                ChannelIdentityPolicy.localStationIdentity("上海纪实频道", ""));
    }

    @Test public void localStationMergeRequiresAnUnambiguousCityAndSpecificLabel() {
        assertEquals("", ChannelIdentityPolicy.localStationIdentity("福州综合频道", ""));
        assertEquals("", ChannelIdentityPolicy.localStationIdentity("福建新闻综合", ""));
        assertEquals("local:福建|福州|福建福州综合频道",
                ChannelIdentityPolicy.localStationIdentity("福建福州综合频道", ""));
        assertNotEquals(
                ChannelIdentityPolicy.localStationIdentity("深圳新闻频道", ""),
                ChannelIdentityPolicy.localStationIdentity("广州新闻频道", ""));
    }

    @Test public void keepsProviderNamespacePunctuation() {
        assertEquals("fjtv.fuzhou", ChannelIdentityPolicy.normalizeTvgId(" FJTV.FUZHOU "));
        assertEquals("", ChannelIdentityPolicy.normalizeTvgId("123"));
        assertEquals("", ChannelIdentityPolicy.normalizeTvgId("abc"));
    }
}
