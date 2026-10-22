package com.epam.aidial.enrichment.persistence;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.GroupStateWriter.GroupCounts;
import com.epam.aidial.enrichment.persistence.GroupStateWriter.Readiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime view's group counts agree with the sweep's own selection for every readiness shape: idle only, with
 * staleness, with a signal, and with and without a ceiling. The fixture holds one group per way of being ready or not,
 * so a parameter bound out of order moves a count.
 */
@SpringBootTest
class GroupStateCountsIntegrationTest {

    private static final String PIPELINE = "counts";
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);
    private static final String OTHER_PIPELINE = "counts-other";

    @Autowired
    private GroupStateWriter writer;

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
    void seed() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, dlq_item, run_history, group_state");
        Instant quiet = NOW.minus(Duration.ofHours(1));
        group("never-computed-quiet", 5, null, quiet, null, false, null, 0);
        group("never-computed-signalled", 5, null, NOW, null, true, null, 0);
        group("stale", 5, 3L, NOW, NOW.minus(Duration.ofHours(2)), false, null, 0);
        group("recently-computed", 5, 3L, NOW, NOW.minus(Duration.ofMinutes(5)), false, null, 0);
        group("at-ceiling", 5, 3L, quiet, NOW.minus(Duration.ofMinutes(5)), false, TODAY, 1);
        group("clean", 2, 2L, quiet, quiet, false, TODAY, 1);
        // Over the ceiling by count, but yesterday's: the day comparison alone lets it through, and it is not today's.
        group("at-ceiling-yesterday", 5, 3L, quiet, NOW.minus(Duration.ofMinutes(4)), false, TODAY.minusDays(1), 5);
        // Another pipeline's dirty, ready group, at the ceiling today: it must move none of this one's counts.
        group(OTHER_PIPELINE, "other-dirty", 5, null, quiet, NOW, true, TODAY, 7);
    }

    static Stream<Arguments> shapes() {
        return Stream.of(true, false).flatMap(staleness -> Stream.of(true, false).flatMap(signal ->
                Stream.of(1, null).map(ceiling -> Arguments.of(staleness, signal, ceiling))));
    }

    @ParameterizedTest(name = "staleness={0} signal={1} ceiling={2}")
    @MethodSource("shapes")
    void countsAgreeWithTheSweepsSelection(boolean staleness, boolean signal, Integer ceiling) {
        Readiness readiness = new Readiness(NOW.minus(Duration.ofMinutes(10)),
                staleness ? NOW.minus(Duration.ofMinutes(30)) : null, signal);

        GroupCounts counts = writer.counts(PIPELINE, readiness, TODAY, ceiling);

        // Six of this pipeline's groups are dirty; the other pipeline's dirty group would make it seven.
        assertThat(counts.pending()).isEqualTo(6);
        assertThat(counts.ready()).isEqualTo(writer.findDirtyAndReady(PIPELINE, readiness, TODAY, ceiling, 1000).size());
        assertThat(counts.ceilingBlocked()).isEqualTo(ceiling == null ? 0 : writer.countCeilingBlocked(PIPELINE, readiness, TODAY, ceiling));
        assertThat(counts.ready() + counts.ceilingBlocked() + counts.waitingIdle()).isEqualTo(counts.pending());
        // The fixture is built so that every branch moves the count: idle, plus the signal, plus staleness.
        long expectedReadyOrBlocked = 3 + (signal ? 1 : 0) + (staleness ? 1 : 0);
        assertThat(counts.ready() + counts.ceilingBlocked()).isEqualTo(expectedReadyOrBlocked);
        // Only today's count blocks: the group at the ceiling yesterday is ready, and the other pipeline's group at
        // today's ceiling is not counted here.
        assertThat(counts.ceilingBlocked()).isEqualTo(ceiling == null ? 0 : 1);
        assertThat(counts.ready()).isEqualTo(expectedReadyOrBlocked - counts.ceilingBlocked());
    }

    private void group(String key, long version, Long computedVersion, Instant lastActivity, Instant computedAt,
                       boolean signalled, LocalDate evaluationsDay, int evaluations) {
        group(PIPELINE, key, version, computedVersion, lastActivity, computedAt, signalled, evaluationsDay, evaluations);
    }

    private void group(String pipeline, String key, long version, Long computedVersion, Instant lastActivity, Instant computedAt,
                       boolean signalled, LocalDate evaluationsDay, int evaluations) {
        jdbcTemplate.update("""
                        INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at, computed_version,
                                                 computed_at, signalled, evaluations_day, evaluations)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, pipeline, key, version, Timestamp.from(lastActivity), computedVersion,
                computedAt == null ? null : Timestamp.from(computedAt), signalled, evaluationsDay, evaluations);
    }
}
