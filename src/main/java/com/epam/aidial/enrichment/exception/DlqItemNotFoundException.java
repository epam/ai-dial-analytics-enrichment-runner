package com.epam.aidial.enrichment.exception;

/** A DLQ item id was not found. */
public class DlqItemNotFoundException extends RuntimeException {

    public DlqItemNotFoundException(long id) {
        super("DLQ item not found: " + id);
    }
}
