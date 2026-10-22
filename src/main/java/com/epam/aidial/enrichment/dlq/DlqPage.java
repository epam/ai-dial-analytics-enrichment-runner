package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.persistence.DlqCounts;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;

import java.util.List;

/** One page of the DLQ listing: its items, where the next page starts (null on the last), and the filter's counts. */
public record DlqPage(List<DlqItemEntity> items, String nextCursor, boolean hasMore, DlqCounts counts) {
}
