package com.epam.aidial.enrichment.group;

import com.epam.aidial.enrichment.persistence.GroupStateEntity;

import java.util.List;

/** One page of the group listing: its groups, where the next page starts (null on the last), and the pipeline's total. */
public record GroupPage(List<GroupStateEntity> groups, String nextCursor, boolean hasMore, long total) {
}
