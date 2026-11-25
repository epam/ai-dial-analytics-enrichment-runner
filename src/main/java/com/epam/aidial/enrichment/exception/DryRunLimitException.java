package com.epam.aidial.enrichment.exception;

import com.epam.aidial.enrichment.web.dto.dryrun.DryRunRequest;

/**
 * A dry-run {@code limit} outside {@code 1}..{@link DryRunRequest#MAX_LIMIT}. Mapped to HTTP 422
 * {@code pipeline_validation_failed}, the status and code ADAS's trial input answers the same body with, so refusing it
 * here rather than after the read changes nothing a caller sees but the round trip.
 */
public class DryRunLimitException extends RuntimeException {

    public DryRunLimitException(int limit) {
        super("`limit` must be between 1 and " + DryRunRequest.MAX_LIMIT + ", got " + limit);
    }
}
