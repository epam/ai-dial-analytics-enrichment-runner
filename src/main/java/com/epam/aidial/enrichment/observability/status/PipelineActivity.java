package com.epam.aidial.enrichment.observability.status;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * What each pipeline has written and dead-lettered, for the status heartbeat.
 *
 * <p>There is no write timestamp in the database to read "when did this pipeline last write?" from, so it is kept
 * here, in memory. The runner is a single replica, and a restart empties the map. {@link PipelineStatusReporter}
 * covers that with a startup grace and reports an unknown last write as {@code -} rather than guessing one.
 *
 * <p>Fed from three places: an upsert success, a Class-1 window that wrote rows, and every dead-letter write. Read by
 * the heartbeat, which drains the counts, and by the runtime view, which reads only the last write.
 */
@Component
@RequiredArgsConstructor
public class PipelineActivity {

    private final Clock clock;

    private final Map<String, Counters> activity = new ConcurrentHashMap<>();

    /**
     * What one pipeline did since the previous report.
     *
     * @param lastWrite the last instant it wrote rows, or {@code null} if it has not since startup
     * @param rowsSince rows written since the previous report
     * @param dlqSince  items dead-lettered since the previous report
     */
    public record Snapshot(Instant lastWrite, long rowsSince, long dlqSince) {
    }

    /** Records rows written to the pipeline's enrichment table. A write of zero rows is not a write. */
    public void recordWrite(String pipelineName, long rows) {
        if (pipelineName == null || rows <= 0) {
            return;
        }
        Counters counters = counters(pipelineName);
        counters.rows.add(rows);
        counters.lastWrite = clock.instant();
    }

    /** Records items dead-lettered for the pipeline. */
    public void recordDeadLetters(String pipelineName, long count) {
        if (pipelineName == null || count <= 0) {
            return;
        }
        counters(pipelineName).dlq.add(count);
    }

    /** The pipeline's activity since the previous call, resetting the counts. The last write instant is kept. */
    public Snapshot drain(String pipelineName) {
        Counters counters = activity.get(pipelineName);
        if (counters == null) {
            return new Snapshot(null, 0, 0);
        }
        return new Snapshot(counters.lastWrite, counters.rows.sumThenReset(), counters.dlq.sumThenReset());
    }

    /**
     * The last instant the pipeline wrote rows, or {@code null} if it has not since startup. Unlike {@link #drain} it
     * resets nothing, so a read of the runtime view never takes rows away from the heartbeat's next line.
     */
    public Instant lastWrite(String pipelineName) {
        Counters counters = activity.get(pipelineName);
        return counters == null ? null : counters.lastWrite;
    }

    /** Forgets every pipeline not in {@code pipelineNames}, so a removed pipeline does not stay in the map. */
    public void retain(Set<String> pipelineNames) {
        activity.keySet().retainAll(pipelineNames);
    }

    /** Forgets one pipeline: a recreated pipeline has written nothing yet, whatever its name's predecessor did. */
    public void forget(String pipelineName) {
        activity.remove(pipelineName);
    }

    private Counters counters(String pipelineName) {
        return activity.computeIfAbsent(pipelineName, name -> new Counters());
    }

    private static final class Counters {
        private final LongAdder rows = new LongAdder();
        private final LongAdder dlq = new LongAdder();
        private volatile Instant lastWrite;
    }
}
