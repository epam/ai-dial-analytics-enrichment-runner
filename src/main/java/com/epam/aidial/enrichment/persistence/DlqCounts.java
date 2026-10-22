package com.epam.aidial.enrichment.persistence;

/**
 * The DLQ listing's counters over one filter: every matching item, and those that carry a payload — the rule
 * behind an item's {@code requeueable} flag, so the counter and the rows never disagree.
 */
public record DlqCounts(long total, long requeueable) {
}
