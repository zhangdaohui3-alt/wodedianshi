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

}
