package com.epam.aidial.enrichment.worker;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MDC keys the worker paths pin, shared so the log correlation and the DIAL span attributes cannot drift
 * apart. Unbounded-cardinality identifiers (a grain key, a group key) live here and on spans — never as
 * metric tags.
 */
public final class WorkerMdc {

    public static final String PIPELINE_NAME = "pipeline_name";
    public static final String CHUNK_ID = "chunk_id";
    public static final String PIPELINE_GENERATION = "pipeline_generation";
    public static final String GRAIN_KEY = "grain_key";
    public static final String GROUP_KEY = "group_key";
    /** The db-scheduler task name of the execution in progress, set for its whole length. */
    public static final String TASK = "task";
    /** The db-scheduler task instance id of the execution in progress. */
    public static final String TASK_INSTANCE = "task_instance";
    /** The backfill run the execution in progress belongs to; absent on the live path. */
    public static final String RUN_ID = "run_id";

    private WorkerMdc() {
    }

    /** Opens a scope that restores every key it puts to its prior value when closed. */
    public static Scope scope() {
        return new Scope();
    }

    /**
     * A set of MDC keys put for one stretch of code and restored — not removed — when it ends.
     *
     * <p>Restoring rather than removing is the point: a db-scheduler execution's {@code pipeline_name} is set by
     * the scheduler listener for the whole execution, including the failure handling that runs after the
     * handler returns, and code running inside it must not wipe what it did not set.
     */
    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous = new LinkedHashMap<>();

        private Scope() {
        }

        /** Puts {@code key}, remembering the value it replaces the first time the key is put in this scope. */
        public Scope put(String key, String value) {
            if (!previous.containsKey(key)) {
                previous.put(key, MDC.get(key));
            }
            MDC.put(key, value);
            return this;
        }

        @Override
        public void close() {
            previous.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }
}
