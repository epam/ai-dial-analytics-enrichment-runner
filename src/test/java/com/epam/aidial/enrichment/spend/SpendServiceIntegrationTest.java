package com.epam.aidial.enrichment.spend;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 spend accounting: increments are aggregated per pipeline/day and failures counted separately.
 * PostgreSQL runs in Testcontainers.
 */
@SpringBootTest
class SpendServiceIntegrationTest {

    @Autowired
    private SpendService spendService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE spend_counter");
    }

    @Test
    void recordsAndAggregatesDailySpend() {
        spendService.record("r1", 3, 100, false);
        spendService.record("r1", 3, 50, false);
        spendService.record("r1", 3, 0, true);

        DailySpend spend = spendService.today("r1");
        assertThat(spend.calls()).isEqualTo(2);
        assertThat(spend.tokens()).isEqualTo(150);
        assertThat(spend.failures()).isEqualTo(1);

        Long failures = jdbcTemplate.queryForObject(
                "SELECT failures FROM spend_counter WHERE pipeline_name = 'r1'", Long.class);
        assertThat(failures).isEqualTo(1);

        String usageUnit = jdbcTemplate.queryForObject(
                "SELECT usage_unit FROM spend_counter WHERE pipeline_name = 'r1'", String.class);
        assertThat(usageUnit).isEqualTo("tokens");
    }

    @Test
    void todayIsZeroWhenNothingRecorded() {
        assertThat(spendService.today("absent")).isEqualTo(new DailySpend(0, 0, 0));
    }

    /**
     * The behaviour whose opposite would be a money bug. Re-keying the counter onto the pipeline's
     * {@code generation} means a declaration edit of <em>any</em> kind — including an execution knob an
     * evaluator version never moved for — starts a new counter row. What must not change is what the daily
     * budget sums: the gate reads {@code WHERE pipeline_name = ? AND day = ?} and therefore across every
     * value of that key component, so an operator who has exhausted a pipeline's daily budget does not get a
     * fresh one by editing {@code rate_rpm}.
     *
     * <p>The sum is asserted, not just the row count: a test that only counted rows would pass equally well
     * against a gate that had started reading one generation's row.
     */
    @Test
    void generationBumpStartsNewRowWithoutResettingTheDaysTotal() {
        spendService.record("r-rekey", 17, 100, false);
        spendService.record("r-rekey", 17, 50, false);

        // An operator edits a knob; ADAS bumps the generation; the next call lands on a new counter row.
        spendService.record("r-rekey", 18, 30, false);

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM spend_counter WHERE pipeline_name = 'r-rekey'", Long.class);
        assertThat(rows).isEqualTo(2);

        DailySpend spend = spendService.today("r-rekey");
        assertThat(spend.calls()).isEqualTo(3);
        assertThat(spend.tokens()).isEqualTo(180);
    }

    /** A generation past {@code 2^31} round-trips, which the pre-fold {@code INT} column could not hold. */
    @Test
    void generationBeyondTheIntRangeRoundTrips() {
        spendService.record("r-wide", 3_000_000_000L, 10, false);

        Long stored = jdbcTemplate.queryForObject(
                "SELECT pipeline_generation FROM spend_counter WHERE pipeline_name = 'r-wide'", Long.class);
        assertThat(stored).isEqualTo(3_000_000_000L);
        assertThat(spendService.today("r-wide").tokens()).isEqualTo(10);
    }
}
