package com.epam.aidial.enrichment.group;

import com.epam.aidial.enrichment.exception.InvalidGroupCursorException;
import com.epam.aidial.enrichment.persistence.GroupStateEntity;
import com.epam.aidial.enrichment.persistence.GroupStateRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The group-state listing: a pipeline's groups by {@code last_activity_at}, oldest or newest first, with
 * {@code group_key} breaking ties, paged by keyset. Every page carries the pipeline's group count, which a cursor never
 * narrows.
 *
 * <p>Unlike the DLQ listing's {@code created_at}, {@code last_activity_at} moves forward as a group gains members, so
 * a group active during a walk can move past the cursor: seen again oldest first, passed over newest first.
 */
@Service
@RequiredArgsConstructor
public class GroupListingService {

    public static final int DEFAULT_LIMIT = 100;
    /** An operator page, not a bulk export; the cursor walks further. */
    public static final int MAX_LIMIT = 500;

    private final GroupStateRepository groupStateRepository;

    /**
     * {@code limit} is clamped to {@code 1..}{@value #MAX_LIMIT}; one extra row is read to learn whether another page
     * exists. A null {@code order} is {@link GroupListOrder#OLDEST}. A cursor issued under the other order is refused.
     * Repeatable read puts the page and its count on one snapshot, so a page never disagrees with its own
     * {@code total}.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupPage list(String pipelineName, int limit, GroupListOrder order, String cursor) {
        GroupListOrder resolved = order == null ? GroupListOrder.OLDEST : order;
        int pageSize = Math.clamp(limit, 1, MAX_LIMIT);
        GroupCursor after = StringUtils.isBlank(cursor) ? null : GroupCursor.decode(cursor.trim());
        if (after != null && after.order() != resolved) {
            throw new InvalidGroupCursorException();
        }

        List<GroupStateEntity> rows = read(pipelineName, resolved, after, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<GroupStateEntity> groups = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasMore) {
            GroupStateEntity last = groups.getLast();
            nextCursor = new GroupCursor(resolved, last.getLastActivityAt(), last.getGroupKey()).encode();
        }
        return new GroupPage(groups, nextCursor, hasMore, groupStateRepository.countByPipelineName(pipelineName));
    }

    private List<GroupStateEntity> read(String pipelineName, GroupListOrder order, GroupCursor after, int probe) {
        if (after == null) {
            return groupStateRepository.findByPipelineName(
                    pipelineName, PageRequest.of(0, probe, Sort.by(order.getDirection(), "lastActivityAt", "groupKey")));
        }
        PageRequest page = PageRequest.of(0, probe);
        return order.getDirection().isAscending()
                ? groupStateRepository.listAfterAsc(pipelineName, after.lastActivityAt(), after.groupKey(), page)
                : groupStateRepository.listAfterDesc(pipelineName, after.lastActivityAt(), after.groupKey(), page);
    }
}
