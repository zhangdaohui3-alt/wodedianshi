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
    @Test public void resumeBeyondFormer4096RowBoundaryAndStopAtExactCandidate()throws Exception{
        StringBuilder body=new StringBuilder("#EXTM3U\n");
        for(int i=0;i<4097;i++)body.append(entry("CCTV-3","https://wrong.example/"+i));
        body.append(entry("CCTV-2","https://right.example/1"));
        body.append(entry("CCTV-2","https://right.example/2"));
        SingleChannelCandidateSearch.Target target=new SingleChannelCandidateSearch.Target("CCTV-2","央视","","");
        ArrayList<SingleChannelCandidateSearch.Candidate> rows=new ArrayList<>();
        assertEquals(2,SingleChannelCandidateSearch.scanFrom(new StringReader(body.toString()),4096,target,
            "china",Collections.emptyList(),10,1,rows,()->false));
        assertEquals("https://right.example/1",rows.get(0).url);
        assertEquals(4098,SingleChannelCandidateSearch.nextOffset(4096,2,10,4099,true));
        rows.clear();
        assertEquals(1,SingleChannelCandidateSearch.scanFrom(new StringReader(body.toString()),4098,target,
            "china",Collections.emptyList(),10,1,rows,()->false));
        assertEquals("https://right.example/2",rows.get(0).url);
        assertEquals(0,SingleChannelCandidateSearch.nextOffset(4098,1,10,4099,true));
        assertEquals(4096,SingleChannelCandidateSearch.resumeOffset("v1","v1",4096));
        assertEquals(0,SingleChannelCandidateSearch.resumeOffset("v1","v2",4096));
        assertEquals(0,SingleChannelCandidateSearch.resumeOffset("","",4096));
        assertEquals(0,SingleChannelCandidateSearch.nextOffset(4098,1,10,-1,false));
    }

    @Test public void indexedResumeDoesNotSkipUnconsumedRecords()throws Exception{
        java.io.File file=java.io.File.createTempFile("single-channel-index",".m3u");
        try{
            StringBuilder body=new StringBuilder("#EXTM3U\n");
            for(int i=0;i<4097;i++)body.append(entry("CCTV-3","https://wrong.example/"+i));
            body.append(entry("CCTV-2","https://right.example/1"));
            body.append(entry("CCTV-2","https://right.example/2"));
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){
                output.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            PlaylistFeedIndex.readPage(file,0,1,ignored->{});
            assertEquals(4099,PlaylistFeedIndex.indexedEntryCount(file));
            SingleChannelCandidateSearch.Target target=new SingleChannelCandidateSearch.Target("CCTV-2","央视","","");
            ArrayList<SingleChannelCandidateSearch.Candidate> found=new ArrayList<>();
            assertEquals(2,SingleChannelCandidateSearch.scanIndexed(file,4096,target,"china",
                Collections.emptyList(),64,1,found,()->false));
            assertEquals("https://right.example/1",found.get(0).url);
            found.clear();
            assertEquals(1,SingleChannelCandidateSearch.scanIndexed(file,4098,target,"china",
                Collections.emptyList(),64,1,found,()->false));
            assertEquals("https://right.example/2",found.get(0).url);
        }finally{
            new java.io.File(file.getAbsolutePath()+".index-data").delete();
            new java.io.File(file.getAbsolutePath()+".index-offsets").delete();
            file.delete();
        }
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
