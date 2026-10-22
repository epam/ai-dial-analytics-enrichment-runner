package com.epam.aidial.enrichment.support;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Captures every log record emitted while it is attached, at every level, so a test can assert on what was
 * <em>not</em> logged.
 *
 * <p>Body-handling pipelines ("never log assembled group input or a rendered prompt") are properties of every
 * present and future line in the enrichment path, and the cost of breaking one is source content in a log
 * aggregator. Asserting them needs the records themselves — including the {@code debug} a future change is
 * most likely to add carelessly, which is why {@link #attachAtTrace()} lowers the root level.
 */
public final class CapturingAppender extends AbstractAppender {

    private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

    private CapturingAppender() {
        super("capturing-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY);
    }

    /** Attaches a started appender to the root logger and lowers the root level to TRACE. */
    public static CapturingAppender attachAtTrace() {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        LoggerContext context = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        context.getConfiguration().getRootLogger().addAppender(appender, Level.TRACE, null);
        Configurator.setRootLevel(Level.TRACE);
        context.updateLoggers();
        return appender;
    }

    /** Detaches the appender and restores the root level. */
    public void detach(Level restoreTo) {
        LoggerContext context = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        context.getConfiguration().getRootLogger().removeAppender(getName());
        Configurator.setRootLevel(restoreTo);
        context.updateLoggers();
        stop();
    }

    @Override
    public void append(LogEvent event) {
        messages.add(event.getMessage().getFormattedMessage());
        if (event.getThrown() != null) {
            // A payload can leak through an exception message just as easily as through a format argument.
            messages.add(String.valueOf(event.getThrown().getMessage()));
        }
    }

    /** Every captured record's formatted message, plus any throwable messages. */
    public List<String> messages() {
        return List.copyOf(messages);
    }

    /** Whether any captured record contains the given text. */
    public boolean containsText(String text) {
        return messages().stream().anyMatch(m -> m != null && m.contains(text));
    }

    public void clear() {
        messages.clear();
    }
}
