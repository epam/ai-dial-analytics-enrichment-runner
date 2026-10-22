package com.epam.aidial.enrichment.observability;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** The console pattern in {@code log4j2-spring.xml} prints a correlation key only when it has a value. */
class ConsolePatternTest {

    private static LoggerContext context;
    private static Layout<?> layout;

    @BeforeAll
    static void loadConfiguration() throws Exception {
        context = new LoggerContext("console-pattern-test");
        try (InputStream in = Objects.requireNonNull(ConsolePatternTest.class.getResourceAsStream("/log4j2-spring.xml"))) {
            XmlConfiguration configuration = new XmlConfiguration(context, new ConfigurationSource(in));
            configuration.initialize();
            layout = configuration.getAppender("Console").getLayout();
        }
    }

    @AfterAll
    static void stopContext() {
        context.stop();
    }

    @Test
    void recordOutsideAnyPipelineCarriesNoEmptyKeys() {
        String line = format(Map.of());

        assertThat(line).doesNotContain("pipeline=", "gen=", "task=", "trace_id=", "span_id=");
        assertThat(line).contains("[main] c.e.a.e.Probe - hello");
    }

    @Test
    void pipelineRecordCarriesItsKeys() {
        String line = format(Map.of(
                "trace_id", "t1", "span_id", "s1",
                "pipeline_name", "session-insights-live", "pipeline_generation", "26", "task", "upsert-rows"));

        assertThat(line).contains("trace_id=t1 span_id=s1 [main]");
        assertThat(line).contains("c.e.a.e.Probe pipeline=session-insights-live gen=26 task=upsert-rows - hello");
    }

    @Test
    void onlyThePopulatedKeysArePrinted() {
        String line = format(Map.of("pipeline_name", "session-insights-live"));

        assertThat(line).contains("c.e.a.e.Probe pipeline=session-insights-live - hello");
        assertThat(line).doesNotContain("gen=", "task=");
    }

    private static String format(Map<String, String> mdc) {
        SortedArrayStringMap contextData = new SortedArrayStringMap();
        mdc.forEach(contextData::putValue);
        LogEvent event = Log4jLogEvent.newBuilder()
                .setLoggerName("com.epam.aidial.enrichment.Probe")
                .setLevel(Level.INFO)
                .setThreadName("main")
                .setMessage(new SimpleMessage("hello"))
                .setContextData(contextData)
                .build();
        return new String(layout.toByteArray(event)).replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
