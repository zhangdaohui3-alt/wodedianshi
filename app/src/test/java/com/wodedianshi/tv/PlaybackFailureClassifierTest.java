package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackFailureClassifierTest {
    @Test public void networkAndInvalidMediaResponseErrorsAreTransportFailures() {
        assertEquals(PlaybackEnginePolicy.FailureKind.TRANSPORT, PlaybackFailureClassifier.classify(2000));
        assertEquals(PlaybackEnginePolicy.FailureKind.TRANSPORT, PlaybackFailureClassifier.classify(2001));
        assertEquals(PlaybackEnginePolicy.FailureKind.TRANSPORT, PlaybackFailureClassifier.classify(2002));
        assertEquals(PlaybackEnginePolicy.FailureKind.TRANSPORT, PlaybackFailureClassifier.classify(2003));
        assertEquals(PlaybackEnginePolicy.FailureKind.TRANSPORT, PlaybackFailureClassifier.classify(2004));
        assertEquals(PlaybackEnginePolicy.FailureKind.TRANSPORT, PlaybackFailureClassifier.classify(2005));
        assertEquals(PlaybackEnginePolicy.FailureKind.TRANSPORT, PlaybackFailureClassifier.classify(2008));
    }

    @Test public void permissionAndCleartextPolicyErrorsAreNotMisdiagnosedAsBadSource() {
        assertEquals(PlaybackEnginePolicy.FailureKind.CONFIGURATION, PlaybackFailureClassifier.classify(2006));
        assertEquals(PlaybackEnginePolicy.FailureKind.CONFIGURATION, PlaybackFailureClassifier.classify(2007));
    }

    @Test public void parsingAndContainerErrorsAreSeparated() {
        assertEquals(PlaybackEnginePolicy.FailureKind.DEMUX, PlaybackFailureClassifier.classify(3001));
        assertEquals(PlaybackEnginePolicy.FailureKind.DEMUX, PlaybackFailureClassifier.classify(3002));
        assertEquals(PlaybackEnginePolicy.FailureKind.UNSUPPORTED_CONTAINER_OR_CODEC, PlaybackFailureClassifier.classify(3003));
        assertEquals(PlaybackEnginePolicy.FailureKind.UNSUPPORTED_CONTAINER_OR_CODEC, PlaybackFailureClassifier.classify(3004));
    }

    @Test public void decoderInitializationAndRuntimeAreSeparated() {
        assertEquals(PlaybackEnginePolicy.FailureKind.DECODER_INITIALIZATION, PlaybackFailureClassifier.classify(4001));
        assertEquals(PlaybackEnginePolicy.FailureKind.DECODER_INITIALIZATION, PlaybackFailureClassifier.classify(4002));
        assertEquals(PlaybackEnginePolicy.FailureKind.DECODER_RUNTIME, PlaybackFailureClassifier.classify(4003));
        assertEquals(PlaybackEnginePolicy.FailureKind.UNSUPPORTED_CONTAINER_OR_CODEC, PlaybackFailureClassifier.classify(4004));
        assertEquals(PlaybackEnginePolicy.FailureKind.UNSUPPORTED_CONTAINER_OR_CODEC, PlaybackFailureClassifier.classify(4005));
        assertEquals(PlaybackEnginePolicy.FailureKind.DECODER_RUNTIME, PlaybackFailureClassifier.classify(4006));
    }

    @Test public void audioAndVideoOutputFailuresAreSeparated() {
        assertEquals(PlaybackEnginePolicy.FailureKind.AUDIO_OUTPUT, PlaybackFailureClassifier.classify(5001));
        assertEquals(PlaybackEnginePolicy.FailureKind.AUDIO_OUTPUT, PlaybackFailureClassifier.classify(5002));
        assertEquals(PlaybackEnginePolicy.FailureKind.AUDIO_OUTPUT, PlaybackFailureClassifier.classify(5003));
        assertEquals(PlaybackEnginePolicy.FailureKind.AUDIO_OUTPUT, PlaybackFailureClassifier.classify(5004));
        assertEquals(PlaybackEnginePolicy.FailureKind.VIDEO_RENDER, PlaybackFailureClassifier.classify(7000));
        assertEquals(PlaybackEnginePolicy.FailureKind.VIDEO_RENDER, PlaybackFailureClassifier.classify(7001));
    }

    @Test public void genericTimeoutAndDrmErrorsDoNotTriggerBlindFallback() {
        assertEquals(PlaybackEnginePolicy.FailureKind.UNKNOWN, PlaybackFailureClassifier.classify(1003));
        assertEquals(PlaybackEnginePolicy.FailureKind.UNKNOWN, PlaybackFailureClassifier.classify(6004));
        assertEquals(PlaybackEnginePolicy.FailureKind.UNKNOWN, PlaybackFailureClassifier.classify(123456));
    }
}
