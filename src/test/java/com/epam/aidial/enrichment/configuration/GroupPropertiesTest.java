package com.epam.aidial.enrichment.configuration;

import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.support.TestPipelines;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupPropertiesTest {

    /** A sub-second fixed delay would fire every group pipeline's mark scan on every scheduler poll. */
    @Test
    void subSecondOrMissingScanCadenceFailsBinding() {
        for (Duration cadence : Arrays.asList(null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(1))) {
            assertThatThrownBy(() -> properties(cadence))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("group.scan-cadence");
        }
    }

    @Test
    void positiveScanCadenceBinds() {
        assertThat(properties(Duration.ofSeconds(1)).scanCadence()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void absentBudgetUsesTheRunnerDefaultUnderEitherPreset() {
        GroupProperties properties = properties(Duration.ofMinutes(1));

        assertThat(properties.resolveInputTokenBudget(groupPipeline(null).build())).isEqualTo(60_000);
        assertThat(properties.resolveInputTokenBudget(groupPipeline(null).appRoute("insights").build()))
                .isEqualTo(60_000);
    }

    @Test
    void declaredBudgetOverridesTheDefault() {
        GroupProperties properties = properties(Duration.ofMinutes(1));

        assertThat(properties.resolveInputTokenBudget(groupPipeline(1_000).build())).isEqualTo(1_000);
        assertThat(properties.resolveInputTokenBudget(groupPipeline(1_000).appRoute("insights").build()))
                .isEqualTo(1_000);
    }

    /** 0 is a value — "do not trim" — not an absence, so it must not fall through to the default. */
    @Test
    void declaredZeroSwitchesTrimmingOff() {
        assertThat(properties(Duration.ofMinutes(1)).resolveInputTokenBudget(groupPipeline(0).build())).isZero();
    }

    /** A negative knob is a producer defect; reading it as 0 would silently drop the guard. */
    @Test
    void negativeBudgetFallsBackToTheDefault() {
        GroupProperties properties = properties(Duration.ofMinutes(1));

        assertThat(properties.resolveInputTokenBudget(groupPipeline(-1).build())).isEqualTo(60_000);
        assertThat(properties.resolveInputTokenBudget(groupPipeline(-1).appRoute("insights").build()))
                .isEqualTo(60_000);
    }

    private static TestPipelines.Builder groupPipeline(Integer inputTokenBudget) {
        return TestPipelines.builder("g1").triggerKind(TriggerKind.GROUP).groupBy("chat_id").grainKey("chat_id")
                .inputTokenBudget(inputTokenBudget);
    }

    private static GroupProperties properties(Duration scanCadence) {
        return new GroupProperties(500, 0, Duration.ofDays(7), Duration.ofMinutes(10), 200, 60_000, scanCadence);
    }
}
