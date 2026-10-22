package com.epam.aidial.enrichment.configuration;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exports metrics over OTLP through the same OpenTelemetry SDK that already carries traces and logs, so
 * all three signals report one identical {@link Resource}.
 *
 * <p>Boot 4's {@code spring-boot-opentelemetry} module auto-configures an {@code SdkTracerProvider} and an
 * {@code SdkLoggerProvider} but no meter provider; its OTLP metrics path is instead Micrometer's own
 * {@code OtlpMeterRegistry}, which serializes OTLP itself and never touches the SDK. That registry builds a
 * resource of its own describing Micrometer as the producing SDK
 * ({@code telemetry.sdk.name=io.micrometer}), while the SDK-backed signals report
 * {@code telemetry.sdk.name=opentelemetry}. The values are individually accurate — the two pipelines really
 * are different SDKs — but they split one service into two OTLP resource identities, which fragments
 * resource-keyed correlation in backends that group by more than {@code service.name}. The divergence
 * cannot be configured away: {@code OtlpMeterRegistry} treats {@code telemetry.sdk.name},
 * {@code telemetry.sdk.language} and {@code telemetry.sdk.version} as reserved and logs-and-drops any
 * caller-supplied value for them.
 *
 * <p>So rather than restating the resource on a second pipeline, this configuration removes the second
 * pipeline. {@link #sdkMeterProvider} builds a meter provider on the very same {@code Resource} bean Boot
 * hands the tracer and logger providers, and Boot's {@code OpenTelemetrySdkAutoConfiguration} — which
 * already accepts an optional {@code SdkMeterProvider} — folds it into the {@code OpenTelemetrySdk} bean.
 * {@link #openTelemetryMeterRegistry} then bridges the Micrometer API onto that SDK, so existing
 * {@code MeterRegistry} instrumentation is unchanged at the call site.
 *
 * <p>The Prometheus registry is untouched and stays additive: it remains the scrape-facing view, keeps
 * Micrometer's own metric naming, and is unaffected by whether OTLP export is on. When export is disabled
 * (the default) neither bean is created and no exporter thread starts.
 *
 * <p>Boot's own OTLP metrics export is pinned off in {@code application.yml}
 * ({@code management.otlp.metrics.export.enabled: false}) and must stay that way: it defaults to *on*
 * whenever {@code micrometer-registry-otlp} is on the classpath, which it is transitively, so leaving the
 * property unset would start Micrometer's exporter alongside this one — double-exporting every meter under
 * the two conflicting resources this configuration exists to reconcile.
 */
@Configuration
@ConditionalOnProperty(prefix = "opentelemetry.metrics", name = "enabled", havingValue = "true")
public class OpenTelemetryMetricsConfiguration {

    /**
     * Builds the meter provider on Boot's shared {@link Resource} bean — the single line that makes metric
     * resource attributes identical to those on traces and logs.
     *
     * @param resource   the resource bean Boot also applies to the tracer and logger providers
     * @param properties OTLP metric export settings
     * @return a meter provider Boot's SDK auto-configuration picks up and installs on the OpenTelemetry bean
     */
    @Bean
    public SdkMeterProvider sdkMeterProvider(Resource resource, OpenTelemetryMetricsProperties properties,
            MetricExporter metricExporter) {
        return SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                        .setInterval(properties.interval())
                        .build())
                .build();
    }

    /**
     * The OTLP exporter the reader flushes to. A separate bean so a test can substitute a collecting
     * exporter and assert on what actually leaves the meter provider.
     *
     * @param properties OTLP metric export settings supplying endpoint and transport
     * @return an OTLP metric exporter over the configured transport
     */
    @Bean
    @ConditionalOnMissingBean
    public MetricExporter metricExporter(OpenTelemetryMetricsProperties properties) {
        if ("grpc".equalsIgnoreCase(properties.protocol())) {
            return OtlpGrpcMetricExporter.builder().setEndpoint(properties.endpoint()).build();
        }
        return OtlpHttpMetricExporter.builder().setEndpoint(properties.endpoint()).build();
    }

    /**
     * Bridges Micrometer meters onto the SDK meter provider wired above.
     *
     * <p>Registered as an additional {@link MeterRegistry} bean, so Boot's composite publishes every meter
     * to both this bridge and the Prometheus registry; meter filters and common tags apply to both.
     *
     * @param openTelemetry the SDK bean, carrying the meter provider from {@link #sdkMeterProvider}
     * @param clock         Micrometer's clock, so bridged timers agree with the rest of the registry
     * @return a Micrometer registry that records through the OpenTelemetry SDK
     */
    @Bean
    public MeterRegistry openTelemetryMeterRegistry(OpenTelemetry openTelemetry, Clock clock) {
        return OpenTelemetryMeterRegistry.builder(openTelemetry)
                .setClock(clock)
                .build();
    }
}
