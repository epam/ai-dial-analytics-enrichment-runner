package com.epam.aidial.enrichment.support;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Captures whole log events — message, logger, thread, MDC and throwable — so a test can assert what a record
 * carried, not only what it said. Unlike {@link CapturingAppender} it does not lower levels: it sees what the
 * configured levels let through, which is what an operator sees. The one exception is a JVM in which no Spring
 * context has configured log4j yet, whose default root is ERROR; the root is raised to INFO for the capture, so a
 * test's outcome does not depend on the order the suite runs in.
 */
public final class EventCapture extends AbstractAppender {

    private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
    private Level rootLevelBefore;

    private EventCapture() {
        super("event-capture-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY);
    }

    /** Attaches a started capture to the root logger. */
    public static EventCapture attach() {
        EventCapture capture = new EventCapture();
        capture.start();
        LoggerContext context = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        LoggerConfig root = context.getConfiguration().getRootLogger();
        if (root.getLevel().isMoreSpecificThan(Level.WARN)) {
            capture.rootLevelBefore = root.getLevel();
            root.setLevel(Level.INFO);
        }
        root.addAppender(capture, Level.ALL, null);
        context.updateLoggers();
        return capture;
    }

    public void detach() {
        LoggerContext context = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        LoggerConfig root = context.getConfiguration().getRootLogger();
        root.removeAppender(getName());
        if (rootLevelBefore != null) {
            root.setLevel(rootLevelBefore);
        }
        context.updateLoggers();
        stop();
    }

    @Override
    public void append(LogEvent event) {
        events.add(event.toImmutable());
    }

    public List<LogEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public void clear() {
        events.clear();
    }

    /** Everything a record would print: its message and, when it has one, its full stack trace. */
    public static String rendered(LogEvent event) {
        String message = event.getMessage().getFormattedMessage();
        if (event.getThrown() == null) {
            return message;
        }
        StringWriter stack = new StringWriter();
        event.getThrown().printStackTrace(new PrintWriter(stack));
        return message + "\n" + stack;
    }
}
