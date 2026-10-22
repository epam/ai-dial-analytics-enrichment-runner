package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.exception.InvalidDlqCursorException;
import com.epam.aidial.enrichment.persistence.KeysetCursors;

import java.time.Instant;

/**
 * A position in the DLQ listing: the last item a page returned, in the listing's {@code (created_at DESC, id DESC)}
 * order. The next page reads strictly past it.
 *
 * <p>On the wire it is opaque: unpadded base64url of {@code v1:<epochSecond>.<nanos>:<id>}. The version prefix lets
 * the encoding change later. It is not signed, unlike the ADAS scan cursor: it repositions a read-only listing the
 * caller can already read in full, so a forged one gains nothing. Nor is it bound to the filters — under any filter it
 * means "older than this item".
 */
public record DlqCursor(Instant createdAt, long id) {

    private static final String VERSION = "v1";

    public String encode() {
        return KeysetCursors.encode(VERSION + ":" + KeysetCursors.formatInstant(createdAt) + ":" + id);
    }

    /** Decodes a cursor this listing issued; anything else is an {@link InvalidDlqCursorException}. */
    public static DlqCursor decode(String cursor) {
        try {
            String[] parts = KeysetCursors.decode(cursor).split(":", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new InvalidDlqCursorException();
            }
            Instant createdAt = KeysetCursors.parseInstant(parts[1]);
            long id = Long.parseLong(parts[2]);
            if (id <= 0) {
                throw new InvalidDlqCursorException();
            }
            return new DlqCursor(createdAt, id);
        } catch (IllegalArgumentException e) {
            throw new InvalidDlqCursorException();
        }
    }
}
