package com.epam.aidial.enrichment.exception;

import lombok.Getter;

/**
 * ADAS refused a dry run's trial-input read — a declaration that would not arm, a caller who may not read what it
 * reads, an unknown pipeline — and the dry run answers with ADAS's own status, code and message.
 */
@Getter
public class DryRunAdasRefusalException extends RuntimeException {

    private final int status;
    private final String code;

    public DryRunAdasRefusalException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
