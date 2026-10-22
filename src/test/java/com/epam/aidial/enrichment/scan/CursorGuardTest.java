package com.epam.aidial.enrichment.scan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cursor is the runner's only record of how far it has read, and the two ways to get it wrong have very
 * different costs: a missed stall busy-loops one fire, while a cleared cursor silently re-scans a pipeline's entire
 * history and re-derives every label in it.
 */
class CursorGuardTest {

    @Test
    void newCursorAdvances() {
        assertThat(CursorGuard.advance("c1", "c2")).isEqualTo("c2");
    }

    @Test
    void absentOrBlankCursorKeepsTheStoredPosition() {
        // The bug this exists to prevent: writing the null through sets the cursor to "the source start".
        assertThat(CursorGuard.advance("c1", null)).isEqualTo("c1");
        assertThat(CursorGuard.advance("c1", "")).isEqualTo("c1");
        assertThat(CursorGuard.advance("c1", "   ")).isEqualTo("c1");
    }

    @Test
    void firstCursorAdvancesFromNothing() {
        assertThat(CursorGuard.advance(null, "c1")).isEqualTo("c1");
        assertThat(CursorGuard.advance(null, null)).isNull();
    }

    @Test
    void unchangedAbsentAndBlankCursorsAllCountAsStalled() {
        assertThat(CursorGuard.isStalled("c1", "c1")).isTrue();
        assertThat(CursorGuard.isStalled("c1", null)).isTrue();
        // Blank counts too: ADAS omitting next_cursor and sending "" are the same statement in two shapes, and
        // only null used to be caught — so a blank one drained the whole per-fire budget over one window.
        assertThat(CursorGuard.isStalled("c1", "")).isTrue();
        assertThat(CursorGuard.isStalled(null, null)).isTrue();
    }

    @Test
    void movedCursorIsNotStalled() {
        assertThat(CursorGuard.isStalled("c1", "c2")).isFalse();
        assertThat(CursorGuard.isStalled(null, "c1")).isFalse();
    }
}
