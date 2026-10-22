package com.epam.aidial.enrichment.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HumanDurationTest {

    @ParameterizedTest
    @CsvSource({
            "0,             0ms",
            "850,           850ms",
            "999,           999ms",
            "1000,          1.0s",
            // The shape the retry backoff actually produces, which ISO-8601 rendered as PT12.517S.
            "12517,         12.5s",
            "59999,         59.9s",
            "60000,         1m",
            // PT2M42.531S, the other shape from the retry log.
            "162531,        2m 43s",
            // Rounds to the whole minute rather than reading "2m 0s".
            "119800,        2m",
            "3600000,       1h",
            "3903000,       1h 5m 3s",
    })
    void formatsAsAnOperatorReadsIt(long millis, String expected) {
        assertThat(HumanDuration.format(Duration.ofMillis(millis))).isEqualTo(expected);
    }

    @Test
    void handlesAbsentAndNegativeDurations() {
        assertThat(HumanDuration.format(null)).isEqualTo("unknown");
        assertThat(HumanDuration.format(Duration.ofMillis(-1500))).isEqualTo("-1.5s");
    }

    /**
     * Every delay the retry backoff can produce renders in the readable grammar — nothing falls through to
     * ISO-8601. Worth asserting over the whole range because that generator, not a caller's literal, is what
     * these log lines actually print.
     */
    @Test
    void everyBackoffDelayIsReadable() {
        for (int failures = 0; failures < 20; failures++) {
            String formatted = HumanDuration.format(RetryBackoff.withJitter(Duration.ofSeconds(10), failures));
            assertThat(formatted).matches("\\d+ms|\\d+\\.\\ds|(\\d+h ?)?(\\d+m)?( ?\\d+s)?");
        }
    }
}
