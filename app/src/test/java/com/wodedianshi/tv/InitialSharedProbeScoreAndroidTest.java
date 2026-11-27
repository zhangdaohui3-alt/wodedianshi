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
        SourceUpdateWorker.storePreflightScore(scores,ak,StableKey.legacyUrl(a),result);
        SourceUpdateWorker.storePreflightScore(scores,bk,StableKey.legacyUrl(b),result);
        assertEquals(1900.0,scores.getDouble(ak),0.0);
        assertEquals(1200.0,scores.getDouble(bk),0.0);
        result.paced=false;result.valid=false;
        SourceUpdateWorker.storePreflightScore(scores,ak,StableKey.legacyUrl(a),result);
        SourceUpdateWorker.storePreflightScore(scores,bk,StableKey.legacyUrl(b),result);
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

}
