package com.epam.aidial.enrichment.worker.upsert;

import com.epam.aidial.enrichment.adas.AdasCompatibilityState;
import com.epam.aidial.enrichment.adas.AdasUpsertClient;
import com.epam.aidial.enrichment.adas.AdasWorkGate;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.dlq.DlqCircuitBreaker;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.AdasIncompatibleHoldException;
import com.epam.aidial.enrichment.exception.AdasTransportException;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.observability.status.PipelineActivity;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.support.SettableClock;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.PreFoldWorkItem;
import com.epam.aidial.enrichment.worker.ProvenanceColumns;
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write-back lane's half of the paid-work gate.
 *
 * <p>This is the lane where holding matters most, because the item's payload <em>is</em> the paid-for
 * result. Left to its ordinary path a write refused by a mismatched ADAS consumes its attempts and then
 * archives a computed result — for a reason that is about to stop being true. What the DLQ exists for is a
 * row this runner cannot process; a deploy in progress is not that.
 */
class UpsertServiceTest {

    private static final String PIPELINE_NAME = "r1";
    private static final long GENERATION = 3;

    private final PipelineCache pipelineCache = mock(PipelineCache.class);
    private final AdasUpsertClient upsertClient = mock(AdasUpsertClient.class);
    private final GroupStateWriter groupStateWriter = mock(GroupStateWriter.class);
    private final DlqItemWriter dlqItemWriter = mock(DlqItemWriter.class);
    private final DlqCircuitBreaker dlqCircuitBreaker = mock(DlqCircuitBreaker.class);
    private final RunnerMetrics runnerMetrics = mock(RunnerMetrics.class);
    private final RunnerTracer runnerTracer = new RunnerTracer(OpenTelemetry.noop());
    private final JsonMapper jsonMapper = mock(JsonMapper.class);
    private final AdasCompatibilityState compatibilityState = new AdasCompatibilityState();

    private final UpsertService service = new UpsertService(pipelineCache, upsertClient, groupStateWriter,
            dlqItemWriter, jsonMapper, dlqCircuitBreaker, runnerMetrics, runnerTracer,
            new AdasWorkGate(compatibilityState),
            new SettableClock(Instant.parse("2026-07-28T12:00:00Z")), mock(PipelineActivity.class));

    @Test
    void pendingWriteBackIsNotAttemptedWhileAdasIsIncompatible() {
        cachePipeline();
        compatibilityState.markIncompatible("no Adas-Contract-Version echo");

        assertThatThrownBy(() -> service.write("w0", payload()))
                .isInstanceOf(AdasIncompatibleHoldException.class);

        verify(upsertClient, never()).upsert(anyString(), anyList());
        // Neither archived nor counted against the breaker: the write never reached ADAS, so nothing about
        // the pipeline's health was learned — and archiving is what would destroy the computed rows.
        verify(dlqItemWriter, never()).upsert(any());
        verify(dlqCircuitBreaker, never()).record(anyString(), anyInt(), anyInt());
    }

    /**
     * The F1 case: a write-back POST that times out. It must retry as a transient UPSERT failure — not be read
     * as a missing contract echo, which would hold every paid work item — and must not be archived.
     */
    @Test
    void writeBackTimeoutIsTransientFailureInTheUpsertStage() {
        cachePipeline();
        when(upsertClient.upsert(anyString(), anyList())).thenThrow(AdasTransportException.of(
                HttpMethod.POST, "/v1/pipelines/" + PIPELINE_NAME + "/rows", 200, 200, new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> service.write("w0", payload()))
                .isInstanceOfSatisfying(TransientChunkException.class,
                        e -> assertThat(e.getStage()).isEqualTo(DlqStage.UPSERT));
        verify(dlqItemWriter, never()).upsert(any());
        assertThat(compatibilityState.isCompatible()).isTrue();
    }

    @Test
    void theSameWriteBackLandsOnceCompatibilityReturns() {
        cachePipeline();
        when(upsertClient.upsert(anyString(), anyList())).thenReturn(1);
        compatibilityState.markIncompatible("no Adas-Contract-Version echo");
        compatibilityState.markCompatible();

        service.write("w0", payload());

        verify(upsertClient).upsert(eq(PIPELINE_NAME), anyList());
    }

    /**
     * The costliest of the three pre-fold cases, and the one the stale-declaration guard handled worst: a
     * write enqueued before the evaluator fold holds rows the model was <em>already paid for</em>, and its
     * payload is their only copy — the compute that produced them completed and its chunk is gone. The
     * guard matched it (0 != the cached generation) and dropped it at {@code info}, indistinguishable from
     * a knob edit. It is written instead, normalised onto the live generation.
     */
    @Test
    void preFoldWriteAdoptsTheLiveGenerationAndLands() {
        cachePipeline();
        when(upsertClient.upsert(anyString(), anyList())).thenReturn(1);

        service.write("w0", preFoldWrite());

        verify(upsertClient).upsert(eq(PIPELINE_NAME), anyList());
        // Nothing dead-lettered and nothing counted against the breaker: the write ran, and a queue that
        // outlived the old image must not pause every pipeline that had work in it at cut-over.
        verify(dlqItemWriter, never()).upsert(any());
        verify(dlqCircuitBreaker, never()).record(anyString(), anyInt(), anyInt());
        verify(runnerMetrics).recordPreFoldWorkItem(PreFoldWorkItem.Lane.UPSERT);
    }

    /**
     * The retired provenance column is <strong>removed and not replaced</strong>. Removed because ADAS
     * refuses a write naming {@code evaluator_version} with 422, so leaving it would dead-letter the very
     * rows this lane exists to save. Not replaced with {@code pipeline_generation}, because these rows
     * were computed under the pre-fold declaration: stamping the generation this process happens to serve
     * now would assert a lineage that did not produce them, and an absent provenance column is both
     * permitted by ADAS and the honest record.
     */
    @Test
    void preFoldRowsReachTheWriteWithTheRetiredProvenanceColumnRemovedAndNothingPutBack() {
        cachePipeline();
        when(upsertClient.upsert(anyString(), anyList())).thenReturn(1);

        service.write("w0", preFoldWrite());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(upsertClient).upsert(eq(PIPELINE_NAME), captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(row -> {
            assertThat(row).doesNotContainKey(ProvenanceColumns.RETIRED_EVALUATOR_VERSION);
            assertThat(row).doesNotContainKey(ProvenanceColumns.PIPELINE_GENERATION);
            // The label itself is untouched — it is the paid-for result.
            assertThat(row).containsEntry("event_id", "e0").containsEntry("label", "positive");
        });
    }

    /**
     * The likeliest write-phase dead letter of a replay is ADAS permanently rejecting it, and the write item's id
     * extends the backfill chunk's run-namespaced one — so the archive names the run. A live write names none.
     */
    @Test
    void backfillWritesPermanentRejectionAndAbsentPipelineArchiveNameTheRun() {
        cachePipeline();
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
        when(upsertClient.upsert(anyString(), anyList())).thenThrow(new RestClientResponseException(
                "422 write_output_column_required", 422, "Unprocessable", null, null, null));
        String backfillWrite = DeterministicId.upsertInstanceId(
                DeterministicId.backfillChunkInstanceId("run-7", GENERATION, "c1", 0));

        service.write(backfillWrite, payload());
        service.write(DeterministicId.upsertInstanceId(
                DeterministicId.chunkInstanceId(PIPELINE_NAME, GENERATION, "c1", 0)), payload());
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(null);
        service.write(backfillWrite, payload());

        ArgumentCaptor<DlqItemEntity> archived = ArgumentCaptor.forClass(DlqItemEntity.class);
        verify(dlqItemWriter, times(3)).upsert(archived.capture());
        assertThat(archived.getAllValues()).extracting(DlqItemEntity::getRunId)
                .containsExactly("run-7", null, "run-7");
    }

    /** Deserialized the way the queue does it, so the test seeds the shape the old image actually wrote. */
    private static UpsertPayload preFoldWrite() {
        String preFoldJson = """
                {"pipelineName":"r1","evaluatorVersion":2,
                 "rows":[{"event_id":"e0","label":"positive","evaluator_version":2}]}""";
        return new JacksonSerializer()
                .deserialize(UpsertPayload.class, preFoldJson.getBytes(StandardCharsets.UTF_8));
    }

    private void cachePipeline() {
        PipelineDto pipeline = TestPipelines.builder(PIPELINE_NAME).generation(GENERATION).build();
        when(pipelineCache.get(PIPELINE_NAME)).thenReturn(pipeline);
    }

    private static UpsertPayload payload() {
        return new UpsertPayload(PIPELINE_NAME, GENERATION,
                List.of(Map.of("event_id", "e0", "label", "positive")));
    }
}
