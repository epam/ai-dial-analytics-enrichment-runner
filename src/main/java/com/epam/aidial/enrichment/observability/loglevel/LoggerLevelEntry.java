package com.epam.aidial.enrichment.observability.loglevel;

/**
 * One logger's entry in the level file. Levels stay strings here so an unknown level skips its entry instead of
 * failing the whole file.
 *
 * @param defaultLevel    the level once {@code validTill} has passed; absent means the logger's baseline
 * @param configuredLevel the level while {@code validTill} is in the future or absent
 * @param validTill       epoch milliseconds the configured level expires at, or {@code null} for no expiry
 */
public record LoggerLevelEntry(String defaultLevel, String configuredLevel, Long validTill) {
}
