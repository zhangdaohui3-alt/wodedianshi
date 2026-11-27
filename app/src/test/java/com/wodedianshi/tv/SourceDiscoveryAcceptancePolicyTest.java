package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class SourceDiscoveryAcceptancePolicyTest {
    @Test public void failedProbeMustNotBecomeNewPublishedChannel() {
        assertFalse(SourceDiscoveryAcceptancePolicy.mayPromoteNewCandidate(false));
    }

    @Test public void passingPreflightMayBeStagedButIsNotDevicePlaybackProof() {
        assertTrue(SourceDiscoveryAcceptancePolicy.mayPromoteNewCandidate(true));
    }

    @Test public void semanticVersionForcesRescanOfLegacyUnvalidatedStaging() {
        assertEquals("validated-new-candidates-v3",
                SourceDiscoveryAcceptancePolicy.BUILD_POLICY_VERSION);
    }
}
