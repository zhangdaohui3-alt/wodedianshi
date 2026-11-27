package com.wodedianshi.tv;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class InitialSharedProbeScoreAndroidTest {
    @Test public void initialScoreCapacityRetainsValidatedBeforeArbitraryHistoricalKeys() throws Exception {
        org.json.JSONObject scores=new org.json.JSONObject();
        String failed=StableKey.url("https://example.invalid/failed");
        String provisional=StableKey.url("https://example.invalid/provisional");
        String valid=StableKey.url("https://example.invalid/valid");
        String recent=StableKey.url("https://example.invalid/recent");
        scores.put(failed,-500);scores.put(provisional,350);
        scores.put("legacy",900);scores.put(valid,1200);scores.put(recent,-500);
        java.util.Set<String> keep=new java.util.LinkedHashSet<>();
        keep.add(recent);
        SourceUpdateWorker.fillRetainedScoreKeys(scores,keep,2,true,
            java.util.Collections.singleton(provisional),()->false);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(recent,valid)),keep);
        assertEquals(5,scores.length());
    }

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

}
