package com.epam.aidial.enrichment.sync;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.exception.PipelineCacheColdException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory cache of the enabled pipelines synced from ADAS, keyed by pipeline name. It is the runner's local
 * source of truth for a pipeline's currently-served declaration — the worker's stale-declaration guard reads
 * its {@code generation} before upsert. Populated by {@link PipelineSyncService} on each poll.
 *
 * <p>The whole map is published via a single atomic reference swap, so every read observes a
 * consistent snapshot (never a half-applied reconcile) with no torn-view window.
 *
 * <p>The cache also answers whether it has <strong>ever</strong> been populated, which is a different
 * question from whether it holds a given pipeline and the one a work item has to ask first. An empty cache
 * at startup is not "every pipeline is disabled"; it is "no poll has happened yet" — the first registry
 * sync is a scheduled task behind the job scheduler, which begins picking due work as soon as the context
 * starts. See {@link #requirePopulated}.
 */
@Component
public class PipelineCache {

    private final AtomicReference<Map<String, PipelineDto>> pipelines = new AtomicReference<>(Map.of());

    /**
     * Whether a reconcile has ever run in this process. Set by {@link #replaceAll}, <strong>including a
     * reconcile to empty</strong>: an empty registry is a real answer about it, and withholding the flag for
     * that case would hold every lane forever against a legitimately empty feed.
     */
    private final AtomicBoolean populated = new AtomicBoolean();

    public PipelineDto get(String pipelineName) {
        return pipelines.get().get(pipelineName);
    }

    public Collection<PipelineDto> getAll() {
        return List.copyOf(pipelines.get().values());
    }

    public int size() {
        return pipelines.get().size();
    }

    /** Whether the registry has been synced at least once in this process. */
    public boolean isPopulated() {
        return populated.get();
    }

    /**
     * Refuses a work item that became due before this process had ever synced the registry, so the caller
     * holds it rather than reading its own ignorance as "the pipeline is disabled or deleted".
     *
     * <p>The distinction is load-bearing per lane: a chunk dropped here is irreversible, because the scan
     * cursor advanced in the same transaction that enqueued it; a group item dropped here costs a
     * re-derivation; an upsert archived here costs an operator a manual requeue of a result already paid
     * for. All three are answers to a question that was never asked.
     */
    public void requirePopulated(String what) {
        if (!populated.get()) {
            throw new PipelineCacheColdException(what);
        }
    }

    /** Replaces the cache contents with {@code desired} in one atomic swap, and marks the cache populated. */
    public void replaceAll(Map<String, PipelineDto> desired) {
        pipelines.set(Map.copyOf(desired));
        populated.set(true);
    }
}
