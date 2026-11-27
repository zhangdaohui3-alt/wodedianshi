package com.wodedianshi.tv;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class InitialSharedProbeScoreAndroidTest {
    @Test public void sharedObservationPreservesExactAliasScoresAndPacedPrior() throws Exception {
        String a="https://EXAMPLE.invalid/live",b="https://example.invalid/live";
        assertEquals(SourceSelectionPolicy.endpointKey(a),SourceSelectionPolicy.endpointKey(b));
        String ak=StableKey.url(a),bk=StableKey.url(b);
        assertNotEquals(ak,bk);
        org.json.JSONObject scores=new org.json.JSONObject();
        SourceUpdateWorker.ProbeResult result=new SourceUpdateWorker.ProbeResult();
        result.valid=true;result.score=1200;result.paced=true;
        scores.put(ak,1900);
        SourceUpdateWorker.storePreflightScore(scores,ak,result);
        SourceUpdateWorker.storePreflightScore(scores,bk,result);
        assertEquals(1900.0,scores.getDouble(ak),0.0);
        assertEquals(1200.0,scores.getDouble(bk),0.0);
        result.paced=false;result.valid=false;
        SourceUpdateWorker.storePreflightScore(scores,ak,result);
        SourceUpdateWorker.storePreflightScore(scores,bk,result);
        assertEquals(-500.0,scores.getDouble(ak),0.0);
        assertEquals(-500.0,scores.getDouble(bk),0.0);
    }

    @Test public void sharedRankingUsesConfirmedPlaybackAndRejectsFailedProbe() throws Exception {
        String a="https://example.invalid/live",b="https://example.invalid/1080p";
        String ak=StableKey.url(a),bk=StableKey.url(b);
        long now=100000000L;
        org.json.JSONObject scores=new org.json.JSONObject().put(ak,600).put(bk,900);
        org.json.JSONObject confirmed=new org.json.JSONObject().put("attempts",4)
            .put("ready",4).put("firstFrames",4).put("stablePlays",3)
            .put("lastStable",now).put("updated",now);
        org.json.JSONObject readyOnly=new org.json.JSONObject().put("attempts",4)
            .put("ready",4).put("updated",now);
        org.json.JSONObject durable=new org.json.JSONObject().put(ak,confirmed).put(bk,readyOnly);
        java.util.Map<String,org.json.JSONObject> memory=new java.util.HashMap<>();
        memory.put(ak,confirmed);memory.put(bk,readyOnly);
        double actual=SourceQualityScorer.rankScore(a,scores,durable::optJSONObject,now);
        assertTrue(actual>SourceQualityScorer.rankScore(b,scores,durable::optJSONObject,now));
        assertEquals(actual,SourceQualityScorer.rankScore(a,scores,memory::get,now),0.0);
        assertTrue(actual>SourceQualityScorer.rankScore(a,scores,memory::get,now+31L*86400000L));
        scores.put(ak,-500);
        assertEquals(-500.0,SourceQualityScorer.rankScore(a,scores,key->{
            fail("Failed probe must not consult playback history");return null;
        },now),0.0);
        assertEquals(12.0,SourceQualityScorer.rankScore(b,new org.json.JSONObject(),key->null,now),0.0);
    }

    @Test public void legacyHashCollisionCannotTransferQualityBetweenDistinctUrls() throws Exception {
        String a="https://example.invalid/Aa",b="https://example.invalid/BB";
        assertEquals(StableKey.legacyUrl(a),StableKey.legacyUrl(b));
        assertNotEquals(StableKey.url(a),StableKey.url(b));
        org.json.JSONObject scores=new org.json.JSONObject().put(StableKey.legacyUrl(a),1800);
        assertEquals(0.0,SourceQualityScorer.rankScore(b,scores,key->null,1000L),0.0);
        SourceUpdateWorker.ProbeResult result=new SourceUpdateWorker.ProbeResult();
        result.valid=true;result.paced=true;result.score=700;
        SourceUpdateWorker.storePreflightScore(scores,StableKey.url(a),result);
        assertEquals(700,scores.getInt(StableKey.url(a)));
        assertEquals(0.0,SourceQualityScorer.rankScore(b,scores,key->null,1000L),0.0);
    }

    @Test public void playbackHistoryRetentionKeepsMostRecentAndUsesStableTieOrder() throws Exception {
        java.util.Map<String,org.json.JSONObject> history=new java.util.HashMap<>();
        history.put("stale",new org.json.JSONObject().put("updated",1L));
        history.put("a",new org.json.JSONObject().put("updated",100L));
        history.put("b",new org.json.JSONObject().put("updated",100L));
        org.json.JSONObject newest=new org.json.JSONObject().put("updated",200L).put("stablePlays",3);
        history.put("recent",newest);
        SourceMemoryStore.trimPlaybackHistory(history,4);
        assertEquals(4,history.size());
        SourceMemoryStore.trimPlaybackHistory(history,2);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("b","recent")),history.keySet());
        assertSame(newest,history.get("recent"));
        assertEquals(3,history.get("recent").getInt("stablePlays"));
        SourceMemoryStore.trimPlaybackHistory(history,2);
        assertEquals(2,history.size());
    }

}
