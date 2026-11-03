package com.epam.aidial.enrichment.pause;

import com.epam.aidial.enrichment.worker.HumanDuration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pipelines whose enqueue the DLQ circuit breaker has paused, with what each pause is and when it started.
 * An operator does not pause here: a deliberate stop is ADAS's {@code enabled} flag, which the runner follows as
 * the pipeline's single run control, so this registry holds only the runner's own inference. A paused pipeline's
 * scan fires, readiness sweeps and materialize ticks are skipped. Single active replica in phase-1, so in-memory
 * state suffices; a restart clears every pause, which the single-replica topology already assumes.
 *
 * <p><strong>A breaker pause expires</strong>, being an inference about an upstream (see {@link PauseOrigin}),
 * unless expiry is configured off. Expiry is evaluated lazily in {@link #current}, the single read path
 * every gate goes through, rather than by a scheduled reaper: the read is the only moment the answer
 * matters, so no stale entry is ever observable and the class needs no second scheduled component.
 *
 * <p>The recurring report of a pause is not here: a paused pipeline's status heartbeat line
 * ({@link com.epam.aidial.enrichment.observability.status.PipelineStatusReporter}) is logged at WARN with the
 * pause's origin, reason and age, so one condition is announced by one line on one clock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelinePauseRegistry {

    private final Clock clock;

    private final Map<String, Pause> paused = new ConcurrentHashMap<>();

    /**
     * One pipeline's pause.
     *
     * @param origin    who paused it — always the breaker
     * @param reason    what was recorded at the time, so the listing answers "why" without a log search
     * @param since     when the pause began
     * @param resumesAt when it lifts itself, or {@code null} for a pause that never expires
     */
    public record Pause(PauseOrigin origin, String reason, Instant since, Instant resumesAt) {
    }

    /** Pauses indefinitely: a breaker trip when expiry is disabled, held until lifted through {@link #resume}. */
    public void pause(String pipelineName, PauseOrigin origin, String reason) {
        pauseUntil(pipelineName, origin, reason, null);
    }

    /** Pauses until {@code resumesAt}, or indefinitely when it is null. */
    public void pauseUntil(String pipelineName, PauseOrigin origin, String reason, Instant resumesAt) {
        paused.put(pipelineName, new Pause(origin, reason, clock.instant(), resumesAt));
    }

    /**
     * Lifts the pipeline's breaker pause early, answering whether there was one to lift. An elapsed pause has
     * already lifted itself, so it is not "one to lift".
     */
    public boolean resume(String pipelineName) {
        return current(pipelineName).isPresent() && paused.remove(pipelineName) != null;
    }

    /** Whether the pipeline's enqueue is paused, lifting an auto-pause whose window has elapsed. */
    public boolean isPaused(String pipelineName) {
        return current(pipelineName).isPresent();
    }

    /**
     * The pipeline's pause, if it is paused — lifting an auto-pause whose window has elapsed first, and for this
     * key only.
     *
     * <p>The read a caller needs when it must know <em>who</em> paused the pipeline as well as whether it is: one
     * evaluation answers both, where {@link #isPaused} followed by {@link #paused()} copies the whole map and can
     * disagree with itself if the pause expires between the two.
     *
     * <p>{@code computeIfPresent} rather than a read-then-remove so that two gates asking at once cannot
     * both log the resume, and so the lift is atomic with the read that observes it.
     */
    public Optional<Pause> current(String pipelineName) {
        return Optional.ofNullable(paused.computeIfPresent(pipelineName, (name, current) -> {
            if (current.resumesAt() == null || clock.instant().isBefore(current.resumesAt())) {
                return current;
            }
            log.warn("Auto-resuming pipeline {}: its circuit-breaker pause of {} has elapsed ({})",
                    name, HumanDuration.format(Duration.between(current.since(), current.resumesAt())),
                    current.reason());
            return null;
        }));
    }

    /** Every pause, expired ones lifted first, so a listing never reports a pause that a gate would not. */
    public Map<String, Pause> paused() {
        paused.keySet().forEach(this::isPaused);
        return Map.copyOf(paused);
    }

    /**
     * How many pipelines are paused, without the defensive copy {@link #paused()} makes. Read by a gauge on
     * every scrape, which is the whole reason it does not go through the listing. Expired entries are not
     * lifted here: a gauge must not have side effects, and the next gate read lifts them anyway.
     */
    public int pausedCount() {
        return paused.size();
    }

    /**
     * How many of those the breaker paused — every one of them, now that an operator stops a pipeline in ADAS. Kept
     * as its own series for the alerts written against it: a breaker pause has no legitimate steady state, which is
     * what makes "one of these has persisted" expressible.
     */
    public int autoPausedCount() {
        return pausedCount();
    }
}
