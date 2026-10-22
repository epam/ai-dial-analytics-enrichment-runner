package com.epam.aidial.enrichment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed binding for the enrichment worker ({@code worker.*}).
 *
 * @param repairRetries  how many times an invalid model response is re-requested with the schema
 *                       validation error as a corrective message before the row is dead-lettered
 * @param rowParallelism bounded per-chunk row parallelism ({@code 1} processes rows sequentially)
 * @param maxAttempts    how many consecutive chunk failures are retried (with backoff) before the
 *                       chunk is archived to the DLQ
 * @param backoffBase    base delay for the exponential backoff between chunk retries
 */
@ConfigurationProperties(prefix = "worker")
public record WorkerProperties(
        int repairRetries,
        int rowParallelism,
        int maxAttempts,
        Duration backoffBase) {
}
