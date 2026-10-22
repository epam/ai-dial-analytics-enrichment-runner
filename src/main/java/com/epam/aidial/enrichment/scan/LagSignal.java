package com.epam.aidial.enrichment.scan;

/**
 * How a cursor-driven lane turns an ADAS response's {@code lag_seconds} into the lag it reports and persists.
 *
 * <p>The sibling of {@link CursorGuard}, for the same reason: ADAS answers an <em>empty</em> page or window with
 * {@code lag_seconds} absent, because there is no window end version to subtract from now. Read literally that
 * is "no value", and skipping it leaves the last non-empty page's lag latched — so a pipeline that fell a week
 * behind and then caught up keeps reporting a week of lag until the process restarts. That is the opposite of
 * what the signal exists for, and it matters most on the Class-1 lane, whose only stuck-pipeline signals are lag,
 * tick failures and cursor stalls.
 *
 * <p>An empty page therefore is not an absence of information: it says nothing older than the safety lag
 * remains, which is precisely <b>zero lag</b>. Absence only means "unknown" when the page was <em>not</em>
 * empty — a response that carried rows but omitted the field — and unknown must leave what is already recorded
 * alone rather than overwrite it with a fabricated zero.
 */
public final class LagSignal {

    /** The lag of a lane with nothing left to read. */
    public static final long CAUGHT_UP = 0L;

    private LagSignal() {
    }

    /**
     * The lag a page or window actually establishes.
     *
     * <p>A reported value always wins, so this never second-guesses ADAS; the empty-page pipeline only fills in the
     * value ADAS cannot compute.
     *
     * @param empty    whether the page/window came back with nothing in it
     * @param reported {@code lag_seconds} as returned, which is absent for an empty page
     * @return the lag to record, or null when it is genuinely unknown
     */
    public static Long resolve(boolean empty, Long reported) {
        if (reported != null) {
            return reported;
        }
        return empty ? CAUGHT_UP : null;
    }

    /**
     * The lag to persist on the pipeline's cursor row: the resolved value, or what is already stored when the
     * response established nothing. Guarded like {@link CursorGuard#advance} so the persisted column and the
     * exported gauge cannot disagree — one of them saying "unknown" while the other reports a stale number is
     * how an operator ends up trusting the wrong one.
     *
     * @param stored   the lag currently persisted for the pipeline
     * @param resolved the outcome of {@link #resolve}
     */
    public static Long orKeep(Long stored, Long resolved) {
        return resolved != null ? resolved : stored;
    }
}
