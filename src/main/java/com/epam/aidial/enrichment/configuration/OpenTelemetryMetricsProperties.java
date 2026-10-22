package com.epam.aidial.enrichment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed binding for OTLP metric export ({@code opentelemetry.metrics.*}).
 *
 * <p>Traces and logs are configured by Boot's own {@code management.opentelemetry.*} properties. Metrics
 * are not: Boot 4's OpenTelemetry module has no metrics support, so the runner wires its own
 * {@code SdkMeterProvider} (see {@link OpenTelemetryMetricsConfiguration}) and needs its own binding. The
 * environment variables behind these keys are the same ones the other two signals read, so a single
 * {@code OTEL_EXPORT_ENABLED} / {@code OTEL_EXPORTER_OTLP_ENDPOINT} pair still configures all three.
 *
 * @param enabled  whether metrics are exported over OTLP at all; when false no meter provider, exporter
 *                 or bridged registry is created and metrics remain available on the Prometheus endpoint
 * @param endpoint full OTLP metrics endpoint, including the {@code /v1/metrics} path for HTTP transport
 * @param protocol OTLP transport, {@code http} or {@code grpc}
 * @param interval how often the periodic reader flushes accumulated metrics to the exporter
 */
@ConfigurationProperties(prefix = "opentelemetry.metrics")
public record OpenTelemetryMetricsProperties(boolean enabled, String endpoint, String protocol, Duration interval) {
}
