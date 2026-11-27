package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class InitialNetworkConcurrencyPolicyTest {
    @Test public void oneBadOriginDoesNotThrottleHealthyProviders() {
        InitialNetworkConcurrencyPolicy p=new InitialNetworkConcurrencyPolicy();
        for(int i=0;i<12;i++)p.observe("one",1000+i,true,false);
        assertEquals(6,p.limit(6,2000));
        assertEquals(2,p.limit(2,2000));
    }

    @Test public void widespreadRecentFailuresReduceOnlyIdleCeiling() {
        InitialNetworkConcurrencyPolicy p=new InitialNetworkConcurrencyPolicy();
        for(int i=0;i<8;i++)p.observe("origin"+(i%3),1000+i,true,false);
        assertEquals(4,p.limit(6,2000));
        assertEquals(4,p.limit(4,2000));
        assertEquals(1,p.limit(1,2000));
        assertEquals(6,p.limit(6,62000));
    }

    @Test public void recoveryAndInsufficientEvidenceRestoreBaseline() {
        InitialNetworkConcurrencyPolicy p=new InitialNetworkConcurrencyPolicy();
        for(int i=0;i<7;i++)p.observe("origin"+(i%3),1000+i,true,false);
        assertEquals(6,p.limit(6,2000));
        p.observe("other",2000,true,false);
        assertEquals(4,p.limit(6,2000));
        for(int i=0;i<8;i++)p.observe("ok"+i,3000+i,false,true);
        assertEquals(6,p.limit(6,4000));
        p.clear();
        assertEquals(6,p.limit(6,4000));
    }
}
