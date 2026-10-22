package com.epam.aidial.enrichment.observability.loglevel;

import java.util.Map;

/**
 * One read of the level file.
 *
 * @param hash         the content hash, which decides whether the file changed since the last read
 * @param loggerLevels the parsed entries by logger name, or {@code null} when the content did not parse
 * @param parseError   why the content did not parse, or {@code null} when it did
 */
public record LogLevelSnapshot(String hash, Map<String, LoggerLevelEntry> loggerLevels, String parseError) {

    public boolean parsed() {
        return parseError == null;
    }
}
