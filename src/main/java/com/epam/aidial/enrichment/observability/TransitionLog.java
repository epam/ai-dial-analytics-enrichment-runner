package com.epam.aidial.enrichment.observability;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Remembers the last reported state per key so a condition evaluated on every tick is logged when it
 * <em>changes</em>, not on every evaluation. A line repeated every fire for a condition that has not moved buries
 * the transition that started it; the persistent state is the heartbeat's to report.
 *
 * <p>A key never recorded is in {@code initial}, so the first observation of the resting state logs nothing and
 * the first observation of anything else logs once. Per-process and in-memory: a restart re-reports a condition
 * still in force, which is the right answer for a reader who starts from the restart.
 *
 * @param <S> the reported state
 */
public final class TransitionLog<S> {

    private final ConcurrentMap<String, S> last = new ConcurrentHashMap<>();
    private final S initial;

    public TransitionLog(S initial) {
        this.initial = Objects.requireNonNull(initial, "initial");
    }

    /**
     * Records {@code state} for {@code key} and answers whether it differs from the state last recorded for it.
     * Atomic per key: of two threads observing the same transition, exactly one is told to log it.
     */
    public boolean changed(String key, S state) {
        S previous = last.put(key, Objects.requireNonNull(state, "state"));
        return !Objects.equals(previous == null ? initial : previous, state);
    }

    /** Forgets a key, returning it to {@code initial}. */
    public void forget(String key) {
        last.remove(key);
    }
}
