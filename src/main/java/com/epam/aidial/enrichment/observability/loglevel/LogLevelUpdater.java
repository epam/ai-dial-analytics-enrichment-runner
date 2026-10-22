package com.epam.aidial.enrichment.observability.loglevel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Applies the runtime level file to running loggers, so a diagnostic level needs no restart and reverts by itself.
 *
 * <p>An entry's level is {@code configuredLevel} while {@code validTill} is in the future or absent, otherwise
 * {@code defaultLevel}; with neither, the logger returns to its baseline. The rules that keep this safe:
 * <ul>
 *   <li><strong>Exact name only.</strong> Levels go through {@link LoggingSystem#setLogLevel} on the entry's own name,
 *       never log4j's {@code Configurator.setAllLevels}, which prefix-matches configured loggers and would overwrite
 *       the body-logger pins in {@code log4j2-spring.xml} and catch sibling names.</li>
 *   <li><strong>Baseline snapshot.</strong> Before a logger is first changed, its configured level (from the XML or
 *       {@code logging.level.*}, or none) is recorded; a revert restores exactly that. Clearing an XML-declared
 *       logger's level instead would drop its pin and make it inherit from its parent.</li>
 *   <li><strong>Idempotence.</strong> The file is re-evaluated only when its content hash changes or the earliest
 *       pending {@code validTill} passes, so a level set by other means is not overwritten on every poll.</li>
 *   <li><strong>Denylist.</strong> A level below INFO is refused for a pinned body or statement logger and any of its
 *       descendants. Ancestors are allowed: each pin is its own logger entry, which an ancestor change leaves alone.</li>
 * </ul>
 *
 * <p>Single scheduler thread; the method is synchronized only so a test or a second caller cannot interleave.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogLevelUpdater {

    /** Loggers pinned at INFO in {@code log4j2-spring.xml}; below INFO they log bodies, statements or bound values. */
    static final List<String> SENSITIVE_LOGGERS = List.of(
            "org.springframework.web.client",
            "org.springframework.http.converter",
            "org.hibernate.SQL",
            "org.hibernate.orm.jdbc.bind",
            "org.springframework.jdbc.core");

    private static final String CHANGE_LINE = "log level {}: {} -> {} (source=file, until={})";

    private final LogLevelSource source;
    private final LoggingSystem loggingSystem;
    private final Clock clock;

    /** Configured level of each logger before this updater first changed it; a null value means none. */
    private final Map<String, LogLevel> baselines = new HashMap<>();
    /** The level this updater currently holds each logger at. */
    private final Map<String, LogLevel> applied = new HashMap<>();

    private Map<String, LoggerLevelEntry> entries = Map.of();
    private String appliedHash;
    private String warnedHash;
    private String warnedReadError;
    private Instant nextExpiry;
    private boolean missingReported;

    @Scheduled(fixedDelayString = "${observability.log-levels.interval}")
    public synchronized void poll() {
        Optional<LogLevelSnapshot> read;
        try {
            read = source.read();
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (!error.equals(warnedReadError)) {
                log.warn("log level file {} could not be read, keeping the current levels: {}", source.location(), error);
                warnedReadError = error;
            }
            expireIfDue();
            return;
        }
        warnedReadError = null;

        if (read.isEmpty()) {
            onMissing();
            return;
        }
        missingReported = false;
        LogLevelSnapshot snapshot = read.get();
        if (!snapshot.parsed()) {
            if (!snapshot.hash().equals(warnedHash)) {
                log.warn("log level file {} could not be parsed, keeping the current levels: {}", source.location(), snapshot.parseError());
                warnedHash = snapshot.hash();
            }
            expireIfDue();
            return;
        }
        if (snapshot.hash().equals(appliedHash)) {
            expireIfDue();
            return;
        }
        entries = snapshot.loggerLevels();
        appliedHash = snapshot.hash();
        evaluate(true);
    }

    private void onMissing() {
        if (appliedHash != null) {
            log.info("log level file {} was removed, reverting {} logger(s) to their baseline", source.location(), applied.size());
            entries = Map.of();
            evaluate(false);
            appliedHash = null;
            nextExpiry = null;
            missingReported = true;
        } else if (!missingReported) {
            log.info("log level file {} not found, runtime log levels are not applied until it appears", source.location());
            missingReported = true;
        }
    }

    private void expireIfDue() {
        if (nextExpiry != null && !clock.instant().isBefore(nextExpiry)) {
            evaluate(false);
        }
    }

    /**
     * Computes and applies the effective level of every entry, and reverts any logger this updater holds that no
     * longer has one.
     *
     * @param announce whether to log validation findings; true only for new content, so each is logged once
     */
    private void evaluate(boolean announce) {
        long now = clock.millis();
        Instant earliest = null;
        Map<String, LogLevel> desired = new LinkedHashMap<>();
        Map<String, String> until = new HashMap<>();
        Set<String> untouched = new HashSet<>();

        for (Map.Entry<String, LoggerLevelEntry> item : new TreeMap<>(entries).entrySet()) {
            String name = normalize(item.getKey());
            LoggerLevelEntry entry = item.getValue() == null ? new LoggerLevelEntry(null, null, null) : item.getValue();
            if (name.isEmpty()) {
                if (announce) {
                    log.warn("log level entry with a blank logger name skipped (source=file)");
                }
                continue;
            }
            if (!isKnown(entry.configuredLevel()) || !isKnown(entry.defaultLevel())) {
                if (announce) {
                    log.warn("log level {}: unknown level in configuredLevel={} defaultLevel={}, entry skipped (source=file)",
                            name, entry.configuredLevel(), entry.defaultLevel());
                }
                untouched.add(name);
                continue;
            }
            Long validTill = entry.validTill();
            boolean active = entry.configuredLevel() != null && (validTill == null || validTill > now);
            if (active && validTill != null) {
                Instant expiry = Instant.ofEpochMilli(validTill);
                earliest = earliest == null || expiry.isBefore(earliest) ? expiry : earliest;
            }
            LogLevel target = parse(active ? entry.configuredLevel() : entry.defaultLevel());
            if (target != null && isBelowInfo(target) && isSensitive(name)) {
                if (announce) {
                    log.info("log level {}: {} refused, levels below INFO are not allowed for body or statement loggers (source=file)", name, target);
                }
                untouched.add(name);
                continue;
            }
            if (announce && active && target != null && isBelowInfo(target) && validTill == null) {
                log.warn("log level {}: {} has no validTill, so it stays until the file changes (source=file)", name, target);
            }
            desired.put(name, target);
            until.put(name, target != null && active && validTill != null ? Instant.ofEpochMilli(validTill).toString() : "-");
        }
        for (String name : new ArrayList<>(applied.keySet())) {
            if (!desired.containsKey(name) && !untouched.contains(name)) {
                desired.put(name, null);
            }
        }
        desired.forEach((name, target) -> apply(name, target, until.getOrDefault(name, "-")));
        nextExpiry = earliest;
    }

    private void apply(String name, LogLevel target, String until) {
        LogLevel current = currentLevel(name);
        if (target == null) {
            applied.remove(name);
            if (!baselines.containsKey(name)) {
                return;
            }
            LogLevel baseline = baselines.remove(name);
            if (!Objects.equals(current, baseline)) {
                loggingSystem.setLogLevel(name, baseline);
                log.info(CHANGE_LINE, name, render(current), render(baseline), "-");
            }
            return;
        }
        if (!baselines.containsKey(name)) {
            baselines.put(name, current);
        }
        applied.put(name, target);
        if (target != current) {
            loggingSystem.setLogLevel(name, target);
            log.info(CHANGE_LINE, name, render(current), render(target), until);
        }
    }

    /**
     * The logger's own configured level, or null when it has none. The root logger always has a level, but Boot
     * reports it only as the effective one.
     */
    private LogLevel currentLevel(String name) {
        LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(name);
        if (isRoot(name)) {
            if (configuration == null) {
                configuration = loggingSystem.getLoggerConfiguration("");
            }
            return configuration == null ? null : configuration.getEffectiveLevel();
        }
        return configuration == null ? null : configuration.getConfiguredLevel();
    }

    /** Whether the text is absent or names a Spring {@link LogLevel}, case-insensitively. */
    private static boolean isKnown(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        try {
            parse(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The level, or null when absent; the text is validated by {@link #isKnown} first. */
    private static LogLevel parse(String raw) {
        return raw == null || raw.isBlank() ? null : LogLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    static boolean isSensitive(String name) {
        return SENSITIVE_LOGGERS.stream().anyMatch(pinned -> name.equals(pinned) || name.startsWith(pinned + ".") || name.startsWith(pinned + "$"));
    }

    private static boolean isBelowInfo(LogLevel level) {
        return level.ordinal() < LogLevel.INFO.ordinal();
    }

    private static boolean isRoot(String name) {
        return LoggingSystem.ROOT_LOGGER_NAME.equals(name);
    }

    private static String normalize(String name) {
        String trimmed = name == null ? "" : name.trim();
        return LoggingSystem.ROOT_LOGGER_NAME.equalsIgnoreCase(trimmed) ? LoggingSystem.ROOT_LOGGER_NAME : trimmed;
    }

    private static String render(LogLevel level) {
        return level == null ? "unset" : level.name();
    }
}
