package com.epam.aidial.enrichment.exception;

/**
 * A listing cursor the listing did not issue, or one in an encoding this build does not read. Mapped to
 * {@code 400 invalid_cursor}, the code ADAS answers a bad scan cursor with; each listing supplies its own message.
 *
 * <p>Refused rather than treated as "from the start": silently restarting a walk shows the operator the same items
 * twice. A dedicated type rather than a {@code ResponseStatusException}, which the catch-all handler would turn into a
 * {@code 500}.
 */
public abstract class InvalidCursorException extends RuntimeException {

    protected InvalidCursorException(String message) {
        super(message);
    }
}
