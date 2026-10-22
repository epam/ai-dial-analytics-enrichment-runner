package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.scan.DeterministicId;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The one-time db-scheduler task that drives a backfill run one batch at a time. Its data is the run
 * id; the handler delegates to {@link BackfillExecutor}, which drains a batch of pages and schedules
 * the next batch instance until the run finishes (or is paused/cancelled). A failed batch is handled by
 * {@link BackfillBatchFailureHandler}: recorded on the run, counted, and retried on the worker's jittered backoff
 * from the run's committed cursor.
 */
@Component
public class BackfillTask {

    public static final String TASK_NAME = "backfill-run";

    private final OneTimeTask<String> task;

    public BackfillTask(ObjectProvider<BackfillExecutor> executorProvider, BackfillBatchFailureHandler failureHandler) {
        this.task = Tasks.oneTime(TASK_NAME, String.class)
                .onFailure(failureHandler)
                .execute((instance, context) -> executorProvider.getObject().run(instance.getData()));
    }

    public OneTimeTask<String> task() {
        return task;
    }

    /**
     * Unique instance id for the next batch of a run, namespaced by the cursor it starts from. Hashed with
     * {@link DeterministicId}'s digest rather than a local copy: this id has to dedup a re-chained batch
     * against itself, and two implementations of "the same cursor hashes the same" would be free to drift.
     */
    public static String instanceId(String runId, String cursor) {
        return instancePrefix(runId) + DeterministicId.sha1Hex(cursor == null ? "start" : cursor);
    }

    /** The prefix every batch id of a run starts with, continuation or resume. */
    public static String instancePrefix(String runId) {
        return TASK_NAME + "#" + runId + "#";
    }

    /**
     * Instance id for the batch a resume schedules: the continuation id suffixed with the resume's own instant.
     *
     * <p>Fresh by construction, so a resume can never be swallowed by {@code scheduleIfNotExists} against an
     * execution of the same run that is still executing — one that would then exit on the {@code paused} it read
     * and leave the run {@code running} with no chain. Two resumes cannot share a millisecond, because each must
     * first find the run paused. Needs no column: an older execution that outlives the resume is stopped at its
     * first page commit, which finds the cursor or the state moved.
     */
    public static String resumeInstanceId(String runId, String cursor, Instant resumedAt) {
        return instanceId(runId, cursor) + "#r" + resumedAt.toEpochMilli();
    }
}
