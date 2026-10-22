package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.exception.UnknownEnumValueException;

import java.util.Arrays;
import java.util.Locale;

/**
 * The lifecycle state of a run in {@code run_history}. The database stores the lowercase form.
 *
 * <p>The transition table lives here, beside the vocabulary, so every verb asks the same question. Guarding one
 * verb of three is not a guard: an unguarded pause writes {@code paused} over a {@code done} run whose
 * {@code finished_at} stays set, and a resume then re-arms it from its end cursor at full model cost.
 */
public enum RunState {
    RUNNING,
    PAUSED,
    DONE,
    CANCELLED;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Only a running run can be paused. */
    public boolean canPause() {
        return this == RUNNING;
    }

    /** Only a paused run can be resumed. */
    public boolean canResume() {
        return this == PAUSED;
    }

    /**
     * A running or a paused run can be cancelled. Wider than pause and resume deliberately: abandoning a paused
     * run is the ordinary way one ends. A finished or cancelled run cannot, since its completion instant is
     * written once.
     */
    public boolean canCancel() {
        return this == RUNNING || this == PAUSED;
    }

    /** Parses the stored or wire spelling, case-insensitively. */
    public static RunState fromDbValue(String value) {
        for (RunState state : values()) {
            if (state.dbValue().equalsIgnoreCase(value)) {
                return state;
            }
        }
        throw new UnknownEnumValueException(Arrays.stream(values()).map(RunState::dbValue).toList());
    }
}
