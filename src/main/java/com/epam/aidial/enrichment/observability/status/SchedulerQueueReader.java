package com.epam.aidial.enrichment.observability.status;

import com.epam.aidial.enrichment.backfill.BackfillTask;
import com.epam.aidial.enrichment.backfill.RunState;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The status heartbeat's and the runtime view's reads of {@code scheduled_tasks}. None of them reads
 * {@code task_data}.
 *
 * <p>Each read is bounded: one row per recurring pipeline instance, one row per task name, one row for the
 * longest execution, and at most {@value #ORPHAN_LIMIT} orphaned backfill runs.
 */
@Component
@RequiredArgsConstructor
public class SchedulerQueueReader {

    static final int ORPHAN_LIMIT = 20;

    private final JdbcTemplate jdbcTemplate;

    /** A backfill run that reads {@code running} but has no {@code backfill-run} execution. */
    public record OrphanedRun(String runId, String pipelineName) {
    }

    /**
     * A pipeline's recurring tick.
     *
     * @param executionTime when it is due, or when the running fire was due
     * @param picked        whether it is executing now
     */
    public record NextFire(Instant executionTime, boolean picked) {
    }

    /**
     * Pending executions of one task.
     *
     * @param depth   every execution of the task
     * @param overdue executions due before the cutoff that nobody has picked
     */
    public record TaskDepth(long depth, long overdue) {
    }

    /**
     * The longest running execution. Its duration is measured from its due time, because db-scheduler records
     * no pick time.
     */
    public record Executing(String taskName, Instant executionTime) {
    }

    /**
     * One pipeline's recurring execution, as the runtime view reads it.
     *
     * @param executionTime       when it is due, or when the running fire was due
     * @param picked              whether it is executing now
     * @param consecutiveFailures fires in a row that threw; a fire that completes, a gated skip included, resets it
     * @param lastFailure         when a fire last threw, or null if none ever has
     */
    public record RecurringExecution(Instant executionTime, boolean picked, int consecutiveFailures, Instant lastFailure) {
    }

    /** The pipeline's recurring execution under {@code taskName}, or empty when it is not scheduled. */
    public Optional<RecurringExecution> recurring(String taskName, String pipelineName) {
        List<RecurringExecution> rows = jdbcTemplate.query("""
                        SELECT execution_time, picked, consecutive_failures, last_failure FROM scheduled_tasks
                         WHERE task_name = ? AND task_instance = ?
                        """,
                (rs, i) -> {
                    Timestamp lastFailure = rs.getTimestamp(4);
                    return new RecurringExecution(rs.getTimestamp(1).toInstant(), rs.getBoolean(2), rs.getInt(3),
                            lastFailure == null ? null : lastFailure.toInstant());
                }, taskName, pipelineName);
        return rows.stream().findFirst();
    }

    /** The recurring tick of each pipeline, keyed by pipeline name (the instance id of a recurring tick). */
    public Map<String, NextFire> nextFires() {
        List<String> taskNames = List.copyOf(PipelineRouting.PIPELINE_TASK_NAMES);
        Map<String, NextFire> fires = new HashMap<>();
        jdbcTemplate.query("SELECT task_instance, execution_time, picked FROM scheduled_tasks WHERE task_name IN ("
                        + String.join(", ", Collections.nCopies(taskNames.size(), "?")) + ")",
                rs -> {
                    fires.put(rs.getString(1), new NextFire(rs.getTimestamp(2).toInstant(), rs.getBoolean(3)));
                }, taskNames.toArray());
        return fires;
    }

    /** Depth and overdue count per task name, sorted by name. {@code overdueBefore} is the overdue cutoff. */
    public Map<String, TaskDepth> depthByTask(Instant overdueBefore) {
        Map<String, TaskDepth> depths = new TreeMap<>();
        jdbcTemplate.query("""
                        SELECT task_name, count(*), count(*) FILTER (WHERE NOT picked AND execution_time < ?)
                          FROM scheduled_tasks GROUP BY task_name
                        """,
                rs -> {
                    depths.put(rs.getString(1), new TaskDepth(rs.getLong(2), rs.getLong(3)));
                }, Timestamp.from(overdueBefore));
        return depths;
    }

    /**
     * Running backfill runs with no {@code backfill-run} execution, so nothing will ever advance them. A live run
     * always has one — its batch is scheduled in the transaction that starts or resumes it, the next is chained
     * before the current one completes, and a failing or held batch keeps its row — so its absence is a lost
     * chain, which only a pause and resume repairs.
     */
    public List<OrphanedRun> orphanedBackfills() {
        return jdbcTemplate.query("""
                        SELECT r.id, r.pipeline_name FROM run_history r
                         WHERE r.state = ?
                           AND NOT EXISTS (SELECT 1 FROM scheduled_tasks t
                                            WHERE t.task_name = ? AND starts_with(t.task_instance, ? || r.id || '#'))
                         ORDER BY r.id LIMIT ?
                        """,
                (rs, i) -> new OrphanedRun(rs.getString(1), rs.getString(2)),
                RunState.RUNNING.dbValue(), BackfillTask.TASK_NAME, BackfillTask.TASK_NAME + "#", ORPHAN_LIMIT);
    }

    /** The picked execution that has been due the longest, if any is executing. */
    public Optional<Executing> longestExecuting() {
        List<Executing> rows = jdbcTemplate.query(
                "SELECT task_name, execution_time FROM scheduled_tasks WHERE picked ORDER BY execution_time LIMIT 1",
                (rs, i) -> new Executing(rs.getString(1), rs.getTimestamp(2).toInstant()));
        return rows.stream().findFirst();
    }
}
