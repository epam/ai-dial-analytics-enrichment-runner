package com.epam.aidial.enrichment.scan;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts pending db-scheduler executions with a cheap {@code COUNT(*)} over {@code scheduled_tasks},
 * instead of fetching and Jackson-deserializing every execution's payload just to count ids. This
 * realizes the instance-id prefix design (see {@link DeterministicId}): a pipeline's in-flight work is
 * counted by {@code task_instance LIKE 'pipelineName#%'} without touching {@code task_data}.
 */
@Component
@RequiredArgsConstructor
public class InflightCounter {

    private final JdbcTemplate jdbcTemplate;

    /** Total pending executions of a task. */
    public long countByTask(String taskName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class, taskName);
        return count != null ? count : 0L;
    }

    /**
     * Pending executions of any of the given tasks whose instance id starts with the prefix.
     *
     * <p>Takes a set of task names rather than one because a pipeline's work is spread across all of
     * {@link com.epam.aidial.enrichment.worker.WorkTasks#PIPELINE_SCOPED}: counting a single task name would
     * report a pipeline as idle while its writes or its group evaluations were backed up.
     */
    public long countByTasksAndInstancePrefix(Collection<String> taskNames, String instancePrefix) {
        if (taskNames.isEmpty()) {
            return 0L;
        }
        Long count = jdbcTemplate.queryForObject(prefixMatchSql("count(*)", taskNames.size()),
                Long.class, prefixMatchArgs(taskNames, instancePrefix));
        return count != null ? count : 0L;
    }

    /**
     * The instance ids of pending executions of any of the given tasks whose id starts with the prefix.
     *
     * <p>The identity counterpart to {@link #countByTasksAndInstancePrefix}: backpressure asks <em>how much</em>
     * work a pipeline has queued, while the readiness sweep asks <em>which</em> items are queued, so that a group
     * whose own work is already in flight is not evaluated a second time. Reading ids costs the same query —
     * still no {@code task_data} — and the result is bounded by the same {@code scan.max-inflight} that gates
     * the sweep, so the set cannot grow past the depth at which the sweep stops running at all.
     */
    public Set<String> instanceIdsByTasksAndPrefix(Collection<String> taskNames, String instancePrefix) {
        if (taskNames.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbcTemplate.queryForList(prefixMatchSql("task_instance", taskNames.size()),
                String.class, prefixMatchArgs(taskNames, instancePrefix)));
    }

    /**
     * Pending executions of any of the given tasks whose instance id starts with the prefix, per task name. The same
     * rows {@link #countByTasksAndInstancePrefix} counts, so the per-task counts add up to the backpressure depth.
     */
    public Map<String, Long> countsByTaskAndInstancePrefix(Collection<String> taskNames, String instancePrefix) {
        Map<String, Long> counts = new HashMap<>();
        if (taskNames.isEmpty()) {
            return counts;
        }
        jdbcTemplate.query(prefixMatchSql("task_name, count(*)", taskNames.size()) + " GROUP BY task_name",
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                }, prefixMatchArgs(taskNames, instancePrefix));
        return counts;
    }

    /**
     * Deletes a pipeline's pending, unpicked work: every execution of the given tasks whose id carries the pipeline's
     * prefix (requeued items included), plus every backfill item of any of its runs, which is namespaced by run rather
     * than by pipeline. A picked execution is running and is left to finish. Only for a pipeline recreated under the
     * same name, whose queued items belong to the pipeline that was deleted.
     *
     * @return how many executions were deleted
     */
    public int deletePendingForPipeline(Collection<String> taskNames, String pipelineName) {
        if (taskNames.isEmpty()) {
            return 0;
        }
        // The shared prefix filter, widened by the backfill items of the pipeline's runs and narrowed to unpicked rows.
        String sql = "DELETE FROM scheduled_tasks t WHERE " + taskNameIn(taskNames.size()) + " AND NOT t.picked"
                + " AND (task_instance LIKE ? ESCAPE '!'"
                + " OR EXISTS (SELECT 1 FROM run_history r WHERE r.pipeline_name = ?"
                + " AND t.task_instance LIKE ? || r.id || '#%' ESCAPE '!'))";
        List<Object> args = new ArrayList<>(List.of(prefixMatchArgs(taskNames, DeterministicId.pipelinePrefix(pipelineName))));
        args.add(pipelineName);
        args.add(escapeLike(DeterministicId.BACKFILL_NAMESPACE));
        return jdbcTemplate.update(sql, args.toArray());
    }

    /**
     * The one definition of "this pipeline's queued work", so the count, the id read and the recreation purge cannot
     * drift apart — a change to the task filter or to the prefix escaping has to move all of them at once, and a
     * pipeline reported idle by one but busy by the other is precisely the disagreement that would make the sweep's
     * suppression unsound.
     */
    private static String prefixMatchSql(String selectList, int taskCount) {
        return "SELECT " + selectList + " FROM scheduled_tasks WHERE " + taskNameIn(taskCount) + " AND task_instance LIKE ? ESCAPE '!'";
    }

    private static String taskNameIn(int taskCount) {
        return "task_name IN (" + String.join(", ", Collections.nCopies(taskCount, "?")) + ")";
    }

    /** The task names, then the escaped prefix, in the order {@link #prefixMatchSql} binds them. */
    private static Object[] prefixMatchArgs(Collection<String> taskNames, String instancePrefix) {
        List<Object> args = new ArrayList<>(taskNames);
        args.add(escapeLike(instancePrefix) + "%");
        return args.toArray();
    }

    /** Escapes LIKE wildcards so a pipeline name containing {@code % _ !} can't broaden the prefix match. */
    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
