package com.epam.aidial.enrichment.integration;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real application context with every OTLP signal enabled, which the
 * {@code OpenTelemetryMetricsConfigurationTest} slice cannot do.
 *
 * <p>The slice pins the behaviour of the metrics configuration in isolation; what only appears in the full
 * context is how that configuration collides with Spring Boot's own OTLP metrics auto-configuration. That
 * auto-configuration is active by default whenever {@code micrometer-registry-otlp} is on the classpath —
 * it arrives transitively, so it is on — and {@code application.yml} pins it off precisely so Micrometer's
 * exporter does not run beside the SDK-backed one, double-exporting every meter under the second resource
 * this design exists to eliminate. Delete that pin and the slice still passes; this test does not.
 */
@SpringBootTest(properties = {
        "opentelemetry.metrics.enabled=true",
        "management.tracing.export.enabled=true",
        "management.logging.export.otlp.enabled=true"
})
class OpenTelemetryExportIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    @Test
    @Timeout(60)
    void allSignalsShareOneSdkAndOneResource() {
        // The meter provider reaching the SDK bean is what puts metrics on the same resource as the
        // tracer and logger providers, which Boot builds from this very same Resource bean.
        assertThat(context.getBean(OpenTelemetrySdk.class).getSdkMeterProvider())
                .isSameAs(context.getBean(SdkMeterProvider.class));
        assertThat(context.getBean(Resource.class).getAttribute(
                io.opentelemetry.api.common.AttributeKey.stringKey("telemetry.sdk.name")))
                .isEqualTo("opentelemetry");
    }

    @Test
    @Timeout(60)
    void micrometersOwnOtlpExporterDoesNotRunAlongside() {
        assertThat(context.getBeansOfType(MeterRegistry.class))
                .containsKeys("openTelemetryMeterRegistry", "prometheusMeterRegistry")
                .doesNotContainKey("otlpMeterRegistry");
    }
}
