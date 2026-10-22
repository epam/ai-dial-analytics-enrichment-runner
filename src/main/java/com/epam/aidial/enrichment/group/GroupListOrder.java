package com.epam.aidial.enrichment.group;

import com.epam.aidial.enrichment.exception.UnknownEnumValueException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;

import java.util.Arrays;

/**
 * Which end of a pipeline's groups the listing starts from, by {@code last_activity_at}: {@code oldest} — the groups
 * quiet longest, the readiness sweep's own order and the listing's default — or {@code newest}, the groups an operator
 * is usually asking about.
 */
@Getter
@RequiredArgsConstructor
public enum GroupListOrder {
    OLDEST("oldest", Sort.Direction.ASC),
    NEWEST("newest", Sort.Direction.DESC);

    /** The spelling of the {@code order} query parameter, also written into the cursor. */
    private final String wireValue;
    /** The direction of the {@code (last_activity_at, group_key)} keyset. */
    private final Sort.Direction direction;

    /** Parses the wire spelling, case-insensitively. */
    public static GroupListOrder fromWireValue(String value) {
        for (GroupListOrder order : values()) {
            if (order.wireValue.equalsIgnoreCase(value)) {
                return order;
            }
        }
        throw new UnknownEnumValueException(Arrays.stream(values()).map(GroupListOrder::getWireValue).toList());
    }
}
