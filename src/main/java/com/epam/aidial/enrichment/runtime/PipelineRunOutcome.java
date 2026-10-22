package com.epam.aidial.enrichment.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How each pipeline's last live fire ended, for the runtime view.
 *
 * <p>Nothing in the database records whether a fire reached the end of its input or why it failed, so it is kept
 * here, in memory. The runner is a single replica, so the process that serves a read is the process that ran the
 * fire. A restart empties the map, and the view then reports these fields as unknown until each pipeline's next fire.
 *
 * <p>Written once per fire that runs, by the scan and Class-1 executors. A fire skipped by a gate is not a run and
 * records nothing. Backfill fires never write here. Each entry is an immutable record replaced through
 * {@link Map#compute}, so a concurrent read always sees one whole outcome. Each pipeline has one recurring execution,
 * so two fires of one pipeline never race.
 */
@Component
@RequiredArgsConstructor
public class PipelineRunOutcome {

    private final Clock clock;

    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();

    /**
     * One pipeline's last recorded fire.
     *
     * @param hasMore     whether its last page or window reported more input; true also after a drain that stopped
     *                    early (page budget, cursor stall, a gate tripping mid-drain). Null until a fire has completed
     * @param caughtUpAt  when a fire last ended with nothing more to read, or null if none has since startup
     * @param lastError   the classified failure of the last fire, or null when that fire succeeded
     * @param lastErrorAt when that failure happened, or null when the last fire succeeded
     */
    public record Outcome(Boolean hasMore, Instant caughtUpAt, String lastError, Instant lastErrorAt) {
    }

    /** Records a fire that completed: clears the error, and moves {@code caughtUpAt} when nothing is left to read. */
    public void recordSuccess(String pipelineName, boolean hasMore) {
        Instant now = clock.instant();
        outcomes.compute(pipelineName, (name, previous) -> new Outcome(hasMore,
                hasMore ? caughtUpAt(previous) : now, null, null));
    }

    /** Records a fire that failed, keeping what the last completed fire established about the input. */
    public void recordFailure(String pipelineName, String error) {
        Instant now = clock.instant();
        outcomes.compute(pipelineName, (name, previous) -> new Outcome(previous == null ? null : previous.hasMore(),
                caughtUpAt(previous), error, now));
    }

    /** The pipeline's last recorded fire, or empty when none has been recorded since startup. */
    public Optional<Outcome> get(String pipelineName) {
        return Optional.ofNullable(outcomes.get(pipelineName));
    }

    /**
     * Forgets every pipeline not in {@code pipelineNames}, bounding the map. Run on every sync rather than once when a
     * pipeline leaves: a fire still running at that moment records its outcome afterwards, and the next sync drops it.
     */
    public void retain(Set<String> pipelineNames) {
        outcomes.keySet().retainAll(pipelineNames);
    }

    /** Forgets one pipeline's outcome: a recreated pipeline has not fired yet, whatever its name's predecessor did. */
    public void forget(String pipelineName) {
        outcomes.remove(pipelineName);
    }

    private static Instant caughtUpAt(Outcome previous) {
        return previous == null ? null : previous.caughtUpAt();
    }
}
