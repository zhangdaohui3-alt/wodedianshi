package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChinaDiscoveryCandidatePolicyTest {
    private static final int MIXED = SourceDiscoveryFeedCatalog.MIXED_MAINLAND_FEED_INDEX;

    @Test public void mixedFeedRanksMainlandBeforeHmtAndForeign() {
        assertEquals(0,ChinaDiscoveryCandidatePolicy.initialProbePriority(
                MIXED,"china","上虞新闻综合","中国大陆 · 新闻","",""));
        assertEquals(1,ChinaDiscoveryCandidatePolicy.initialProbePriority(
                MIXED,"china","RTHK","Hong Kong","香港电台",""));
        assertEquals(2,ChinaDiscoveryCandidatePolicy.initialProbePriority(
                MIXED,"global","BBC News","International · News","",""));
        assertEquals(2,ChinaDiscoveryCandidatePolicy.initialProbePriority(
                MIXED,"global","Unidentified TV","News","",""));
    }

    @Test public void sortingMixedFeedKeepsEveryRouteAndIsStableWithinPriority() {
        String[][] entries={
            {"BBC News","global","International"},
            {"上海新闻","china","地方"},
            {"CNN","global","News"},
            {"福建福州综合","china","地方"},
            {"RTHK","china","Hong Kong"}
        };
        ArrayList<String[]> ordered=new ArrayList<>(Arrays.asList(entries));
        ordered.sort((a,b)->Integer.compare(
            ChinaDiscoveryCandidatePolicy.initialProbePriority(
                MIXED,a[1],a[0],a[2],"",""),
            ChinaDiscoveryCandidatePolicy.initialProbePriority(
                MIXED,b[1],b[0],b[2],"","")));
        assertEquals(5,ordered.size());
        assertEquals("上海新闻",ordered.get(0)[0]);
        assertEquals("福建福州综合",ordered.get(1)[0]);
        assertEquals("RTHK",ordered.get(2)[0]);
        assertEquals("BBC News",ordered.get(3)[0]);
        assertEquals("CNN",ordered.get(4)[0]);
    }

    @Test public void dedicatedCountryFeedsKeepOriginalCandidateOrder() {
        assertEquals(0,ChinaDiscoveryCandidatePolicy.initialProbePriority(
                0,"global","Mislabelled HD","News","",""));
        assertEquals(0,ChinaDiscoveryCandidatePolicy.initialProbePriority(
                SourceDiscoveryFeedCatalog.CHINESE_REGION_FEED_COUNT,
                "global","BBC News","News","",""));
    }

    @Test public void unknownMetadataIsNotInventedAsMainland() {
        assertFalse(ChinaDiscoveryCandidatePolicy.isClearlyMainland(
                null,null,null,null,null));
        assertTrue(ChinaDiscoveryCandidatePolicy.isClearlyMainland(
                "china","福州新闻","福建地方台",null,null));
        assertFalse(ChinaDiscoveryCandidatePolicy.isClearlyMainland(
                "china","RTHK","Hong Kong",null,null));
    }
}
