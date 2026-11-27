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
        assertFalse(gate.reveal(player,"old"));
        assertTrue(gate.reveal(player,"new"));
        assertFalse(gate.reveal(player,"new"));
    }

    @Test public void previousEngineCannotRevealFallbackEvenForSameMedia(){
        LivePictureGate gate=new LivePictureGate();
        Object media3=new Object(),vlc=new Object();
        gate.arm(media3,"same");
        gate.cover();
        gate.arm(vlc,"same");
        assertFalse(gate.reveal(media3,"same"));
        assertTrue(gate.reveal(vlc,"same"));
    }

    @Test public void currentOutputMayReopenAfterLossButOldEngineCannot(){
        LivePictureGate gate=new LivePictureGate();
        Object current=new Object(),old=new Object();
        gate.arm(current,"source");
        assertTrue(gate.reveal(current,"source"));
        gate.cover();
        gate.arm(current,"source");
        assertFalse(gate.reveal(old,"source"));
        assertTrue(gate.reveal(current,"source"));
    }

    @Test public void matchingMediaButPreAssignmentFrameCannotReveal(){
        LivePictureGate gate=new LivePictureGate();
        Object player=new Object();
        gate.arm(player,"current",100L);
        assertFalse(gate.revealRendered(player,"current",99L));
        assertTrue(gate.revealRendered(player,"current",100L));
        assertFalse(gate.revealRendered(player,"current",101L));
    }

    @Test public void releaseInvalidatesQueuedCallbacks(){
        LivePictureGate gate=new LivePictureGate();
        Object engine=new Object();
        gate.arm(engine,"source");
        gate.cover();
        assertFalse(gate.reveal(engine,"source"));
        gate.arm(engine,"");
        assertFalse(gate.reveal(engine,""));
    }
}
