package com.wodedianshi.tv;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class PlaybackEngineCoordinatorTest {
    private PlaybackEnginePolicy.Candidate candidate(PlaybackEnginePolicy.Engine engine) {
        return new PlaybackEnginePolicy.Candidate(engine, PlaybackEnginePolicy.DecoderMode.SOFTWARE);
    }

    @Test public void releasesOldEngineBeforeCreatingNextAndInvalidatesOldGeneration() throws Exception {
        List<String> events = new ArrayList<>();
        PlaybackEngineCoordinator coordinator = new PlaybackEngineCoordinator(candidate -> {
            events.add("create:" + candidate.engine);
            return () -> events.add("release:" + candidate.engine);
        });

        PlaybackEngineCoordinator.Activation first = coordinator.activate(candidate(PlaybackEnginePolicy.Engine.MPV));
        assertTrue(coordinator.isCurrent(first.generation));
        PlaybackEngineCoordinator.Activation second = coordinator.activate(candidate(PlaybackEnginePolicy.Engine.IJK));

        assertEquals(Arrays.asList("create:MPV", "release:MPV", "create:IJK"), events);
        assertFalse(coordinator.isCurrent(first.generation));
        assertTrue(coordinator.isCurrent(second.generation));
        assertEquals(PlaybackEnginePolicy.Engine.IJK, coordinator.activeCandidate().engine);
    }

    @Test public void failedReleasePreventsCreationOfSecondEngine() throws Exception {
        final int[] creates = {0};
        PlaybackEngineCoordinator coordinator = new PlaybackEngineCoordinator(candidate -> {
            creates[0]++;
            if (creates[0] == 1) return () -> { throw new IllegalStateException("release failed"); };
            return () -> {};
        });
        coordinator.activate(candidate(PlaybackEnginePolicy.Engine.MPV));

        try {
            coordinator.activate(candidate(PlaybackEnginePolicy.Engine.LIBVLC));
            fail("Expected release failure");
        } catch (IllegalStateException expected) {
            assertEquals("release failed", expected.getMessage());
        }
        assertEquals("second engine must not be created while first may still own resources", 1, creates[0]);
        assertNull(coordinator.activeCandidate());
    }

    @Test public void failedCreationLeavesNoActiveEngine() throws Exception {
        PlaybackEngineCoordinator coordinator = new PlaybackEngineCoordinator(candidate -> {
            throw new Exception("native library unavailable");
        });
        try {
            coordinator.activate(candidate(PlaybackEnginePolicy.Engine.MPV));
            fail("Expected create failure");
        } catch (Exception expected) {
            assertEquals("native library unavailable", expected.getMessage());
        }
        assertNull(coordinator.activeCandidate());
    }

    @Test public void releaseIsIdempotentAndInvalidatesGeneration() throws Exception {
        final int[] releases = {0};
        PlaybackEngineCoordinator coordinator = new PlaybackEngineCoordinator(candidate -> () -> releases[0]++);
        PlaybackEngineCoordinator.Activation activation = coordinator.activate(candidate(PlaybackEnginePolicy.Engine.MPV));
        coordinator.release();
        coordinator.release();
        assertFalse(coordinator.isCurrent(activation.generation));
        assertEquals(1, releases[0]);
        assertNull(coordinator.activeCandidate());
    }
}
