package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.adas.dto.ScanRequest;
import com.epam.aidial.enrichment.exception.BackfillWindowException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackfillWindowTest {

    private static final Instant EARLY = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant LATE = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void invertedWindowIsRejected() {
        assertThatThrownBy(() -> new BackfillWindow(LATE, EARLY))
                .isInstanceOf(BackfillWindowException.class)
                .hasMessageContaining("strictly before");
    }

    @Test
    void equalBoundsAreRejected() {
        // Half-open (from, to] means from == to selects nothing, which is a mistake rather than a request.
        assertThatThrownBy(() -> new BackfillWindow(EARLY, EARLY))
                .isInstanceOf(BackfillWindowException.class);
    }

    @Test
    void orderedWindowKeepsItsBounds() {
        BackfillWindow window = BackfillWindow.of(EARLY, LATE);
        assertThat(window.from()).isEqualTo(EARLY);
        assertThat(window.to()).isEqualTo(LATE);
    }

    @Test
    void oneSidedWindowIsAllowed() {
        assertThat(BackfillWindow.of(EARLY, null).to()).isNull();
        assertThat(BackfillWindow.of(null, LATE).from()).isNull();
    }

    @Test
    void absentWindowIsUnbounded() {
        assertThat(BackfillWindow.of(null, null)).isEqualTo(BackfillWindow.UNBOUNDED);
    }

    @Test
    void scanRequestConvenienceConstructorLeavesTheWindowAbsent() {
        // The live tick must keep sending an unwindowed request, unchanged by this milestone.
        ScanRequest request = new ScanRequest("c1", 100);
        assertThat(request.from()).isNull();
        assertThat(request.to()).isNull();
    }
}
