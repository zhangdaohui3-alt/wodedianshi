package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class LivePictureGateTest {
    @Test public void oldMediaOnReusedPlayerCannotRevealNextSource(){
        LivePictureGate gate=new LivePictureGate();
        Object player=new Object();
        gate.arm(player,"old");
        gate.cover();
        gate.arm(player,"new");
        assertFalse(gate.reveal(player,"old",0L));
        assertTrue(gate.reveal(player,"new",0L));
        assertFalse(gate.reveal(player,"new",0L));
    }

    @Test public void previousEngineCannotRevealFallbackEvenForSameMedia(){
        LivePictureGate gate=new LivePictureGate();
        Object media3=new Object(),vlc=new Object();
        gate.arm(media3,"same");
        gate.cover();
        gate.arm(vlc,"same");
        assertFalse(gate.reveal(media3,"same",0L));
        assertTrue(gate.reveal(vlc,"same",0L));
    }

    @Test public void currentOutputMayReopenAfterLossButOldEngineCannot(){
        LivePictureGate gate=new LivePictureGate();
        Object current=new Object(),old=new Object();
        gate.arm(current,"source");
        assertTrue(gate.reveal(current,"source",0L));
        gate.cover();
        gate.arm(current,"source");
        assertFalse(gate.reveal(old,"source",0L));
        assertTrue(gate.reveal(current,"source",0L));
    }

    @Test public void matchingMediaButPreAssignmentFrameCannotReveal(){
        LivePictureGate gate=new LivePictureGate();
        Object player=new Object();
        gate.arm(player,"current",100L);
        assertFalse(gate.reveal(player,"current",0L));
        assertFalse(gate.reveal(player,"current",99L));
        assertTrue(gate.reveal(player,"current",100L));
        assertFalse(gate.reveal(player,"current",101L));
    }

    @Test public void releaseInvalidatesQueuedCallbacks(){
        LivePictureGate gate=new LivePictureGate();
        Object engine=new Object();
        gate.arm(engine,"source");
        gate.cover();
        assertFalse(gate.reveal(engine,"source",0L));
        gate.arm(engine,"");
        assertFalse(gate.reveal(engine,"",0L));
    }
}
