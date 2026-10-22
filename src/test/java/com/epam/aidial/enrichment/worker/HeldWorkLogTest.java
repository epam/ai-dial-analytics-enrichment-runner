package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.support.SettableClock;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A held condition re-holds every due item every {@link WorkHold#DELAY}; the log must carry its start and then a
 * count at most every five minutes, not a line per hold.
 */
class HeldWorkLogTest {

    private static final String CAUSE = "Holding chunk c1: ADAS is incompatible with this build's contract (no echo)";

    private final SettableClock clock = new SettableClock(Instant.parse("2026-09-29T10:00:00Z"));
    private final HeldWorkLog heldLog = new HeldWorkLog(clock);
    private CapturingAppender appender;

    @BeforeEach
    void attach() {
        appender = CapturingAppender.attachAtTrace();
    }

    @AfterEach
    void detach() {
        appender.detach(Level.INFO);
    }

    @Test
    void tenMinutesOfHoldsLogTheFirstAndAtMostTwoCounts() {
        // Three lanes' items re-held every 30s for ten minutes: 60 holds.
        for (int tick = 0; tick < 20; tick++) {
            heldLog.held("Chunk c1", CAUSE);
            heldLog.held("Group item g1", CAUSE);
            heldLog.held("Upsert item u1", CAUSE);
            clock.advance(WorkHold.DELAY);
        }

        List<String> lines = heldLines();
        assertThat(lines).hasSizeBetween(2, 3);
        assertThat(lines.getFirst()).startsWith("Chunk c1 held; retrying in 30.0s").endsWith(CAUSE);
        assertThat(lines.subList(1, lines.size())).allSatisfy(line -> assertThat(line).contains("work item hold(s) in the last 5m"));
    }

    @Test
    void conditionThatEndedStartsOverWithFullLine() {
        heldLog.held("Chunk c1", CAUSE);
        clock.advance(WorkHold.DELAY);
        heldLog.held("Chunk c1", CAUSE);

        // Quiet for longer than the report interval: the condition is over.
        clock.advance(HeldWorkLog.REPORT_INTERVAL.plus(Duration.ofSeconds(1)));
        heldLog.held("Chunk c2", CAUSE);

        List<String> lines = heldLines();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith("Chunk c2 held; retrying in 30.0s");
    }

    private List<String> heldLines() {
        return appender.messages().stream()
                .filter(m -> m != null && (m.contains(" held; retrying in ") || m.contains("work item hold(s)")))
                .toList();
    }
}
