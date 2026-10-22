package com.epam.aidial.enrichment.observability.loglevel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Typed binding for the runtime level file ({@code observability.log-levels.*}).
 *
 * <p>{@code interval} is also read as a placeholder by {@link LogLevelUpdater}'s {@code @Scheduled}; it is bound
 * here so a value below one second fails startup instead of polling the file in a tight loop. A bare number is
 * milliseconds, which is the usual way to end up there.
 *
 * @param path     the JSON level file, typically a mounted ConfigMap
 * @param interval how often the file is polled, at least one second
 */
@ConfigurationProperties(prefix = "observability.log-levels")
public record LogLevelProperties(Path path, Duration interval) {

    static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    public LogLevelProperties {
        if (path == null) {
            throw new IllegalArgumentException("observability.log-levels.path must be set");
        }
        if (interval == null || interval.compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException("observability.log-levels.interval must be at least 1s, was " + interval
                    + " (a bare number is milliseconds)");
        }
    }
}
