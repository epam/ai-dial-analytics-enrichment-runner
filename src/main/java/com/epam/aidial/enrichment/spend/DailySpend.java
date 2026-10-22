package com.epam.aidial.enrichment.spend;

/**
 * A pipeline's aggregated spend for a single day.
 *
 * @param calls    successful model calls, which are what the daily call budget counts
 * @param tokens   token usage of those calls
 * @param failures failed model calls, counted apart and never charged to the budget
 */
public record DailySpend(long calls, long tokens, long failures) {
}
