package com.epam.aidial.enrichment.configuration;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed binding for DIAL Core model calls ({@code dial.*}). One DIAL {@code Api-Key} may serve both
 * ADAS auth and DIAL model calls, so {@code apiKey} may equal {@code adas.api-key}.
 *
 * @param baseUrl            DIAL Core base URL
 * @param apiKey             DIAL {@code Api-Key} for chat/completions
 * @param apiVersion         value of the {@code api-version} query parameter sent on every
 *                           chat/completions call. DIAL Core forwards the caller's query string verbatim
 *                           to the upstream adapter, and the Azure-OpenAI-compatible adapters
 *                           <em>require</em> the parameter — without it they answer HTTP 400
 *                           {@code api-version is a required query parameter}, which is a permanent
 *                           failure and dead-letters the row. Blank omits the parameter, for an adapter
 *                           that does not want it.
 * @param connectTimeoutMs   HTTP connect timeout in milliseconds
 * @param readTimeoutMs      HTTP read timeout in milliseconds
 * @param defaultRpm         per-pipeline rate-limit requests-per-minute when a pipeline declares no rate budget
 * @param rateLimiterTimeout how long a worker waits for a rate-limit permit before failing the chunk
 */
@ConfigurationProperties(prefix = "dial")
public record DialProperties(
        String baseUrl,
        String apiKey,
        String apiVersion,
        int connectTimeoutMs,
        int readTimeoutMs,
        int defaultRpm,
        Duration rateLimiterTimeout) {

    /** Redacts {@code apiKey} so an accidental log or actuator dump never leaks the credential. */
    @Override
    public @NonNull String toString() {
        return "DialProperties[baseUrl=" + baseUrl + ", apiKey=***, apiVersion=" + apiVersion
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs + ", defaultRpm=" + defaultRpm
                + ", rateLimiterTimeout=" + rateLimiterTimeout + "]";
    }
}
