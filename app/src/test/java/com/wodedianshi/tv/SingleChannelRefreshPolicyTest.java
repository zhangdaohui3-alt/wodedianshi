package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class SingleChannelRefreshPolicyTest {
    @org.junit.Test public void refreshDeadlineAppliesThroughoutRequest(){
        org.junit.Assert.assertFalse(SingleChannelRefreshPolicy.requestExpired(89999L,90000L));
        org.junit.Assert.assertTrue(SingleChannelRefreshPolicy.requestExpired(90000L,90000L));
        org.junit.Assert.assertTrue(SingleChannelRefreshPolicy.requestExpired(90001L,90000L));
        org.junit.Assert.assertFalse(SingleChannelRefreshPolicy.shouldCancelRequest(false,7,7,89999L,90000L));
        org.junit.Assert.assertTrue(SingleChannelRefreshPolicy.shouldCancelRequest(false,7,7,90000L,90000L));
        org.junit.Assert.assertTrue(SingleChannelRefreshPolicy.shouldCancelRequest(true,7,7,1000L,90000L));
        org.junit.Assert.assertTrue(SingleChannelRefreshPolicy.shouldCancelRequest(false,7,8,1000L,90000L));
    }

    private boolean match(String n,String g,String id,String guide,String n2,String g2,String id2,String guide2){
        return SingleChannelRefreshPolicy.matchesStation(n,g,id,guide,n2,g2,id2,guide2);
    }
    @Test public void recognizedNationalStationsMustAgreeAcrossMetadata(){
        assertTrue(match("CCTV-2","央视","","","中央二套","央视","",""));
        assertTrue(match("CCTV-12","央视","","","中央电视台十二套","央视","",""));
        assertFalse(match("CCTV-2","央视","","","中央二套专题","央视","",""));
        assertFalse(match("CCTV-2","央视","","","中央二套","央视","","CCTV-3"));
        assertFalse(match("CCTV-2","央视","","","CCTV-3","央视","",""));
        assertFalse(match("CCTV-2","央视","","","CCTV-2","央视","","CCTV-3"));
        assertFalse(match("CCTV-4","央视","","","CCTV-4 Asia","央视","",""));
        assertFalse(match("湖南卫视","卫视","","","湖南卫视","香港","",""));
    }
    @Test public void localAndGenericIdentityIsConservative(){
        assertTrue(match("深圳新闻","广东","","","深圳新闻","广东","",""));
        assertFalse(match("深圳新闻","广东","","","深圳综合","广东","",""));
        assertFalse(match("福州新闻","福建","news.fuzhou","",
            "福州新闻","江西","news.fuzhou",""));
        assertFalse(match("新闻综合","地方台","","","新闻综合","地方台","",""));
        assertFalse(match("Local News","美国","local.news","",
            "Local News","美国","local.news",""));
        assertTrue(match("Example Music","国际","example.music","",
            "Example Music","国际","example.music",""));
        assertFalse(match("Example Music","国际","example.music","",
            "Other Music","国际","example.music",""));
        assertFalse(match("Example Music","香港","example.music","",
            "Example Music","台湾","example.music",""));
    }

    @Test public void contradictoryGeographyNeverFallsBackToSharedEpgId(){
        assertNull(ChannelNamePolicy.localProvinceEvidence("河北 Fuzhou News"));
        assertNull(ChannelNamePolicy.localProvinceEvidence("江西 福州新闻"));
        assertEquals("",ChannelNamePolicy.localProvinceEvidence("Fuzhou News"));
        assertEquals("福建",ChannelNamePolicy.localProvinceEvidence("福建 Fuzhou News"));
        assertFalse(match("Fuzhou News","河北","news.fuzhou","",
            "Fuzhou News","河北","news.fuzhou",""));
        assertFalse(match("Fuzhou News","福建","news.fuzhou","",
            "Fuzhou News","河北","news.fuzhou",""));
        assertTrue(match("泰州新闻","江苏","","","泰州新闻","江苏","",""));
    }

    @Test public void sameProvinceConflictingCityEvidenceRejectsRefresh(){
        assertEquals("深圳",ChannelNamePolicy.localCityEvidence("深圳新闻 Shenzhen News","广东"));
        assertEquals("深圳",ChannelNamePolicy.localCityEvidence("Shenzhen News","广东"));
        assertNull(ChannelNamePolicy.localCityEvidence("深圳新闻 Guangzhou News","广东"));
        assertNull(ChannelNamePolicy.localCityEvidence("深圳新闻 广州新闻","广东"));
        assertNull(ChannelNamePolicy.localCityEvidence("Shenzhen News Guangzhou News","广东"));
        assertFalse(match("深圳新闻","广东","news.shenzhen","",
            "深圳新闻","广东","news.shenzhen","Guangzhou News"));
        assertFalse(match("深圳新闻","广东","news.shenzhen","Guangzhou News",
            "深圳新闻","广东","news.shenzhen","Guangzhou News"));
        assertTrue(match("深圳新闻","广东","news.shenzhen","Shenzhen News",
            "深圳新闻","广东","news.shenzhen","Shenzhen News"));
    }

    @Test public void appendOnlyUsesFreeSlotsInVerifiedQualityOrder(){
        java.util.List<String> old=Arrays.asList("https://a.example/live","https://b.example/live");
        assertEquals(Arrays.asList("https://c.example/live","https://d.example/live"),
            SingleChannelRefreshPolicy.appendCandidates(old,Arrays.asList(
                "https://A.example:443/live","https://c.example/live","https://c.example/live",
                "file:///bad","https:///bad","https://d.example/live","https://e.example/live"),4));
        assertEquals(Arrays.asList("https://a.example/live","https://b.example/live"),old);
    }
    @Test public void fullOrEmptyRefreshDoesNotRemoveAnyExistingRoute(){
        assertTrue(SingleChannelRefreshPolicy.appendCandidates(
            Arrays.asList("https://a.example/live","https://b.example/live"),
            Collections.singletonList("https://new.example/live"),2).isEmpty());
        assertTrue(SingleChannelRefreshPolicy.appendCandidates(
            Collections.singletonList("https://a.example/live"),Collections.emptyList(),8).isEmpty());
    }
    @Test public void fullRefreshReplacesOnlyFailedLowerQualityUnprotectedSlots(){
        String primary="https://primary.example/live", selected="https://selected.example/live",
            playing="https://playing.example/live", failed="https://failed.example/live",
            good="https://good.example/live", fresh="https://fresh.example/live";
        java.util.List<String> old=Arrays.asList(primary,selected,playing,failed,good);
        java.util.Set<String> eligible=new java.util.HashSet<>(Arrays.asList(primary,selected,playing,failed));
        java.util.Comparator<String> bestFirst=java.util.Comparator.comparingInt(url->url.equals(fresh)?0:1);
        assertEquals(Arrays.asList(primary,selected,playing,fresh,good),
            SingleChannelRefreshPolicy.planRefresh(old,Arrays.asList(fresh,good),5,primary,selected,playing,eligible,bestFirst));
        assertEquals(old,SingleChannelRefreshPolicy.planRefresh(old,Collections.singletonList(fresh),5,
            primary,selected,playing,Collections.emptySet(),bestFirst));
        assertEquals(old,SingleChannelRefreshPolicy.planRefresh(old,Collections.singletonList(fresh),5,
            primary,selected,playing,eligible,bestFirst.reversed()));
        assertEquals(Arrays.asList(primary,selected,playing,failed,good,fresh),
            SingleChannelRefreshPolicy.planRefresh(old,Collections.singletonList(fresh),6,
                primary,selected,playing,eligible,bestFirst));
        assertEquals(old,SingleChannelRefreshPolicy.planRefresh(old,Collections.singletonList("https://FAILED.example:443/live"),5,
            primary,selected,playing,eligible,bestFirst));
        assertEquals(Arrays.asList(primary,selected,playing,failed,good),old);
    }

}
