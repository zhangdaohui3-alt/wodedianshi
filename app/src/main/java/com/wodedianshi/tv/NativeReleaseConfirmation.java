package com.wodedianshi.tv;

import java.util.concurrent.atomic.AtomicReference;

/** One native engine lifetime. Never turn an uncertain teardown into success
 * merely because its Java references have already been cleared. */
final class NativeReleaseConfirmation {
    private enum State { NOT_ATTEMPTED, RELEASING, CONFIRMED, UNCONFIRMED }
    private final AtomicReference<State> state =
            new AtomicReference<>(State.NOT_ATTEMPTED);

    boolean mayInitialize() {
        return state.get() == State.NOT_ATTEMPTED;
    }

    boolean beginRelease() {
        return state.compareAndSet(State.NOT_ATTEMPTED, State.RELEASING);
    }

    boolean isConfirmed() {
        return state.get() == State.CONFIRMED;
    }

    void finishRelease(boolean confirmed) {
        state.compareAndSet(State.RELEASING,
                confirmed ? State.CONFIRMED : State.UNCONFIRMED);
    }
}
