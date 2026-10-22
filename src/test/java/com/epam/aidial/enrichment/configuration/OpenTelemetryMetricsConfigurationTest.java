package com.epam.aidial.enrichment.configuration;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of routing metrics through the OpenTelemetry SDK is that every signal leaves the process under
 * one identical {@link Resource}. Nothing in the configuration asserts that by itself — Boot's metrics
 * default (Micrometer's {@code OtlpMeterRegistry}) also produces a working OTLP metrics stream, just one
 * stamped with a resource of its own reporting {@code telemetry.sdk.name=io.micrometer} against the
 * {@code opentelemetry} on traces and logs. That regression is invisible from configuration and from any
 * assertion that only checks metrics are exported, so it is pinned here against exported metric data.
 */
class OpenTelemetryMetricsConfigurationTest {

    private static final AttributeKey<String> SDK_NAME = AttributeKey.stringKey("telemetry.sdk.name");

    private final InMemoryMetricExporter exporter = InMemoryMetricExporter.create();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Boot's own OTLP metrics auto-configuration is deliberately in the slice: it defaults to ON
            // whenever micrometer-registry-otlp is on the classpath (it is, transitively), so it is the
            // thing most likely to quietly restore the second pipeline.
            .withConfiguration(AutoConfigurations.of(
                    OpenTelemetrySdkAutoConfiguration.class,
                    MetricsAutoConfiguration.class,
                    OtlpMetricsExportAutoConfiguration.class))
            // @ConfigurationPropertiesScan on the application class covers this at runtime; a slice must
            // register the binding itself.
            .withUserConfiguration(PropertiesConfiguration.class, OpenTelemetryMetricsConfiguration.class)
            .withBean(Clock.class, () -> Clock.SYSTEM)
            .withPropertyValues(
                    "spring.application.name=analytics-enrichment-runner",
                    "management.opentelemetry.resource-attributes.service.name=analytics-enrichment-runner",
                    // Mirrors application.yml; see openTelemetryMeterRegistry's javadoc.
                    "management.otlp.metrics.export.enabled=false",
                    "opentelemetry.metrics.endpoint=http://localhost:4318/v1/metrics",
                    "opentelemetry.metrics.protocol=http",
                    "opentelemetry.metrics.interval=60s");

    @Test
    void metricsAreExportedUnderTheResourceSharedWithTracesAndLogs() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            Resource shared = context.getBean(Resource.class);

            context.getBean(MeterRegistry.class).counter("runner.probe").increment();
            context.getBean(SdkMeterProvider.class).forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(exporter.getFinishedMetricItems())
                    .isNotEmpty()
                    .extracting(MetricData::getResource)
                    .allSatisfy(exported -> {
                        // Byte-for-byte the resource the tracer and logger providers carry — not merely a
                        // matching service.name, which the two-pipeline setup also satisfied.
                        assertThat(exported).isEqualTo(shared);
                        assertThat(exported.getAttribute(SDK_NAME)).isEqualTo("opentelemetry");
                    });
        });
    }

    @Test
    void bridgesMicrometerOntoTheSdkRatherThanOntoSeparatePipeline() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("openTelemetryMeterRegistry", MeterRegistry.class).getClass().getName())
                    .isEqualTo("io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry");

            // Micrometer's own OTLP registry must not run alongside it: it would export every meter a
            // second time under the io.micrometer resource, which is the split being fixed here.
            // Compared by name: micrometer-registry-otlp reaches this module only transitively, at runtime.
            assertThat(context.getBeansOfType(MeterRegistry.class).values())
                    .extracting(registry -> registry.getClass().getName())
                    .doesNotContain("io.micrometer.registry.otlp.OtlpMeterRegistry");
        });
    }

    @Test
    void exportsNothingWhenDisabled() {
        runner.withPropertyValues("opentelemetry.metrics.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SdkMeterProvider.class);
            assertThat(context).doesNotHaveBean(MeterRegistry.class);
        });
    }

    private ApplicationContextRunner enabled() {
        return runner
                .withPropertyValues("opentelemetry.metrics.enabled=true")
                .withBean(MetricExporter.class, () -> exporter);
    }

    @EnableConfigurationProperties(OpenTelemetryMetricsProperties.class)
    static class PropertiesConfiguration {
    }
}
