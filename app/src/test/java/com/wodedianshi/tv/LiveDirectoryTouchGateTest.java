package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveDirectoryTouchGateTest {
    @Test public void retainsOnlyNewestPublicationDuringGesture(){
        LiveDirectoryTouchGate gate=new LiveDirectoryTouchGate();
        Runnable older=()->{},newer=()->{};
        assertFalse(gate.defer(older));
        gate.begin();
        assertTrue(gate.defer(older));
        assertTrue(gate.defer(newer));
        assertSame(newer,gate.end());
        assertNull(gate.end());
        assertFalse(gate.defer(newer));
    }

    @Test public void deferredPublicationCanBeDeferredAgainByNextGesture(){
        LiveDirectoryTouchGate gate=new LiveDirectoryTouchGate();
        Runnable publication=()->{};
        gate.begin();
        assertTrue(gate.defer(publication));
        Runnable released=gate.end();
        gate.begin();
        assertTrue(gate.defer(released));
        assertSame(publication,gate.end());
    }
}
