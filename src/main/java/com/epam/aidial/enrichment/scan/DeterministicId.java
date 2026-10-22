package com.epam.aidial.enrichment.scan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives the deterministic db-scheduler instance id for an enrich-chunk work item. The id depends
 * only on values the runner holds — the pipeline name, the pipeline {@code generation}, the (opaque) cursor
 * the page was scanned at, and the chunk index — so a re-scan of the same range produces the same ids and
 * {@code scheduleIfNotExists} dedups the residual overlap window (e.g. the live path re-enqueuing a
 * range after a crash before the cursor advanced). The atomic cursor transaction is the primary
 * exactly-once mechanism; these ids are the belt to that suspenders. Live and backfill chunk ids are
 * namespaced separately (see {@link #backfillChunkInstanceId}), so a live/backfill range overlap is
 * <em>not</em> deduped here — it collapses on the enrichment table's ReplacingMergeTree instead.
 *
 * <p>The pipeline name is kept as a plain prefix so backpressure can count a pipeline's in-flight chunks by
 * instance-id prefix without deserializing payloads.
 *
 * <p><strong>The version component is the pipeline's {@code generation} since the evaluator fold, and that
 * widens what makes an id change.</strong> An evaluator version moved only when a prompt or an expression
 * did; {@code generation} moves for <em>any</em> declaration edit — {@code filter}, {@code trigger},
 * {@code cadence}, {@code rate_rpm}, {@code batch_scan_limit}. So the residual-overlap dedup these ids
 * provide no longer spans such an edit: work in flight when a knob changes is re-keyed, exactly as it would
 * be by a re-identification. The primary exactly-once mechanism — the atomic cursor transaction, and the
 * enrichment target's ReplacingMergeTree — is unaffected; the belt is briefly loosened, the suspenders are
 * not. It is typed {@code long} for the same reason ADAS types the column that way: a {@code generation} is
 * an unbounded counter, and an {@code int} here would silently truncate it.
 */
public final class DeterministicId {

    /**
     * Namespace of an id that belongs to no pipeline's in-flight count. It opens with the delimiter, which
     * ADAS's name grammar forbids at any position and certainly at the first, so no {@link #pipelinePrefix}
     * can ever match it.
     */
    static final String BACKFILL_NAMESPACE = "#bf#";

    /**
     * Marks a requeued item's id. A requeued item that came from a backfill run carries the run after it, in the
     * same {@link #BACKFILL_NAMESPACE} form, so a requeued run item that fails again is archived under its run.
     */
    private static final String REQUEUE_MARKER = "#requeue";

    private DeterministicId() {
    }

    public static String chunkInstanceId(String pipelineName, long pipelineGeneration, String cursorBefore, int chunkIndex) {
        String basis = pipelineName + "|" + pipelineGeneration + "|" + (cursorBefore == null ? "" : cursorBefore);
        return pipelineName + "#" + sha1Hex(basis) + "#c" + chunkIndex;
    }

    /**
     * Instance id for a backfill chunk, namespaced by the run id so a backfill from the source start
     * never collides with a live chunk enqueued at the same (null) cursor.
     *
     * <p>The namespace <em>leads</em> with the delimiter, and that is load-bearing rather than decorative.
     * A backfill chunk rides the same {@code enrich-chunk} task as a live one but is deliberately outside
     * any pipeline's in-flight count, so its id must not be reachable from {@link #pipelinePrefix}. Since a
     * pipeline name must begin with a lower-case letter, no name can produce a prefix that matches an id
     * beginning with {@code #} — whereas a plain {@code bf#} namespace would have been the whole in-flight
     * scan of a pipeline named {@code bf}, silently backpressuring it on every other pipeline's backfill.
     */
    public static String backfillChunkInstanceId(String runId, long pipelineGeneration, String cursorBefore, int chunkIndex) {
        String basis = runId + "|" + pipelineGeneration + "|" + (cursorBefore == null ? "" : cursorBefore);
        return BACKFILL_NAMESPACE + runId + "#" + sha1Hex(basis) + "#c" + chunkIndex;
    }

    /**
     * Instance id for a group work item, keyed by the group and the version it was selected at. Re-sweeping
     * an unchanged group yields the same id, so {@code scheduleIfNotExists} suppresses it; a new member
     * raises the group's version and therefore produces a distinct id.
     *
     * <p>The pipeline-name prefix is retained so a group pipeline's in-flight depth is still countable by
     * instance-id prefix, the way backpressure counts a row pipeline's chunks.
     */
    public static String groupInstanceId(String pipelineName, long pipelineGeneration, String groupKey, long groupVersion) {
        String basis = pipelineName + "|" + pipelineGeneration + "|" + groupKey + "|" + groupVersion;
        return pipelineName + "#g#" + sha1Hex(basis);
    }

    /**
     * Instance id for the write phase of a compute item, derived from that item's id. A compute that runs twice
     * (a retry after a transient failure before the enqueue committed) therefore produces the same write id, and
     * {@code scheduleIfNotExists} suppresses the duplicate — so one computed result is enqueued for writing at
     * most once.
     *
     * <p>Keeps the pipeline-name prefix so the write counts toward the pipeline's in-flight backpressure.
     */
    public static String upsertInstanceId(String computeInstanceId) {
        return computeInstanceId + "#w";
    }

    /**
     * Instance id for a requeued dead letter: {@code <pipeline>#requeue#<itemId>} for a live item, and
     * {@code <pipeline>#requeue#bf#<runId>#<itemId>} for a backfill run's. The pipeline prefix is kept either way,
     * so a requeue counts toward the pipeline's in-flight backpressure; the run is carried so that a requeued item
     * which fails again still names it rather than drifting into the live lane. Parsed by
     * {@link #backfillRunIdOf}, so the format and its parse live in one place.
     */
    public static String requeueInstanceId(String pipelineName, long itemId, String runId) {
        String prefix = pipelineName + REQUEUE_MARKER;
        return runId == null
                ? prefix + "#" + itemId
                : prefix + BACKFILL_NAMESPACE + runId + "#" + itemId;
    }

    /**
     * The backfill run a work item belongs to, recovered from its instance id; null for the live path.
     *
     * <p>Recognises a backfill chunk ({@code #bf#<runId>#…#c<n>}), its write phase (the same, suffixed
     * {@code #w}), and a requeued run item ({@code <pipeline>#requeue#bf#<runId>#<id>}, and its write phase).
     * Run ids are UUIDs and a pipeline name cannot contain {@code #}, so the run is the segment after the
     * namespace. Read from the id rather than the payload so that no queued payload has to change shape — and a
     * work item enqueued before run attribution existed is still attributed.
     */
    public static String backfillRunIdOf(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        int namespace;
        if (instanceId.startsWith(BACKFILL_NAMESPACE)) {
            namespace = 0;
        } else {
            namespace = instanceId.indexOf(REQUEUE_MARKER + BACKFILL_NAMESPACE);
            if (namespace < 0) {
                return null;
            }
            namespace += REQUEUE_MARKER.length();
        }
        int start = namespace + BACKFILL_NAMESPACE.length();
        int end = instanceId.indexOf('#', start);
        return end > start ? instanceId.substring(start, end) : null;
    }

    /** The instance-id prefix identifying every chunk belonging to a pipeline. */
    public static String pipelinePrefix(String pipelineName) {
        return pipelineName + "#";
    }

    /**
     * SHA-1 of a UTF-8 string, hex-encoded — the digest every deterministic instance id in the runner is
     * built on. Exposed because {@code BackfillTask} derives its own batch ids the same way, and two copies
     * of it would be free to drift into ids that no longer dedup against each other.
     */
    public static String sha1Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
