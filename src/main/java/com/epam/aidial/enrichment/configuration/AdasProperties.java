package com.epam.aidial.enrichment.configuration;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the ADAS integration ({@code adas.*}). The runner reads pipelines, scans sources, and
 * writes enrichment results through ADAS; all values are env-driven via {@code application.yml}.
 *
 * @param baseUrl          ADAS base URL (e.g. {@code http://localhost:5000})
 * @param apiKey           DIAL {@code Api-Key} presented to ADAS (may equal the DIAL model-call key)
 * @param connectTimeoutMs HTTP connect timeout in milliseconds
 * @param readTimeoutMs    HTTP read timeout in milliseconds
 */
@ConfigurationProperties(prefix = "adas")
public record AdasProperties(
        String baseUrl,
        String apiKey,
        int connectTimeoutMs,
        int readTimeoutMs) {

    // Note: adas.pipelines.poll-interval-ms / initial-delay-ms are consumed directly by PipelineSyncService's
    // @Scheduled placeholders, so they are intentionally not bound here.

    /** Redacts {@code apiKey} so an accidental log or actuator dump never leaks the credential. */
    @Override
    public @NonNull String toString() {
        return "AdasProperties[baseUrl=" + baseUrl + ", apiKey=***, connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs + "]";
    }
}
