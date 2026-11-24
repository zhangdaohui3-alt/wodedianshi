package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChinaDiscoveryCandidatePolicyTest {
    private static final int MIXED = SourceDiscoveryFeedCatalog.MIXED_MAINLAND_FEED_INDEX;

    @Test public void mixedFeedOnlyProbesClearlyMainlandCandidates() {
        assertTrue(ChinaDiscoveryCandidatePolicy.shouldProbeInInitialDiscovery(
                MIXED,"china","上虞新闻综合","中国大陆 · 新闻","",""));
        assertTrue(ChinaDiscoveryCandidatePolicy.shouldProbeInInitialDiscovery(
                MIXED,"china","CCTV-1","央视","",""));
        assertFalse(ChinaDiscoveryCandidatePolicy.shouldProbeInInitialDiscovery(
                MIXED,"global","BBC News","International · News","",""));
        assertFalse(ChinaDiscoveryCandidatePolicy.shouldProbeInInitialDiscovery(
                MIXED,"global","Unidentified TV","News","",""));
    }

    @Test public void hmtStationsAreDeferredEvenIfDirectoryUsesChinaScope() {
        assertFalse(ChinaDiscoveryCandidatePolicy.shouldProbeInInitialDiscovery(
                MIXED,"china","RTHK","Hong Kong","香港电台",""));
        assertFalse(ChinaDiscoveryCandidatePolicy.shouldProbeInInitialDiscovery(
                MIXED,"china","PTS","News","臺灣公共電視",""));
        assertFalse(ChinaDiscoveryCandidatePolicy.shouldProbeInInitialDiscovery(
                MIXED,"china","TDM","MO","",""));
        assertTrue(ChinaDiscoveryCandidatePolicy.isClearlyMainland(
                "china","福州新闻","福建地方台","",""));
    }

    @Test public void dedicatedChinaAndInternationalFeedsDoNotLoseEntries() {
        assertTrue(ChinaDiscoveryCandidatePolicy.shouldProbeInInitialDiscovery(
                0,"global","Mislabelled HD","News","",""));
        assertTrue(ChinaDiscoveryCandidatePolicy.shouldProbeInInitialDiscovery(
                SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT,
                "global","BBC News","News","",""));
    }

    @Test public void nullAndEmptyMetadataCannotInventMainlandStatus() {
        assertFalse(ChinaDiscoveryCandidatePolicy.isClearlyMainland(
                null,null,null,null,null));
        assertTrue(ChinaDiscoveryCandidatePolicy.isClearlyMainland(
                "china",null,null,null,null));
    }
}
