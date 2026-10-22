package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.exception.DlqFilterException;
import com.epam.aidial.enrichment.persistence.DlqCounts;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The DLQ listing: newest first, narrowed by pipeline, by the backfill run that produced an item, or by lane, and
 * paged by keyset so a walk survives items being archived, requeued and swept underneath it. Every page carries the
 * filter's counts, which a cursor never narrows.
 */
@Service
@RequiredArgsConstructor
public class DlqListingService {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    private final DlqItemRepository dlqItemRepository;

    /**
     * {@code lane=live} together with a {@code run_id} cannot match anything and is refused rather than answered
     * with an empty list, which on a failure listing reads as "nothing failed". {@code limit} is clamped to
     * {@code 1..}{@value #MAX_LIMIT}; one extra row is read to learn whether another page exists. Repeatable read
     * puts the page and its counts on one snapshot, so a page never disagrees with its own {@code total}.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DlqPage list(String pipelineName, String runId, DlqLane lane, int limit, String cursor) {
        String pipeline = StringUtils.trimToNull(pipelineName);
        String run = StringUtils.trimToNull(runId);
        if (lane == DlqLane.LIVE && run != null) {
            throw new DlqFilterException("lane=live selects items with no run, so it cannot be combined with run_id");
        }
        String laneValue = lane == null ? null : lane.dbValue();
        int pageSize = Math.clamp(limit, 1, MAX_LIMIT);
        DlqCursor after = StringUtils.isBlank(cursor) ? null : DlqCursor.decode(cursor.trim());

        Pageable probe = PageRequest.of(0, pageSize + 1);
        List<DlqItemEntity> rows = after == null
                ? dlqItemRepository.list(pipeline, run, laneValue, probe)
                : dlqItemRepository.listAfter(pipeline, run, laneValue, after.createdAt(), after.id(), probe);
        boolean hasMore = rows.size() > pageSize;
        List<DlqItemEntity> items = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasMore) {
            DlqItemEntity last = items.getLast();
            nextCursor = new DlqCursor(last.getCreatedAt(), last.getId()).encode();
        }
        DlqCounts counts = dlqItemRepository.counts(pipeline, run, laneValue);
        return new DlqPage(items, nextCursor, hasMore, counts);
    }
}
