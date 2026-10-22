package com.epam.aidial.enrichment.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerTracerTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build();
    private final RunnerTracer tracer = new RunnerTracer(sdk);

    @AfterEach
    void close() {
        tracerProvider.close();
    }

    @Test
    void recordsSpanWithAttributesAndReturnsBodyValue() {
        String result = tracer.inSpan("enrichment.dial-call",
                Map.of("pipeline.name", "r1", "grain.key", "g1"), () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("enrichment.dial-call");
        assertThat(span.getAttributes().asMap())
                .containsEntry(AttributeKey.stringKey("pipeline.name"), "r1")
                .containsEntry(AttributeKey.stringKey("grain.key"), "g1");
        assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }

    @Test
    void marksSpanErrorAndRethrowsOnFailure() {
        RuntimeException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> tracer.inSpan("enrichment.upsert", Map.of(), () -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).isNotEmpty(); // recordException added an event
    }
}
