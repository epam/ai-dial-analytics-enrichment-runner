package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.AdasContractMismatchException;
import com.epam.aidial.enrichment.exception.AdasIncompatibleHoldException;
import com.epam.aidial.enrichment.exception.PipelineCacheColdException;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.worker.group.GroupFailureHandler;
import com.epam.aidial.enrichment.worker.group.GroupPayload;
import com.epam.aidial.enrichment.worker.group.PoisonGroupException;
import com.epam.aidial.enrichment.worker.upsert.UpsertFailureHandler;
import com.epam.aidial.enrichment.worker.upsert.UpsertPayload;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A work item that fails on a condition that clears by itself is <em>held</em>, on all three lanes:
 * rescheduled with its attempt budget untouched, and never archived however long the condition lasts. Both
 * of today's conditions are exercised here, against the one mechanism they share — a pipeline cache this
 * process has not synced yet, and an ADAS contract skew — because the treatment is defined once and the
 * three lanes must not be able to disagree about it.
 *
 * <p>The assertion that carries the weight is the rescheduled {@link ExecutionComplete}'s result being
 * {@code OK}. That is not a detail of taste — it is the mechanism. db-scheduler's
 * {@link ExecutionOperations#reschedule} writes {@code consecutive_failures = 0} for an OK result and
 * {@code consecutiveFailures + 1} otherwise, so a hold expressed on the ordinary failure arm would leave
 * the counter elevated for the whole length of the condition and the <em>first real</em> failure after it
 * cleared would archive on its first attempt — turning a startup or deploy window into lost work at the
 * moment it ended.
 *
 * <p>Each lane is exercised at a failure count <b>past</b> its max attempts, which is where an unheld item
 * would archive. That is the shape of the bug being prevented, not an edge case: the ladder is seconds, and
 * a registry that stays unreachable outlasts it easily.
 */
class WorkHoldTest {

    private static final String PIPELINE_NAME = "r1";
    private static final int MAX_ATTEMPTS = 3;

    private final DlqItemWriter dlqItemWriter = mock(DlqItemWriter.class);
    private final WorkerProperties workerProperties =
            new WorkerProperties(1, 1, MAX_ATTEMPTS, Duration.ofSeconds(10));

    private final ChunkFailureHandler chunkHandler =
            new ChunkFailureHandler(dlqItemWriter, new JsonMapperConfiguration().jsonMapper(), workerProperties);
    private final GroupFailureHandler groupHandler =
            new GroupFailureHandler(dlqItemWriter, new JsonMapperConfiguration().jsonMapper(), workerProperties);
    private final UpsertFailureHandler upsertHandler =
            new UpsertFailureHandler(dlqItemWriter, new JsonMapperConfiguration().jsonMapper(), workerProperties);

    @SuppressWarnings("unchecked")
    private final ExecutionOperations<ChunkPayload> chunkOperations = mock(ExecutionOperations.class);
    @SuppressWarnings("unchecked")
    private final ExecutionOperations<GroupPayload> groupOperations = mock(ExecutionOperations.class);
    @SuppressWarnings("unchecked")
    private final ExecutionOperations<UpsertPayload> upsertOperations = mock(ExecutionOperations.class);

    /**
     * The lane where dropping the item is irreversible: the scan cursor advanced in the same transaction
     * that enqueued this chunk, so nothing would ever read its rows again.
     */
    @Test
    void chunkDueBeforeTheFirstRegistrySyncIsHeld() {
        ExecutionComplete complete = chunkFailure(MAX_ATTEMPTS + 5,
                new PipelineCacheColdException("chunk r1#h#c0"));

        chunkHandler.onFailure(complete, chunkOperations);

        assertHeld(chunkOperations);
        verify(dlqItemWriter, never()).upsert(any());
        verify(chunkOperations, never()).remove();
    }

    @Test
    void groupItemDueBeforeTheFirstRegistrySyncIsHeld() {
        ExecutionComplete complete = groupFailure(MAX_ATTEMPTS + 5,
                new PipelineCacheColdException("group item r1#g#abc"));

        groupHandler.onFailure(complete, groupOperations);

        assertHeld(groupOperations);
        verify(dlqItemWriter, never()).upsert(any());
        verify(groupOperations, never()).remove();
    }

    /** The lane where holding matters most: this item's payload <em>is</em> the paid-for result. */
    @Test
    void writeBackDueBeforeTheFirstRegistrySyncIsHeld() {
        ExecutionComplete complete = upsertFailure(MAX_ATTEMPTS + 5,
                new PipelineCacheColdException("write-back for pipeline r1"));

        upsertHandler.onFailure(complete, upsertOperations);

        assertHeld(upsertOperations);
        verify(dlqItemWriter, never()).upsert(any());
        verify(upsertOperations, never()).remove();
    }

    /** The cause may arrive wrapped by a lane on its way out, so the walk is over the whole chain. */
    @Test
    void wrappedHoldCauseIsStillHeld() {
        ExecutionComplete complete = chunkFailure(MAX_ATTEMPTS + 5,
                new TransientChunkException(DlqStage.UPSERT, new PipelineCacheColdException("chunk c0")));

        chunkHandler.onFailure(complete, chunkOperations);

        assertHeld(chunkOperations);
        verify(dlqItemWriter, never()).upsert(any());
    }

    /**
     * The hold must stay distinct from a permanent failure. A poison group is dead-lettered inside
     * {@code GroupWorkerService} and never reaches this handler at all — but if one ever did, it must take
     * the archive branch rather than looping forever behind a condition it does not have.
     */
    @Test
    void poisonGroupIsStillPermanent() {
        ExecutionComplete complete = groupFailure(MAX_ATTEMPTS,
                new PoisonGroupException(DlqStage.GROUP_FETCH, "422 not a group pipeline", null));

        groupHandler.onFailure(complete, groupOperations);

        verify(dlqItemWriter).upsert(any(DlqItemEntity.class));
        verify(groupOperations).remove();
    }

    /** An ordinary transient failure past the ladder still archives: the hold must not swallow everything. */
    @Test
    void ordinaryExhaustionStillArchives() {
        ExecutionComplete complete = upsertFailure(MAX_ATTEMPTS,
                new TransientChunkException(DlqStage.UPSERT, new IllegalStateException("ADAS 503")));

        upsertHandler.onFailure(complete, upsertOperations);

        verify(dlqItemWriter).upsert(any(DlqItemEntity.class));
        verify(upsertOperations).remove();
    }

    /**
     * The other held condition, on the lane where dropping the item is irreversible. This is the gate's own
     * signal: the item became due while the skew was already recorded, so it is held before any ADAS call,
     * any DIAL call and any write.
     */
    @Test
    void chunkHeldByTheContractGateIsRescheduledWithoutConsumingAnAttempt() {
        ExecutionComplete complete = chunkFailure(MAX_ATTEMPTS + 5,
                new AdasIncompatibleHoldException("chunk r1#h#c0", "no echo"));

        chunkHandler.onFailure(complete, chunkOperations);

        assertHeld(chunkOperations);
        verify(dlqItemWriter, never()).upsert(any());
        verify(chunkOperations, never()).remove();
    }

    /**
     * The skew's other arm, and the one the gate's cheap check cannot cover: the <em>first</em> item to meet
     * a fresh skew discovers it from inside its own call, when the recorded state still read compatible. The
     * interceptor's refusal reaches the handler wrapped by the lane, so the walk over the cause chain is
     * what makes this a hold rather than an archive.
     */
    @Test
    void chunkThatDiscoveredTheSkewInItsOwnCallIsHeldToo() {
        ExecutionComplete complete = chunkFailure(MAX_ATTEMPTS + 5,
                new TransientChunkException(DlqStage.DIAL_CALL,
                        AdasContractMismatchException.noEcho("/v1/pipelines/r1/scan")));

        chunkHandler.onFailure(complete, chunkOperations);

        assertHeld(chunkOperations);
        verify(dlqItemWriter, never()).upsert(any());
    }

    /** The costliest lane per item, so the one where failing to hold costs the most. */
    @Test
    void groupItemIsHeldOnContractSkew() {
        ExecutionComplete complete = groupFailure(MAX_ATTEMPTS + 5,
                new AdasIncompatibleHoldException("group chat-a", "echo was 2, not 1"));

        groupHandler.onFailure(complete, groupOperations);

        assertHeld(groupOperations);
        verify(dlqItemWriter, never()).upsert(any());
        verify(groupOperations, never()).remove();
    }

    /**
     * The lane where holding matters most: this item's payload <em>is</em> the paid-for result, so archiving
     * it during a version skew turns a deploy window into work an operator has to requeue by hand — and,
     * during an ADAS-first window, a requeue that only works with the pipeline enabled.
     */
    @Test
    void writeBackIsHeldRatherThanArchivedWithItsComputedRowsOnSkew() {
        ExecutionComplete complete = upsertFailure(MAX_ATTEMPTS + 5,
                new TransientChunkException(DlqStage.UPSERT,
                        AdasContractMismatchException.mismatch("/v1/pipelines/r1/rows", "2")));

        upsertHandler.onFailure(complete, upsertOperations);

        assertHeld(upsertOperations);
        verify(dlqItemWriter, never()).upsert(any());
        verify(upsertOperations, never()).remove();
    }

    /**
     * What the OK result buys, stated as the scenario it exists for: an item held many times and then
     * failing for an ordinary transient reason still has its whole attempt budget, because each hold reset
     * the count rather than adding to it.
     */
    @Test
    void anOrdinaryTransientFailureAfterManyHoldsStillTakesTheLadderNotTheArchive() {
        for (int i = 0; i < 20; i++) {
            chunkHandler.onFailure(chunkFailure(MAX_ATTEMPTS + 5,
                    new AdasIncompatibleHoldException("chunk r1#h#c0", "no echo")), chunkOperations);
        }
        // Every hold wrote consecutive_failures = 0, so the first genuine failure afterwards is attempt 1.
        ExecutionComplete transientFailure = chunkFailure(1,
                new TransientChunkException(DlqStage.DIAL_CALL, "503 from DIAL", null));

        chunkHandler.onFailure(transientFailure, chunkOperations);

        verify(chunkOperations).reschedule(eq(transientFailure), any(Instant.class));
        verify(dlqItemWriter, never()).upsert(any());
        verify(chunkOperations, never()).remove();
    }

    /**
     * The mechanism: rescheduled with an <b>OK</b> result, which is what resets {@code consecutive_failures}.
     * Asserting only "reschedule was called" would pass for the ordinary backoff arm too, which is the
     * behaviour this exists to rule out.
     */
    private static <T> void assertHeld(ExecutionOperations<T> operations) {
        ArgumentCaptor<ExecutionComplete> rescheduled = ArgumentCaptor.forClass(ExecutionComplete.class);
        ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
        verify(operations).reschedule(rescheduled.capture(), when.capture());
        assertThat(rescheduled.getValue().getResult()).isEqualTo(ExecutionComplete.Result.OK);
        assertThat(when.getValue()).isAfter(Instant.now());
        assertThat(when.getValue()).isBefore(Instant.now().plus(WorkHold.DELAY).plusSeconds(5));
    }

    private static ExecutionComplete chunkFailure(int consecutiveFailures, Throwable cause) {
        ChunkPayload payload = new ChunkPayload(PIPELINE_NAME, 3,
                List.of(new ChunkRow("e0", Map.of("text", "hi"))));
        return failure(new TaskInstance<>(EnrichChunkTask.TASK_NAME, PIPELINE_NAME + "#h#c0", payload),
                consecutiveFailures, cause);
    }

    private static ExecutionComplete groupFailure(int consecutiveFailures, Throwable cause) {
        GroupPayload payload = new GroupPayload(PIPELINE_NAME, 3, "chat-a", 100L);
        return failure(new TaskInstance<>("enrich-group", PIPELINE_NAME + "#g#abc", payload),
                consecutiveFailures, cause);
    }

    private static ExecutionComplete upsertFailure(int consecutiveFailures, Throwable cause) {
        UpsertPayload payload = new UpsertPayload(PIPELINE_NAME, 3, List.of(Map.of("chat_id", "chat-a")));
        return failure(new TaskInstance<>("upsert-rows", PIPELINE_NAME + "#g#abc#u", payload),
                consecutiveFailures, cause);
    }

    private static ExecutionComplete failure(TaskInstance<?> instance, int consecutiveFailures, Throwable cause) {
        Execution execution = new Execution(Instant.now(), instance, false, null, null, null,
                consecutiveFailures, null, 1);
        Instant now = Instant.now();
        return ExecutionComplete.failure(execution, now.minusMillis(10), now, cause);
    }
}
