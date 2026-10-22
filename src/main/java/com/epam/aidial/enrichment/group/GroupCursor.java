package com.epam.aidial.enrichment.group;

import com.epam.aidial.enrichment.exception.InvalidGroupCursorException;
import com.epam.aidial.enrichment.persistence.KeysetCursors;

import java.time.Instant;

/**
 * A position in the group listing: the last group a page returned, in the listing's {@code (last_activity_at,
 * group_key)} order, together with the direction that order was walked in. The next page reads strictly past it.
 *
 * <p>On the wire it is opaque: unpadded base64url of {@code v1:<order>:<epochSecond>.<nanos>:<groupKey>}. The key is
 * last and the split is bounded, so a key containing {@code :} or {@code /} survives, and so does an empty or blank
 * key: any key a page can end on yields a cursor this decodes. Unsigned, like the DLQ cursor, for the same reason: it
 * repositions a read-only listing the caller can already read in full. Unlike the DLQ cursor it is bound to its order —
 * under the other direction "past this group" selects the complementary set, and the walk would silently restart.
 */
public record GroupCursor(GroupListOrder order, Instant lastActivityAt, String groupKey) {

    private static final String VERSION = "v1";

    public String encode() {
        return KeysetCursors.encode(
                VERSION + ":" + order.getWireValue() + ":" + KeysetCursors.formatInstant(lastActivityAt) + ":" + groupKey);
    }

    /** Decodes a cursor this listing issued; anything else is an {@link InvalidGroupCursorException}. */
    public static GroupCursor decode(String cursor) {
        try {
            String[] parts = KeysetCursors.decode(cursor).split(":", 4);
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw new InvalidGroupCursorException();
            }
            GroupListOrder order = GroupListOrder.fromWireValue(parts[1]);
            return new GroupCursor(order, KeysetCursors.parseInstant(parts[2]), parts[3]);
        } catch (IllegalArgumentException e) {
            // Also covers an unknown order: UnknownEnumValueException is an IllegalArgumentException.
            throw new InvalidGroupCursorException();
        }
    }
}
