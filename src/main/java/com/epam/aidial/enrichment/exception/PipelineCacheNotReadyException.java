package com.epam.aidial.enrichment.exception;

/**
 * An operator verb that needs to know a pipeline arrived before this process had synced the registry even once.
 * Mapped to {@code 503 pipeline_cache_cold}.
 *
 * <p>Its own answer rather than a {@code 404}: an empty cache at startup is the runner's own ignorance, and
 * reading it as a miss would tell an operator in the seconds after a boot that their pipeline does not exist.
 * The work lanes' equivalent is {@link PipelineCacheColdException}, which holds an item rather than refusing a
 * request.
 */
public class PipelineCacheNotReadyException extends RuntimeException {

    public PipelineCacheNotReadyException() {
        super("The pipeline cache has not been populated since this process started; retry once the first "
                + "pipeline sync has completed");
    }
}
