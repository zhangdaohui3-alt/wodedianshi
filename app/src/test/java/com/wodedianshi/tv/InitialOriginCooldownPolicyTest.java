package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class InitialOriginCooldownPolicyTest {
    @Test public void distinctFailuresTemporarilyCoolOnlySameOrigin() {
        InitialOriginCooldownPolicy p=new InitialOriginCooldownPolicy();
        String a=InitialOriginCooldownPolicy.origin("https://EXAMPLE.org/a");
        assertEquals(a,InitialOriginCooldownPolicy.origin("https://example.org:443/b"));
        assertNotEquals(a,InitialOriginCooldownPolicy.origin("http://example.org/a"));
        assertNotEquals(a,InitialOriginCooldownPolicy.origin("https://example.org:8443/a"));
        p.observe(a,"one",1000,true,false);
        p.observe(a,"one",2000,true,false);
        p.observe(a,"two",3000,true,false);
        assertFalse(p.cooling(a,3000));
        p.observe(a,"three",4000,true,false);
        assertTrue(p.cooling(a,4000));
        assertFalse(p.cooling("other",4000));
        assertTrue(p.cooling(a,33999));
        assertFalse(p.cooling(a,34000));
        p.observe(a,"four",34000,true,false);
        assertFalse(p.cooling(a,34000));
    }

    @Test public void reachableOriginAndExpiredFailureWindowResetHealth() {
        InitialOriginCooldownPolicy p=new InitialOriginCooldownPolicy();
        String a="origin";
        p.observe(a,"a",1,true,false);
        p.observe(a,"b",2,true,false);
        p.observe(a,"c",30001,true,false);
        assertFalse(p.cooling(a,30001));
        p.observe(a,"d",30002,true,false);
        p.observe(a,"e",30003,true,false);
        assertTrue(p.cooling(a,30003));
        p.observe(a,"f",30004,false,true);
        assertFalse(p.cooling(a,30004));
        p.observe(a,"a",30005,false,false);
        assertFalse(p.cooling(a,30005));
        p.clear();
        assertFalse(p.cooling(a,30006));
    }

    @Test public void neverDeferCoreReplayViewingSavedOrRoutineWork() {
        assertTrue(InitialOriginCooldownPolicy.mayDefer(true,false,false,false,false));
        assertFalse(InitialOriginCooldownPolicy.mayDefer(true,true,false,false,false));
        assertFalse(InitialOriginCooldownPolicy.mayDefer(true,false,true,false,false));
        assertFalse(InitialOriginCooldownPolicy.mayDefer(true,false,false,true,false));
        assertFalse(InitialOriginCooldownPolicy.mayDefer(true,false,false,false,true));
        assertFalse(InitialOriginCooldownPolicy.mayDefer(false,false,false,false,false));
    }

    @Test public void explicitServerPressureCoolsOnlyOriginAndSurvivesConcurrentSuccess(){
        InitialOriginCooldownPolicy p=new InitialOriginCooldownPolicy();
        p.observePressure("origin",1000);
        assertTrue(p.cooling("origin",1000));
        assertFalse(p.cooling("other",1000));
        p.observe("origin","concurrent",2000,false,true);
        assertTrue(p.cooling("origin",30999));
        assertFalse(p.cooling("origin",31000));
        p.observe("origin","new",31001,false,true);
        assertFalse(p.cooling("origin",31001));
    }
}
