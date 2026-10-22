package com.epam.aidial.enrichment.configuration;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The log4j2 appender declared in log4j2-spring.xml only forwards to the SDK once
 * {@code OpenTelemetryAppender.install} has been called; without it the OTLP log exporter is wired but never
 * receives a record. These tests load a log4j2 configuration carrying that appender and assert the
 * configuration bean closes the gap.
 */
class OpenTelemetryLogAppenderConfigurationTest {

    private final InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    private final SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
            .build();
    private final OpenTelemetry sdk = OpenTelemetrySdk.builder()
            .setLoggerProvider(loggerProvider)
            .build();

    private LoggerContext loggerContext;
    private URI originalConfigLocation;

    @BeforeEach
    void useAppenderConfiguration() throws URISyntaxException {
        loggerContext = (LoggerContext) LogManager.getContext(false);
        originalConfigLocation = loggerContext.getConfigLocation();
        loggerContext.setConfigLocation(
                Objects.requireNonNull(getClass().getResource("/log4j2-otel-appender-test.xml")).toURI());
    }

    @AfterEach
    void restoreConfiguration() {
        loggerContext.setConfigLocation(originalConfigLocation);
        loggerProvider.close();
    }

    @Test
    void bridgesLog4j2EventsIntoTheSdkOnceInstalled() {
        new ApplicationContextRunner()
                .withBean(OpenTelemetry.class, () -> sdk)
                .withUserConfiguration(OpenTelemetryLogAppenderConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    LogManager.getLogger("otel-appender-test").info("enrichment log reaches the SDK");

                    assertThat(exporter.getFinishedLogRecordItems())
                            .extracting(record -> Objects.requireNonNull(record.getBodyValue()).asString())
                            .contains("enrichment log reaches the SDK");
                });
    }

    /**
     * Loads the shipped {@code log4j2-spring.xml} rather than the test mirror, so the exported MDC allow-list is the
     * one production runs with: correlation keys become attributes, the possibly user-identifying keys do not.
     */
    @Test
    void exportsCorrelationKeysButNotGrainOrGroupKeys() throws URISyntaxException {
        loggerContext.setConfigLocation(Objects.requireNonNull(getClass().getResource("/log4j2-spring.xml")).toURI());
        new ApplicationContextRunner()
                .withBean(OpenTelemetry.class, () -> sdk)
                .withUserConfiguration(OpenTelemetryLogAppenderConfiguration.class)
                .run(context -> {
                    try (CloseableThreadContext.Instance ignored = CloseableThreadContext
                            .put("pipeline_name", "summaries")
                            .put("pipeline_generation", "3")
                            .put("chunk_id", "c-1")
                            .put("task", "enrichment-chunk")
                            .put("task_instance", "summaries#c-1")
                            .put("run_id", "r-1")
                            .put("grain_key", "user@example.com")
                            .put("group_key", "conversation-42")) {
                        LogManager.getLogger("com.epam.aidial.enrichment.worker").info("worker processed a chunk");
                    }

                    LogRecordData record = exporter.getFinishedLogRecordItems().stream()
                            .filter(r -> "worker processed a chunk".equals(Objects.requireNonNull(r.getBodyValue()).asString()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(record.getAttributes().get(AttributeKey.stringKey("pipeline_name"))).isEqualTo("summaries");
                    assertThat(record.getAttributes().get(AttributeKey.stringKey("pipeline_generation"))).isEqualTo("3");
                    assertThat(record.getAttributes().get(AttributeKey.stringKey("chunk_id"))).isEqualTo("c-1");
                    assertThat(record.getAttributes().get(AttributeKey.stringKey("task"))).isEqualTo("enrichment-chunk");
                    assertThat(record.getAttributes().get(AttributeKey.stringKey("task_instance"))).isEqualTo("summaries#c-1");
                    assertThat(record.getAttributes().get(AttributeKey.stringKey("run_id"))).isEqualTo("r-1");
                    assertThat(record.getAttributes().get(AttributeKey.stringKey("grain_key"))).isNull();
                    assertThat(record.getAttributes().get(AttributeKey.stringKey("group_key"))).isNull();
                });
    }

    @Test
    void startsCleanlyWhenNoOpenTelemetryBeanIsPresent() {
        new ApplicationContextRunner()
                .withUserConfiguration(OpenTelemetryLogAppenderConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
