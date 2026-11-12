package com.wodedianshi.tv;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class PlaybackEnginePolicyTest {
    private final List<PlaybackEnginePolicy.Engine> all=PlaybackEnginePolicy.orderedEngines(true,true);
    @Test public void media3IsPrimaryAndOptionalEnginesAreOptIn() {
        assertEquals(Arrays.asList(PlaybackEnginePolicy.Engine.MEDIA3),PlaybackEnginePolicy.orderedEngines(false,false));
        assertEquals(Arrays.asList(PlaybackEnginePolicy.Engine.MEDIA3,PlaybackEnginePolicy.Engine.IJK),PlaybackEnginePolicy.orderedEngines(true,false));
        assertEquals(Arrays.asList(PlaybackEnginePolicy.Engine.MEDIA3,PlaybackEnginePolicy.Engine.IJK,PlaybackEnginePolicy.Engine.LIBVLC),all);
    }
    @Test public void transportFailureChangesSourceNotEngine() {
        PlaybackEnginePolicy.Decision d=PlaybackEnginePolicy.decide(PlaybackEnginePolicy.FailureKind.TRANSPORT,all,PlaybackEnginePolicy.Engine.MEDIA3);
        assertTrue(d.changeSource); assertFalse(d.tryNextEngine); assertNull(d.nextEngine);
    }
    @Test public void decoderFailureMovesFromMedia3ToIijk() {
        PlaybackEnginePolicy.Decision d=PlaybackEnginePolicy.decide(PlaybackEnginePolicy.FailureKind.DECODER_RUNTIME,all,PlaybackEnginePolicy.Engine.MEDIA3);
        assertFalse(d.changeSource); assertTrue(d.tryNextEngine); assertEquals(PlaybackEnginePolicy.Engine.IJK,d.nextEngine);
    }
    @Test public void renderFailureMovesFromIijkToLibVlc() {
        PlaybackEnginePolicy.Decision d=PlaybackEnginePolicy.decide(PlaybackEnginePolicy.FailureKind.VIDEO_RENDER,all,PlaybackEnginePolicy.Engine.IJK);
        assertTrue(d.tryNextEngine); assertEquals(PlaybackEnginePolicy.Engine.LIBVLC,d.nextEngine);
    }
    @Test public void noEngineIsRepeatedAfterLastCandidate() {
        PlaybackEnginePolicy.Decision d=PlaybackEnginePolicy.decide(PlaybackEnginePolicy.FailureKind.DEMUX,all,PlaybackEnginePolicy.Engine.LIBVLC);
        assertFalse(d.tryNextEngine); assertNull(d.nextEngine);
    }
    @Test public void unknownFailureDoesNotTriggerBlindSwitch() {
        PlaybackEnginePolicy.Decision d=PlaybackEnginePolicy.decide(PlaybackEnginePolicy.FailureKind.UNKNOWN,all,PlaybackEnginePolicy.Engine.MEDIA3);
        assertFalse(d.tryNextEngine); assertFalse(d.changeSource);
    }
    @Test public void unavailableEnginesAreNeverSelected() {
        List<PlaybackEnginePolicy.Engine> available=PlaybackEnginePolicy.orderedEngines(false,true);
        PlaybackEnginePolicy.Decision d=PlaybackEnginePolicy.decide(PlaybackEnginePolicy.FailureKind.DECODER_INITIALIZATION,available,PlaybackEnginePolicy.Engine.MEDIA3);
        assertEquals(PlaybackEnginePolicy.Engine.LIBVLC,d.nextEngine);
    }
}
