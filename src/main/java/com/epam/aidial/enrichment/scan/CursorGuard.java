package com.epam.aidial.enrichment.scan;

import org.apache.commons.lang3.StringUtils;

/**
 * The two decisions every cursor-driven lane makes about the {@code next_cursor} ADAS returns.
 *
 * <p>Shared rather than repeated per lane because getting either one wrong is invisible until it has already
 * cost a full re-scan: {@link #advance} guards the write, {@link #isStalled} guards the loop, and a lane that
 * has one without the other is worse than a lane with neither. Before this existed the scan, group and backfill
 * lanes each wrote {@code next_cursor} unconditionally and only the Class-1 lane guarded it.
 *
 * <p>A blank cursor is treated exactly like an absent one throughout. ADAS omits {@code next_cursor} on an
 * empty window, and a JSON {@code ""} is the same statement in a different shape — a distinction no caller
 * should have to remember.
 */
public final class CursorGuard {

    private CursorGuard() {
    }

    /**
     * The cursor to persist after a page.
     *
     * <p>An absent or blank {@code nextCursor} keeps what is already stored. Writing it through would set the
     * stored cursor to null, which is not "no progress" but <em>the source start</em> — so the next fire would
     * re-scan the pipeline's entire history. The caller's stall guard stops the drain loop, but by then the cursor
     * has already been committed, which is why this decision belongs at the write and not at the loop.
     *
     * @param stored     the cursor currently persisted for the pipeline or run
     * @param nextCursor what the scan/materialize response returned
     */
    public static String advance(String stored, String nextCursor) {
        return StringUtils.isBlank(nextCursor) ? stored : nextCursor;
    }

    /**
     * Whether the cursor failed to move, given that ADAS claims more backlog remains.
     *
     * <p>True for an absent, blank or unchanged cursor: in every one of those cases continuing would re-read
     * the same range for the rest of the per-fire budget rather than making progress.
     *
     * @param cursorBefore the cursor the page was read at
     * @param nextCursor   what the response returned
     */
    public static boolean isStalled(String cursorBefore, String nextCursor) {
        return StringUtils.isBlank(nextCursor) || nextCursor.equals(cursorBefore);
    }
}
