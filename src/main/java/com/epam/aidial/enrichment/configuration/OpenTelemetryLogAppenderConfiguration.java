package com.epam.aidial.enrichment.configuration;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;

/**
 * Routes log4j2 events captured by the {@code <OpenTelemetry>} appender (log4j2-spring.xml) into the
 * OpenTelemetry SDK configured by spring-boot-starter-opentelemetry. The official starter does not install
 * the appender automatically — without this, the appender stays in its pre-install state, buffers the first
 * {@code numLogsCapturedBeforeOtelInstall} events and drops the rest, so no log record ever reaches the OTLP
 * exporter even though traces and metrics export normally. The install must therefore run once the Spring
 * context has wired the SDK; events emitted before that are replayed from the appender's buffer.
 */
@Configuration
public class OpenTelemetryLogAppenderConfiguration {

    public OpenTelemetryLogAppenderConfiguration(ObjectProvider<OpenTelemetry> openTelemetry) {
        openTelemetry.ifAvailable(OpenTelemetryAppender::install);
    }
}
