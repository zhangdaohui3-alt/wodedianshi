package com.wodedianshi.tv;

import org.junit.Test;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class SingleChannelCandidateSearchTest {
    @Test public void onlineFallbackUsesOnlyKnownStationFamilyAndCompleteSmallBody(){
        assertEquals(0,SingleChannelCandidateSearch.preferredOnlineFeedIndex(new SingleChannelCandidateSearch.Target("CCTV-2","央视","","")));
        assertEquals(2,SingleChannelCandidateSearch.preferredOnlineFeedIndex(new SingleChannelCandidateSearch.Target("湖南卫视","卫视","","")));
        assertEquals(5,SingleChannelCandidateSearch.preferredOnlineFeedIndex(new SingleChannelCandidateSearch.Target("福州新闻综合","福建地方台","","")));
        assertEquals(10,SingleChannelCandidateSearch.preferredOnlineFeedIndex(new SingleChannelCandidateSearch.Target("香港新闻","香港","","")));
        assertEquals(-1,SingleChannelCandidateSearch.preferredOnlineFeedIndex(new SingleChannelCandidateSearch.Target("Unknown","","","")));
        assertTrue(SingleChannelCandidateSearch.onlineResponseAllowed(200,null,"identity",-1));
        assertFalse(SingleChannelCandidateSearch.onlineResponseAllowed(206,"bytes 0-9/20","identity",10));
        assertFalse(SingleChannelCandidateSearch.onlineResponseAllowed(200,null,"gzip",10));
        assertFalse(SingleChannelCandidateSearch.onlineResponseAllowed(200,null,null,262145));
        assertTrue(SingleChannelCandidateSearch.completeOnlineBody(10,10));
        assertTrue(SingleChannelCandidateSearch.completeOnlineBody(-1,10));
        assertFalse(SingleChannelCandidateSearch.completeOnlineBody(10,9));
        assertFalse(SingleChannelCandidateSearch.completeOnlineBody(-1,262145));
    }
    private String entry(String name,String url){
        return "#EXTINF:-1 group-title=\"央视\","+name+"\n"+url+"\n";
    }
    @Test public void scansOnlyMatchingNewEndpointsWithinCandidateBudget()throws Exception{
        String body=entry("CCTV-3","https://wrong.example/live")
            +entry("CCTV-2","https://old.example/live")
            +entry("中央二套","https://new.example/live")
            +entry("CCTV-2","https://NEW.example:443/live")
            +entry("CCTV-2","https://second.example/live")
            +entry("CCTV-2","https://third.example/live");
        ArrayList<SingleChannelCandidateSearch.Candidate> result=new ArrayList<>();
        int scanned=SingleChannelCandidateSearch.scan(new StringReader(body),
            new SingleChannelCandidateSearch.Target("CCTV-2","央视","",""),
            "china",Collections.singletonList("https://old.example/live"),20,2,result,()->false);
        assertEquals(5,scanned);
        assertEquals(2,result.size());
        assertEquals("https://new.example/live",result.get(0).url);
        assertEquals("https://second.example/live",result.get(1).url);
        assertEquals("china",result.get(0).scope);
    }
    @Test public void rowBudgetAndCancellationAreIndependent()throws Exception{
        String body=entry("CCTV-3","https://wrong.example/live")
            +entry("CCTV-2","https://right.example/live");
        ArrayList<SingleChannelCandidateSearch.Candidate> result=new ArrayList<>();
        SingleChannelCandidateSearch.Target target=new SingleChannelCandidateSearch.Target("CCTV-2","央视","","");
        assertEquals(1,SingleChannelCandidateSearch.scan(new StringReader(body),target,
            "china",Collections.emptyList(),1,8,result,()->false));
        assertTrue(result.isEmpty());
        try{
            SingleChannelCandidateSearch.scan(new StringReader(body),target,
                "china",Collections.emptyList(),10,8,result,()->true);
            fail("Expected cancellation");
        }catch(SourceProbeTimePolicy.PausedException expected){}
        assertTrue(result.isEmpty());
    }
}
