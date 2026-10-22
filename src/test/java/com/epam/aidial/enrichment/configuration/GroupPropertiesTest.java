package com.epam.aidial.enrichment.configuration;

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

    private static GroupProperties properties(Duration scanCadence) {
        return new GroupProperties(500, 0, Duration.ofDays(7), Duration.ofMinutes(10), 200, 60_000, scanCadence);
    }
}
