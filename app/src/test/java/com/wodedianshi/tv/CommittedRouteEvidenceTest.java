package com.wodedianshi.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=28)
public class CommittedRouteEvidenceTest {
    @Test public void onlyExactRetainedAssociationMayReuseCommittedEvidence() throws Exception {
        String url="https://example.invalid/live?token=A";
        JSONObject row=new JSONObject().put("name","CCTV-1").put("group","央视")
            .put("tvgId","CCTV1").put("tvgName","CCTV-1")
            .put("sources",new JSONArray().put(url))
            .put("backupSourceScopes",new JSONArray().put(new JSONObject().put("url",url)
                .put("scopes",new JSONArray().put("china"))));
        SourceUpdateWorker.Entry same=new SourceUpdateWorker.Entry("CCTV-1",url,"央视",false,"china","CCTV1","CCTV-1");
        assertTrue(SourceUpdateWorker.hasCommittedRouteEvidence(row,same,"CCTV-1"));
        assertFalse(SourceUpdateWorker.hasCommittedRouteEvidence(row,same,"CCTV-10"));
        assertFalse(SourceUpdateWorker.hasCommittedRouteEvidence(row,null,"CCTV-1"));
        assertFalse(SourceUpdateWorker.hasCommittedRouteEvidence(null,same,"CCTV-1"));
        assertFalse(SourceUpdateWorker.hasCommittedRouteEvidence(row,new SourceUpdateWorker.Entry("CCTV-1",url,"央视",false,"global","CCTV1","CCTV-1"),"CCTV-1"));
        assertFalse(SourceUpdateWorker.hasCommittedRouteEvidence(row,new SourceUpdateWorker.Entry("CCTV-1","https://EXAMPLE.invalid:443/live?token=A","央视",false,"china","CCTV1","CCTV-1"),"CCTV-1"));
        assertFalse(SourceUpdateWorker.hasCommittedRouteEvidence(row,new SourceUpdateWorker.Entry("CCTV-1",url,"央视",false,"china","new-id","CCTV-1"),"CCTV-1"));
        assertFalse(SourceUpdateWorker.hasCommittedRouteEvidence(row,new SourceUpdateWorker.Entry("CCTV-1",url,"央视",true,"china","CCTV1","CCTV-1"),"CCTV-1"));
        row.put("sources",new JSONArray());
        assertFalse(SourceUpdateWorker.hasCommittedRouteEvidence(row,same,"CCTV-1"));
    }

    @Test public void retainedRouteReuseRequiresCurrentPositiveNonProvisionalScore() throws Exception {
        String url="https://example.invalid/live";
        String key=StableKey.url(url),legacy=StableKey.legacyUrl(url);
        JSONObject scores=new JSONObject();
        java.util.Set<String> provisional=new java.util.HashSet<>();
        assertFalse(SourceUpdateWorker.mayReuseRetainedPreflight(scores,provisional,url));
        scores.put(legacy,200);
        assertFalse(SourceUpdateWorker.mayReuseRetainedPreflight(scores,provisional,url));
        scores.put(key,200);
        assertTrue(SourceUpdateWorker.mayReuseRetainedPreflight(scores,provisional,url));
        provisional.add(key);
        assertFalse(SourceUpdateWorker.mayReuseRetainedPreflight(scores,provisional,url));
        provisional.clear();provisional.add(legacy);
        assertFalse(SourceUpdateWorker.mayReuseRetainedPreflight(scores,provisional,url));
        provisional.clear();scores.put(key,-500);
        assertFalse(SourceUpdateWorker.mayReuseRetainedPreflight(scores,provisional,url));
        scores.put(key,0);
        assertFalse(SourceUpdateWorker.mayReuseRetainedPreflight(scores,provisional,url));
    }
}
