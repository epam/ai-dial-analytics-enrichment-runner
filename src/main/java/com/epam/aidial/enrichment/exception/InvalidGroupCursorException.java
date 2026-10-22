package com.epam.aidial.enrichment.exception;

/**
 * A group listing cursor the listing did not issue, or one issued under the other {@code order}; see
 * {@link InvalidCursorException}.
 */
public class InvalidGroupCursorException extends InvalidCursorException {

    public InvalidGroupCursorException() {
        super("The cursor is not one the group listing issued for this order; omit it to list from the first page");
    }
}
