package com.wodedianshi.tv;

import java.util.Objects;

/**
 * Owns exactly one playback-engine session at a time.
 *
 * This platform-neutral lifecycle gate deliberately does not create Android
 * views or native players. Concrete adapters are added only after their ABI,
 * native dependencies and surface lifecycle have been verified.
 */
public final class PlaybackEngineCoordinator {
    public interface Session {
        /** Must synchronously stop playback, detach callbacks/surfaces and release native resources. */
        void stopAndRelease();
    }

    public interface Factory {
        Session create(PlaybackEnginePolicy.Candidate candidate) throws Exception;
    }

    public static final class Activation {
        public final long generation;
        public final PlaybackEnginePolicy.Candidate candidate;
        public final Session session;

        private Activation(long generation, PlaybackEnginePolicy.Candidate candidate, Session session) {
            this.generation = generation;
            this.candidate = candidate;
            this.session = session;
        }
    }

    private final Factory factory;
    private long generation;
    private Activation active;

    public PlaybackEngineCoordinator(Factory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Releases the previous owner before creating the next one. A failed
     * release aborts activation: never risk two engines owning one surface.
     */
    public synchronized Activation activate(PlaybackEnginePolicy.Candidate candidate) throws Exception {
        Objects.requireNonNull(candidate, "candidate");
        releaseActiveLocked();
        long token = ++generation;
        Session created = factory.create(candidate);
        if (created == null) {
            generation++;
            throw new IllegalStateException("Engine factory returned no session");
        }
        active = new Activation(token, candidate, created);
        return active;
    }

    /** Invalidates callbacks from the current or any previous playback attempt. */
    public synchronized boolean isCurrent(long token) {
        return active != null && active.generation == token && generation == token;
    }

    /** Idempotent teardown; invalidates the active generation before releasing. */
    public synchronized void release() {
        generation++;
        releaseActiveLocked();
    }

    public synchronized PlaybackEnginePolicy.Candidate activeCandidate() {
        return active == null ? null : active.candidate;
    }

    private void releaseActiveLocked() {
        Activation previous = active;
        active = null;
        if (previous != null) {
            previous.session.stopAndRelease();
        }
    }
}
