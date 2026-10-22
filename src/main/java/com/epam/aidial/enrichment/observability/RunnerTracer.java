package com.epam.aidial.enrichment.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Thin wrapper over an OpenTelemetry {@link Tracer} for the runner's explicit spans (scan-tick,
 * DIAL call, upsert). Correlation is carried as string span attributes ({@code pipeline.id},
 * {@code chunk.id}, {@code pipeline.generation}, {@code grain.key}); those must stay off Micrometer
 * metric tags, since {@code grain.key} is unbounded cardinality.
 *
 * <p>Falls back to a no-op tracer when OpenTelemetry autoconfiguration is absent (e.g. plain unit
 * tests), so span instrumentation never changes business behavior.
 */
@Component
public class RunnerTracer {

    private static final String INSTRUMENTATION_NAME = "analytics-enrichment-runner";

    private final Tracer tracer;

    @Autowired
    public RunnerTracer(ObjectProvider<OpenTelemetry> openTelemetry) {
        this(openTelemetry.getIfAvailable(OpenTelemetry::noop));
    }

    public RunnerTracer(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    /** Runs {@code body} inside a new span, recording its result value. */
    public <T> T inSpan(String name, Map<String, String> attributes, Supplier<T> body) {
        Span span = newSpan(name, attributes);
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** Runs {@code body} inside a new span with no return value. */
    public void inSpan(String name, Map<String, String> attributes, Runnable body) {
        inSpan(name, attributes, () -> {
            body.run();
            return null;
        });
    }

    private Span newSpan(String name, Map<String, String> attributes) {
        SpanBuilder builder = tracer.spanBuilder(name);
        if (attributes != null) {
            attributes.forEach((key, value) -> {
                if (value != null) {
                    builder.setAttribute(AttributeKey.stringKey(key), value);
                }
            });
        }
        return builder.startSpan();
    }
}
