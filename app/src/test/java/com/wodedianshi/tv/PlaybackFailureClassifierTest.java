package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackFailureClassifierTest {
    @Test public void networkErrorsAreTransportFailures() {
        assertEquals(PlaybackEnginePolicy.FailureKind.TRANSPORT, PlaybackFailureClassifier.classify(2001));
        assertEquals(PlaybackEnginePolicy.FailureKind.TRANSPORT, PlaybackFailureClassifier.classify(2002));
        assertEquals(PlaybackEnginePolicy.FailureKind.TRANSPORT, PlaybackFailureClassifier.classify(2004));
    }

    @Test public void cleartextPolicyErrorIsNotMisdiagnosedAsBadSource() {
        assertEquals(PlaybackEnginePolicy.FailureKind.CONFIGURATION, PlaybackFailureClassifier.classify(2007));
    }

    @Test public void parsingAndContainerErrorsAreSeparated() {
        assertEquals(PlaybackEnginePolicy.FailureKind.DEMUX, PlaybackFailureClassifier.classify(3001));
        assertEquals(PlaybackEnginePolicy.FailureKind.UNSUPPORTED_CONTAINER_OR_CODEC, PlaybackFailureClassifier.classify(3003));
    }

    @Test public void decoderInitializationAndRuntimeAreSeparated() {
        assertEquals(PlaybackEnginePolicy.FailureKind.DECODER_INITIALIZATION, PlaybackFailureClassifier.classify(4001));
        assertEquals(PlaybackEnginePolicy.FailureKind.DECODER_RUNTIME, PlaybackFailureClassifier.classify(4003));
        assertEquals(PlaybackEnginePolicy.FailureKind.UNSUPPORTED_CONTAINER_OR_CODEC, PlaybackFailureClassifier.classify(4005));
    }

    @Test public void audioAndVideoOutputFailuresAreSeparated() {
        assertEquals(PlaybackEnginePolicy.FailureKind.AUDIO_OUTPUT, PlaybackFailureClassifier.classify(5001));
        assertEquals(PlaybackEnginePolicy.FailureKind.VIDEO_RENDER, PlaybackFailureClassifier.classify(7001));
    }

    @Test public void unknownAndDrmErrorsDoNotTriggerBlindFallback() {
        assertEquals(PlaybackEnginePolicy.FailureKind.UNKNOWN, PlaybackFailureClassifier.classify(6004));
        assertEquals(PlaybackEnginePolicy.FailureKind.UNKNOWN, PlaybackFailureClassifier.classify(123456));
    }
}
