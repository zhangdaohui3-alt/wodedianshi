package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChinaDiscoveryCandidatePolicyTest {
    private static final int MIXED = SourceDiscoveryFeedCatalog.MIXED_MAINLAND_FEED_INDEX;

    @Test public void countyFeedPrioritizesLocalBeforeNationalBackupWithoutDroppingRows() {
        assertEquals(0,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(
                MIXED,"china","深圳新闻","广东","",""));
        assertEquals(0,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(
                MIXED,"china","County News","China; Local","","CountyNews.cn"));
        assertEquals(1,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(
                MIXED,"china","CCTV-1","央视","",""));
        assertEquals(1,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(
                MIXED,"china","湖南卫视","卫视","",""));
        assertEquals(2,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(
                MIXED,"china","RTHK","Hong Kong","",""));
        assertEquals(3,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(
                MIXED,"global","Local News","US","",""));
        assertEquals(0,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(
                0,"china","CCTV-1","央视","",""));
    }

    @Test public void allBroadMainlandFeedsGiveLocalStationsAnEarlyTurn() {
        for(int i=0;i<SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT;i++){
            boolean broad=i>=MIXED;
            assertEquals(0,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(i,"china","福州新闻","福建","",""));
            assertEquals(broad?1:0,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(i,"china","CCTV-1","央视","",""));
            assertEquals(broad?1:0,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(i,"china","湖南卫视","卫视","",""));
            assertEquals(broad?2:1,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(i,"china","RTHK","Hong Kong","",""));
            assertEquals(broad?3:2,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(i,"global","Local News","US","",""));
        }
        assertEquals(0,ChinaDiscoveryCandidatePolicy.initialCoveragePriority(SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT,"global","BBC News","News","",""));
    }

    @Test public void regionalSchedulingDoesNotInventLocalMembership() {
        assertEquals("福建",ChinaDiscoveryCandidatePolicy.initialCoverageRegion(MIXED,"china","福州新闻","福建","",""));
        assertEquals("广东",ChinaDiscoveryCandidatePolicy.initialCoverageRegion(MIXED,"china","深圳新闻","广东","",""));
        assertEquals("",ChinaDiscoveryCandidatePolicy.initialCoverageRegion(MIXED,"china","CCTV-1","央视","",""));
        assertEquals("",ChinaDiscoveryCandidatePolicy.initialCoverageRegion(MIXED,"china","湖南卫视","卫视","",""));
        assertEquals("",ChinaDiscoveryCandidatePolicy.initialCoverageRegion(MIXED,"china","County News","Local","",""));
        assertEquals("",ChinaDiscoveryCandidatePolicy.initialCoverageRegion(MIXED,"global","Local News","US","",""));
        assertEquals("",ChinaDiscoveryCandidatePolicy.initialCoverageRegion(0,"china","福州新闻","福建","",""));
    }

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

    @Test public void allMainlandStageFeedsPrioritizeChinaButLaterStagesKeepOrder() {
        for(int i=0;i<SourceDiscoveryFeedCatalog.MAINLAND_FEED_COUNT;i++){
            assertEquals(0,ChinaDiscoveryCandidatePolicy.initialProbePriority(
                i,"china","福建福州综合","福建","",""));
            assertEquals(2,ChinaDiscoveryCandidatePolicy.initialProbePriority(
                i,"global","BBC News","News","",""));
        }
        assertEquals(2,ChinaDiscoveryCandidatePolicy.initialProbePriority(
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
