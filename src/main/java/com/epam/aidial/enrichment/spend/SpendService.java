package com.epam.aidial.enrichment.spend;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Best-effort spend accounting into {@code spend_counter}, keyed by (pipeline, pipeline generation, UTC
 * day). Increments are race-free via a Postgres UPSERT; no cost or pricing — usage only.
 *
 * <p><strong>Re-keying the middle component onto the pipeline's {@code generation} changed what a counter
 * row is, not what the daily budget sums.</strong> A declaration edit of any kind now starts a new row,
 * where an evaluator version kept a day's usage on one row across a {@code filter}, trigger, cadence or
 * {@code rate_rpm} change. The pause gate is unaffected, and deliberately: {@link #today} sums
 * {@code WHERE pipeline_name = ? AND day = ?} and therefore across <em>every</em> value of that component,
 * so a generation bump spreads the day's total over more rows rather than resetting it. An operator who has
 * exhausted a pipeline's daily budget does not get a fresh one by editing a knob — the opposite would be a
 * money bug, so it is stated here rather than left to be inferred from the SQL.
 */
@Service
@RequiredArgsConstructor
public class SpendService {

    /** The only usage unit phase-1's chat_completion path records; other presets may add calls/rows. */
    public static final String TOKENS_UNIT = "tokens";

    private static final String UPSERT = """
            INSERT INTO spend_counter (pipeline_name, pipeline_generation, day, calls, usage_amount, usage_unit, failures)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (pipeline_name, pipeline_generation, day) DO UPDATE SET
                calls = spend_counter.calls + EXCLUDED.calls,
                usage_amount = spend_counter.usage_amount + EXCLUDED.usage_amount,
                failures = spend_counter.failures + EXCLUDED.failures
            """;

    private static final String DAILY_TOTALS = """
            SELECT COALESCE(SUM(calls), 0), COALESCE(SUM(usage_amount), 0), COALESCE(SUM(failures), 0)
            FROM spend_counter WHERE pipeline_name = ? AND day = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /** Records one DIAL call for a pipeline generation: its token usage and whether the call failed. */
    @Transactional
    public void record(String pipelineName, long pipelineGeneration, long tokens, boolean failure) {
        record(pipelineName, pipelineGeneration, tokens, TOKENS_UNIT, failure);
    }

    /** The same, with an explicit usage unit ({@code tokens|calls|rows}). */
    @Transactional
    public void record(String pipelineName, long pipelineGeneration, long usageAmount, String usageUnit,
                       boolean failure) {
        jdbcTemplate.update(UPSERT, pipelineName, pipelineGeneration, today(),
                failure ? 0 : 1, usageAmount, usageUnit, failure ? 1 : 0);
    }

    /** A pipeline's successful calls, token usage and failed calls for the current UTC day. */
    public DailySpend today(String pipelineName) {
        DailySpend spend = jdbcTemplate.queryForObject(DAILY_TOTALS,
                (rs, rowNum) -> new DailySpend(rs.getLong(1), rs.getLong(2), rs.getLong(3)), pipelineName, today());
        return spend != null ? spend : new DailySpend(0, 0, 0);
    }

    /** The UTC day on the runner's clock, the same day the runtime view's group counts and the readiness sweep use. */
    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
