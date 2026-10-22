package com.epam.aidial.enrichment.exception;

/** A DLQ listing cursor the listing did not issue; see {@link InvalidCursorException}. */
public class InvalidDlqCursorException extends InvalidCursorException {

    public InvalidDlqCursorException() {
        super("The cursor is not one the DLQ listing issued; omit it to list from the newest item");
    }
}
