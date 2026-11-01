package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.exception.ErrorMessages;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

/** Reads what an ADAS refusal on a pipeline-scoped endpoint means, where its code carries a meaning to act on. */
public final class AdasRefusals {

    /** ADAS's code for a write refused because the pipeline is disabled. */
    static final String PIPELINE_CONFLICT = "pipeline_conflict";

    private AdasRefusals() {
    }

    /**
     * Whether ADAS refused a write because the pipeline is disabled: HTTP 409 {@code pipeline_conflict}, which on
     * {@code …/rows} and {@code …/materialize} ADAS pins to that one meaning. It is a stop that reached ADAS before
     * this runner's next sync did, not a rejection of the rows, so the caller holds the work rather than
     * dead-lettering it.
     */
    public static boolean isPipelineDisabled(RestClientResponseException rejection) {
        return rejection.getStatusCode().value() == HttpStatus.CONFLICT.value()
                && ErrorMessages.adasErrorCode(rejection).filter(PIPELINE_CONFLICT::equals).isPresent();
    }
}
