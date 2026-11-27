package com.wodedianshi.tv;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Coalesces replaceable state, not incremental events. No I/O under its monitor. */
final class LatestStateWriter<T> {
    private T desired, committed;
    private boolean initialized, scheduled;
    private Executor executor;
    private Consumer<T> writer;

    synchronized T initialize(T persisted) {
        if (!initialized) {
            desired = committed = persisted;
            initialized = true;
        }
        return desired;
    }

    synchronized void offer(T value, Executor executor, Consumer<T> writer) {
        initialized = true;
        desired = Objects.requireNonNull(value);
        this.executor = executor;
        this.writer = writer;
        schedule();
    }

    private void schedule() {
        if (scheduled || Objects.equals(desired, committed)) return;
        scheduled = true;
        try {
            executor.execute(this::writeOnce);
        } catch (RuntimeException rejected) {
            scheduled = false;
            // The desired value remains dirty; a subsequent offer can retry.
        }
    }

    private void writeOnce() {
        final T value;
        final Consumer<T> operation;
        synchronized (this) {
            value = desired;
            operation = writer;
            if (Objects.equals(value, committed)) {
                scheduled = false;
                return;
            }
        }
        boolean success = false;
        try {
            operation.accept(value);
            success = true;
        } catch (RuntimeException failure) {
            // The operation logs the failure; never crash the executor thread.
            // Keep the value dirty for the next offer.
        } finally {
            synchronized (this) {
                if (success) committed = value;
                scheduled = false;
                // Yield to other queued writes. Do not spin on a failed value.
                if (!Objects.equals(value, desired)) schedule();
            }
        }
    }
}
