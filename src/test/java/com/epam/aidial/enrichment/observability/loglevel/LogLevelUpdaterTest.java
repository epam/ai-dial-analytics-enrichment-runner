package com.epam.aidial.enrichment.observability.loglevel;

import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.support.SettableClock;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the updater against the real {@code log4j2-spring.xml}, so the pins it must preserve are the shipped ones and
 * a revert is checked against the declared level rather than whatever a test configuration happens to hold.
 */
class LogLevelUpdaterTest {

    private static final String CLASS1 = "com.epam.aidial.enrichment.class1";
    private static final String WEB_CLIENT = "org.springframework.web.client";

    private final SettableClock clock = new SettableClock(Instant.parse("2026-09-29T10:00:00Z"));
    private final FakeSource source = new FakeSource();
    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
    private final LogLevelUpdater updater = new LogLevelUpdater(source, loggingSystem, clock);

    private LoggerContext loggerContext;
    private URI originalConfigLocation;
    private CapturingAppender appender;

    @BeforeEach
    void useShippedConfiguration() throws URISyntaxException {
        loggerContext = (LoggerContext) LogManager.getContext(false);
        originalConfigLocation = loggerContext.getConfigLocation();
        loggerContext.setConfigLocation(Objects.requireNonNull(getClass().getResource("/log4j2-spring.xml")).toURI());
        appender = CapturingAppender.attachAtTrace();
    }

    @AfterEach
    void restoreConfiguration() {
        appender.detach(Level.INFO);
        loggerContext.setConfigLocation(originalConfigLocation);
    }

    @Test
    void timeBoxedLevelAppliesThenRevertsByItself() {
        long validTill = clock.instant().plus(Duration.ofMinutes(5)).toEpochMilli();
        source.write(Map.of(CLASS1, new LoggerLevelEntry("INFO", "debug", validTill)));

        updater.poll();
        assertThat(configured(CLASS1)).isEqualTo(LogLevel.DEBUG);
        assertThat(appender.containsText("log level " + CLASS1 + ": unset -> DEBUG (source=file, until=" + Instant.ofEpochMilli(validTill) + ")"))
                .isTrue();

        clock.advance(Duration.ofMinutes(4));
        updater.poll();
        assertThat(configured(CLASS1)).isEqualTo(LogLevel.DEBUG);

        clock.advance(Duration.ofMinutes(2));
        updater.poll();
        assertThat(configured(CLASS1)).isEqualTo(LogLevel.INFO);
        assertThat(appender.containsText("log level " + CLASS1 + ": DEBUG -> INFO (source=file, until=-)")).isTrue();
    }

    @Test
    void expiryWithoutDefaultLevelRestoresTheBaseline() {
        source.write(Map.of(CLASS1, new LoggerLevelEntry(null, "DEBUG", clock.instant().plusSeconds(30).toEpochMilli())));
        updater.poll();
        assertThat(configured(CLASS1)).isEqualTo(LogLevel.DEBUG);

        clock.advance(Duration.ofMinutes(1));
        updater.poll();
        assertThat(configured(CLASS1)).isNull();
    }

    @Test
    void defaultLevelOnlyEntryAppliesItsDefault() {
        source.write(Map.of(CLASS1, new LoggerLevelEntry("WARN", null, null)));
        updater.poll();
        assertThat(configured(CLASS1)).isEqualTo(LogLevel.WARN);
    }

    @Test
    void removedEntryRestoresTheXmlPinNotTheParentLevel() {
        source.write(Map.of(
                "org.springframework", new LoggerLevelEntry(null, "ERROR", null),
                WEB_CLIENT, new LoggerLevelEntry(null, "WARN", null)));
        updater.poll();
        assertThat(configured(WEB_CLIENT)).isEqualTo(LogLevel.WARN);

        source.write(Map.of("org.springframework", new LoggerLevelEntry(null, "ERROR", null)));
        updater.poll();

        assertThat(configured(WEB_CLIENT)).isEqualTo(LogLevel.INFO);
        assertThat(effective(WEB_CLIENT)).isEqualTo(LogLevel.INFO);
        assertThat(effective("org.springframework")).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void ancestorAtDebugLeavesEveryPinAtInfo() {
        source.write(Map.of(
                "org.springframework", new LoggerLevelEntry(null, "DEBUG", clock.instant().plusSeconds(600).toEpochMilli()),
                "org.hibernate", new LoggerLevelEntry(null, "DEBUG", clock.instant().plusSeconds(600).toEpochMilli())));
        updater.poll();

        assertThat(effective("org.springframework")).isEqualTo(LogLevel.DEBUG);
        assertThat(effective("org.springframework.web.servlet")).isEqualTo(LogLevel.DEBUG);
        for (String pinned : LogLevelUpdater.SENSITIVE_LOGGERS) {
            assertThat(effective(pinned)).as(pinned).isEqualTo(LogLevel.INFO);
            assertThat(effective(pinned + ".Child")).as(pinned + ".Child").isEqualTo(LogLevel.INFO);
        }
    }

    @Test
    void pinnedLoggersAndTheirDescendantsCannotBeLowered() {
        source.write(Map.of(
                "org.springframework.web.client.RestClient", new LoggerLevelEntry(null, "TRACE", clock.instant().plusSeconds(60).toEpochMilli()),
                "org.hibernate.SQL", new LoggerLevelEntry(null, "DEBUG", clock.instant().plusSeconds(60).toEpochMilli()),
                "org.springframework.web.clientx", new LoggerLevelEntry(null, "DEBUG", clock.instant().plusSeconds(60).toEpochMilli())));
        updater.poll();

        assertThat(effective("org.springframework.web.client.RestClient")).isEqualTo(LogLevel.INFO);
        assertThat(configured("org.hibernate.SQL")).isEqualTo(LogLevel.INFO);
        assertThat(count("org.springframework.web.client.RestClient: TRACE refused")).isEqualTo(1);
        assertThat(count("org.hibernate.SQL: DEBUG refused")).isEqualTo(1);
        // Dot-boundary aware: a sibling that merely shares the prefix is not a descendant.
        assertThat(configured("org.springframework.web.clientx")).isEqualTo(LogLevel.DEBUG);
    }

    @Test
    void sensitivityIsDotBoundaryAware() {
        assertThat(LogLevelUpdater.isSensitive("org.springframework.web.client")).isTrue();
        assertThat(LogLevelUpdater.isSensitive("org.springframework.web.client.RestClient")).isTrue();
        assertThat(LogLevelUpdater.isSensitive("org.springframework.jdbc.core.JdbcTemplate")).isTrue();
        assertThat(LogLevelUpdater.isSensitive("org.springframework.web.clientx")).isFalse();
        assertThat(LogLevelUpdater.isSensitive("org.springframework.web")).isFalse();
    }

    @Test
    void missingFileChangesNothingAndIsReportedOnce() {
        updater.poll();
        updater.poll();

        assertThat(count("not found")).isEqualTo(1);
        assertThat(appender.containsText(source.location())).isTrue();
        assertThat(configured(WEB_CLIENT)).isEqualTo(LogLevel.INFO);
        assertThat(configured(CLASS1)).isNull();
    }

    @Test
    void removedFileRevertsEverything() {
        source.write(Map.of(
                CLASS1, new LoggerLevelEntry(null, "DEBUG", clock.instant().plusSeconds(60).toEpochMilli()),
                WEB_CLIENT, new LoggerLevelEntry(null, "ERROR", null)));
        updater.poll();
        assertThat(configured(CLASS1)).isEqualTo(LogLevel.DEBUG);
        assertThat(configured(WEB_CLIENT)).isEqualTo(LogLevel.ERROR);

        source.remove();
        updater.poll();
        updater.poll();

        assertThat(configured(CLASS1)).isNull();
        assertThat(configured(WEB_CLIENT)).isEqualTo(LogLevel.INFO);
        assertThat(count("was removed")).isEqualTo(1);
    }

    @Test
    void unparsableFileKeepsStateWithOneWarn() {
        source.write(Map.of(CLASS1, new LoggerLevelEntry(null, "DEBUG", clock.instant().plusSeconds(600).toEpochMilli())));
        updater.poll();

        source.broken();
        updater.poll();
        updater.poll();
        updater.poll();

        assertThat(configured(CLASS1)).isEqualTo(LogLevel.DEBUG);
        assertThat(count("could not be parsed")).isEqualTo(1);
    }

    @Test
    void unchangedContentIsNotReappliedOverOtherChanges() {
        source.write(Map.of(CLASS1, new LoggerLevelEntry(null, "WARN", null)));
        updater.poll();
        loggingSystem.setLogLevel(CLASS1, LogLevel.ERROR);
        appender.clear();

        updater.poll();

        assertThat(configured(CLASS1)).isEqualTo(LogLevel.ERROR);
        assertThat(appender.containsText("log level " + CLASS1)).isFalse();
    }

    @Test
    void unknownLevelIsSkippedWithOneWarn() {
        source.write(Map.of(CLASS1, new LoggerLevelEntry(null, "VERBOSE", null)));
        updater.poll();
        updater.poll();

        assertThat(configured(CLASS1)).isNull();
        assertThat(count("unknown level")).isEqualTo(1);
    }

    @Test
    void levelBelowInfoWithoutValidTillWarns() {
        source.write(Map.of(CLASS1, new LoggerLevelEntry(null, "DEBUG", null)));
        updater.poll();

        assertThat(configured(CLASS1)).isEqualTo(LogLevel.DEBUG);
        assertThat(count("has no validTill")).isEqualTo(1);
    }

    private LogLevel configured(String name) {
        LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(name);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }

    private LogLevel effective(String name) {
        // Resolving the logger makes it known to the context, as a class using it would.
        LogManager.getLogger(name);
        return Objects.requireNonNull(loggingSystem.getLoggerConfiguration(name)).getEffectiveLevel();
    }

    private long count(String text) {
        return appender.messages().stream().filter(m -> m != null && m.contains(text)).count();
    }

    /** An in-memory level source; each write gets a fresh hash, like a changed file would. */
    private static final class FakeSource implements LogLevelSource {

        private LogLevelSnapshot current;
        private int version;

        void write(Map<String, LoggerLevelEntry> levels) {
            current = new LogLevelSnapshot("hash-" + ++version, new LinkedHashMap<>(levels), null);
        }

        void broken() {
            current = new LogLevelSnapshot("bad-hash", null, "Unexpected character");
        }

        void remove() {
            current = null;
        }

        @Override
        public String location() {
            return "/test/logging.levels.json";
        }

        @Override
        public Optional<LogLevelSnapshot> read() {
            return Optional.ofNullable(current);
        }
    }
}
