package com.epam.aidial.enrichment.observability.loglevel;

import java.util.Optional;

/** Where runtime logger levels come from. */
public interface LogLevelSource {

    /** A human-readable location for log lines, such as the file path. */
    String location();

    /**
     * Reads the current levels.
     *
     * @return the snapshot, or empty when the source does not exist
     * @throws java.io.UncheckedIOException when the source exists but cannot be read
     */
    Optional<LogLevelSnapshot> read();
}
