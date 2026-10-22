package com.epam.aidial.enrichment.spend;

import com.epam.aidial.enrichment.configuration.SpendProperties;
import com.epam.aidial.enrichment.support.CapturingAppender;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpendPauseGateTest {

    private final SpendService spendService = mock(SpendService.class);

    private SpendPauseGate gate(boolean enabled, long callBudget, long tokenBudget) {
        return new SpendPauseGate(spendService, new SpendProperties(
                new SpendProperties.Pause(enabled), new SpendProperties.Budget(callBudget, tokenBudget)));
    }

    @Test
    void disabledGateIsNeverOverBudget() {
        assertThat(gate(false, 1, 1).isOverBudget("r1")).isFalse();
    }

    @Test
    void overCallBudgetTrips() {
        when(spendService.today("r1")).thenReturn(new DailySpend(50, 0, 0));
        assertThat(gate(true, 50, 0).isOverBudget("r1")).isTrue();
    }

    @Test
    void overTokenBudgetTrips() {
        when(spendService.today("r1")).thenReturn(new DailySpend(0, 1000, 0));
        assertThat(gate(true, 0, 500).isOverBudget("r1")).isTrue();
    }

    @Test
    void underBothBudgetsDoesNotTrip() {
        when(spendService.today("r1")).thenReturn(new DailySpend(5, 5, 0));
        assertThat(gate(true, 100, 100).isOverBudget("r1")).isFalse();
    }

    /**
     * Several fires against a closed gate log one line with the values that closed it; the fires after it say
     * nothing until the gate opens, which logs once too.
     */
    @Test
    void theGateLogsOncePerTransitionWithItsValues() {
        SpendPauseGate gate = gate(true, 50, 1000);
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            when(spendService.today("r1")).thenReturn(new DailySpend(10, 100, 0));
            gate.isOverBudget("r1");
            assertThat(gateLines(appender)).isEmpty();

            when(spendService.today("r1")).thenReturn(new DailySpend(50, 400, 0));
            gate.isOverBudget("r1");
            gate.isOverBudget("r1");
            gate.isOverBudget("r1");
            assertThat(gateLines(appender)).containsExactly("spend gate closed: pipeline=r1 calls=50/50 tokens=400/1000");

            when(spendService.today("r1")).thenReturn(new DailySpend(0, 0, 0));
            gate.isOverBudget("r1");
            gate.isOverBudget("r1");
            assertThat(gateLines(appender)).containsExactly(
                    "spend gate closed: pipeline=r1 calls=50/50 tokens=400/1000",
                    "spend gate open: pipeline=r1 calls=0/50 tokens=0/1000");
        } finally {
            appender.detach(Level.INFO);
        }
    }

    /** A pipeline that leaves the synced set is forgotten, so re-enabled over its budget it is announced again. */
    @Test
    void anEvictedPipelineIsAnnouncedAfresh() {
        SpendPauseGate gate = gate(true, 50, 1000);
        CapturingAppender appender = CapturingAppender.attachAtTrace();
        try {
            when(spendService.today("r1")).thenReturn(new DailySpend(50, 400, 0));
            gate.isOverBudget("r1");
            gate.evict("r1");
            gate.isOverBudget("r1");
            assertThat(gateLines(appender)).hasSize(2).allMatch(m -> m.startsWith("spend gate closed: pipeline=r1"));
        } finally {
            appender.detach(Level.INFO);
        }
    }

    private static List<String> gateLines(CapturingAppender appender) {
        return appender.messages().stream().filter(m -> m != null && m.startsWith("spend gate")).toList();
    }
}
