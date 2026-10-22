package com.epam.aidial.enrichment.scan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicIdTest {

    @Test
    void sameInputsProduceSameId() {
        assertThat(DeterministicId.chunkInstanceId("r1", 3, "cursor-A", 0))
                .isEqualTo(DeterministicId.chunkInstanceId("r1", 3, "cursor-A", 0));
    }

    @Test
    void idDiffersByIndexCursorAndVersion() {
        String base = DeterministicId.chunkInstanceId("r1", 3, "cursor-A", 0);
        assertThat(base).isNotEqualTo(DeterministicId.chunkInstanceId("r1", 3, "cursor-A", 1));
        assertThat(base).isNotEqualTo(DeterministicId.chunkInstanceId("r1", 3, "cursor-B", 0));
        assertThat(base).isNotEqualTo(DeterministicId.chunkInstanceId("r1", 4, "cursor-A", 0));
    }

    /**
     * The version component is the pipeline's {@code generation} since the evaluator fold, which widens what
     * makes an id change: {@code generation} moves for <em>any</em> declaration edit, where an evaluator
     * version moved only for a prompt or an expression. So an edit to an execution knob alone re-keys every
     * in-flight work item, and the residual-overlap dedup these ids provide no longer spans it. That is a
     * real behaviour change, bounded by the same argument as a re-identification — and worth pinning,
     * because the alternative reading is that the substitution was a rename.
     */
    @Test
    void knobEditRekeysEveryWorkItemBecauseGenerationMoves() {
        String before = DeterministicId.chunkInstanceId("r1", 17, "cursor-A", 0);
        String after = DeterministicId.chunkInstanceId("r1", 18, "cursor-A", 0);

        assertThat(after).isNotEqualTo(before);
        // Both still count toward the same pipeline's in-flight depth: only the hashed basis moved.
        assertThat(before).startsWith(DeterministicId.pipelinePrefix("r1"));
        assertThat(after).startsWith(DeterministicId.pipelinePrefix("r1"));

        // The same widening on the other two namespaces, so no lane is left keyed on a value that is stable
        // across a declaration edit.
        assertThat(DeterministicId.groupInstanceId("r1", 18, "chat-7", 100L))
                .isNotEqualTo(DeterministicId.groupInstanceId("r1", 17, "chat-7", 100L));
        assertThat(DeterministicId.backfillChunkInstanceId("run-1", 18, "cursor-A", 0))
                .isNotEqualTo(DeterministicId.backfillChunkInstanceId("run-1", 17, "cursor-A", 0));
    }

    /**
     * A generation past {@code 2^31} produces its own id rather than colliding with the truncated one an
     * {@code int} parameter would have given it.
     */
    @Test
    void generationBeyondTheIntRangeIsNotTruncated() {
        assertThat(DeterministicId.chunkInstanceId("r1", 3_000_000_000L, "cursor-A", 0))
                .isNotEqualTo(DeterministicId.chunkInstanceId("r1", 3_000_000_000L - (1L << 32), "cursor-A", 0));
    }

    @Test
    void idCarriesThePipelinePrefixForBackpressureFiltering() {
        assertThat(DeterministicId.chunkInstanceId("r1", 3, "cursor-A", 0))
                .startsWith(DeterministicId.pipelinePrefix("r1"));
    }

    /**
     * A backfill chunk rides the same {@code enrich-chunk} task as a live one but is deliberately outside any
     * pipeline's in-flight count, so its namespace must be unreachable from a pipeline prefix. The identity
     * change is what made this worth pinning: a UUID could never have been the namespace token, but a
     * pipeline name can be any lower-case word — and under the previous {@code bf#} namespace a pipeline
     * named {@code bf} would have counted every other pipeline's backfill chunks as its own queue depth and
     * backpressured itself into silence.
     */
    @Test
    void backfillChunkIsOutsideEveryPipelinePrefix() {
        String backfill = DeterministicId.backfillChunkInstanceId("bf", 3, "cursor-A", 0);

        assertThat(backfill).doesNotStartWith(DeterministicId.pipelinePrefix("bf"));
        assertThat(backfill).doesNotStartWith(DeterministicId.pipelinePrefix("b"));
        // Still deterministic, and still namespaced away from a live chunk at the same cursor.
        assertThat(backfill).isEqualTo(DeterministicId.backfillChunkInstanceId("bf", 3, "cursor-A", 0));
        assertThat(backfill).isNotEqualTo(DeterministicId.chunkInstanceId("bf", 3, "cursor-A", 0));
    }

    @Test
    void nullCursorIsHandled() {
        assertThat(DeterministicId.chunkInstanceId("r1", 3, null, 0)).isNotBlank();
    }

    @Test
    void theRunIsRecoveredFromBackfillChunkAndItsWrite() {
        String chunk = DeterministicId.backfillChunkInstanceId("0e6b-run", 3, "c1", 2);
        assertThat(DeterministicId.backfillRunIdOf(chunk)).isEqualTo("0e6b-run");
        assertThat(DeterministicId.backfillRunIdOf(DeterministicId.upsertInstanceId(chunk))).isEqualTo("0e6b-run");
    }

    @Test
    void requeuedRunItemCarriesItsRunAndStillCountsTowardThePipeline() {
        String requeued = DeterministicId.requeueInstanceId("p1", 17, "0e6b-run");
        assertThat(requeued).isEqualTo("p1#requeue#bf#0e6b-run#17").startsWith(DeterministicId.pipelinePrefix("p1"));
        assertThat(DeterministicId.backfillRunIdOf(requeued)).isEqualTo("0e6b-run");
        assertThat(DeterministicId.backfillRunIdOf(DeterministicId.upsertInstanceId(requeued))).isEqualTo("0e6b-run");
    }

    @Test
    void liveGroupAndLiveRequeueIdsCarryNoRun() {
        assertThat(DeterministicId.backfillRunIdOf(DeterministicId.chunkInstanceId("p1", 3, "c1", 0))).isNull();
        assertThat(DeterministicId.backfillRunIdOf(
                DeterministicId.upsertInstanceId(DeterministicId.chunkInstanceId("p1", 3, "c1", 0)))).isNull();
        assertThat(DeterministicId.backfillRunIdOf(DeterministicId.groupInstanceId("p1", 3, "g", 1))).isNull();
        String liveRequeue = DeterministicId.requeueInstanceId("p1", 17, null);
        assertThat(liveRequeue).isEqualTo("p1#requeue#17");
        assertThat(DeterministicId.backfillRunIdOf(liveRequeue)).isNull();
        assertThat(DeterministicId.backfillRunIdOf(null)).isNull();
    }
}
