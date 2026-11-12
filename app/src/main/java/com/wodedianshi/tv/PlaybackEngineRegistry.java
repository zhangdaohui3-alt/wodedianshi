package com.wodedianshi.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds a deterministic fallback list from engines whose adapter and native
 * runtime have both passed the caller's availability probe.
 *
 * Merely declaring an engine in PlaybackEnginePolicy is not enough to enable
 * it. The probe must verify the ABI-specific libraries and a registered,
 * initialized adapter before returning true.
 */
public final class PlaybackEngineRegistry {
    public interface AvailabilityProbe {
        boolean isReady(PlaybackEnginePolicy.Engine engine,
                        PlaybackEnginePolicy.DecoderMode decoderMode);
    }

    private PlaybackEngineRegistry() {}

    public static List<PlaybackEnginePolicy.Candidate> verifiedFallbacks(
            List<PlaybackEnginePolicy.Candidate> preferredOrder,
            AvailabilityProbe probe) {
        if (preferredOrder == null || preferredOrder.isEmpty() || probe == null) {
            return Collections.emptyList();
        }

        ArrayList<PlaybackEnginePolicy.Candidate> ready = new ArrayList<>();
        for (PlaybackEnginePolicy.Candidate candidate : preferredOrder) {
            if (candidate == null || candidate.engine == null || candidate.decoderMode == null) {
                continue;
            }
            if (candidate.engine == PlaybackEnginePolicy.Engine.MEDIA3 || ready.contains(candidate)) {
                continue;
            }
            boolean available;
            try {
                available = probe.isReady(candidate.engine, candidate.decoderMode);
            } catch (RuntimeException unavailable) {
                // A broken optional engine must never prevent the primary player
                // from starting or suppress other independently verified engines.
                available = false;
            }
            if (available) ready.add(candidate);
        }
        return Collections.unmodifiableList(ready);
    }

    public static List<PlaybackEnginePolicy.Candidate> completeOrder(
            List<PlaybackEnginePolicy.Candidate> preferredOrder,
            AvailabilityProbe probe) {
        return PlaybackEnginePolicy.orderedCandidates(verifiedFallbacks(preferredOrder, probe));
    }
}
