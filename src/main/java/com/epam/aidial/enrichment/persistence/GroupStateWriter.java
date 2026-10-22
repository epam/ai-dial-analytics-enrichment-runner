package com.epam.aidial.enrichment.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Set-based access to {@code group_state}: the dirty-mark's batch upsert, the readiness sweep's
 * dirty-and-ready-and-within-budget select, the runtime view's per-pipeline counts, the evaluated stamp, and
 * eviction.
 *
 * <p>The upsert takes {@code GREATEST} of the existing and incoming values rather than the latest write.
 * That makes replaying a page a genuine no-op (not merely harmless), and — more importantly — stops a
 * backfill re-driving an older window from moving a group <em>backwards</em> and re-firing it.
 *
 * <p>The sweep's selection is composed from fragments rather than written out as one constant per
 * combination: readiness is a disjunction of the branches the pipeline declares and the budget predicate is
 * present only for a pipeline that declares a ceiling, so the combinations multiply and hand-maintained
 * constants would drift. Every predicate is applied <em>inside</em> the query, before the {@code LIMIT} —
 * filtering afterwards would let ineligible groups consume the pass's batch and starve eligible ones,
 * since the ordering puts long-quiet groups first.
 */
@Component
@RequiredArgsConstructor
public class GroupStateWriter {

    private static final String UPSERT_MARK = """
            INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at, signalled)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (pipeline_name, group_key) DO UPDATE SET
                group_version = GREATEST(group_state.group_version, EXCLUDED.group_version),
                last_activity_at = GREATEST(group_state.last_activity_at, EXCLUDED.last_activity_at),
                signalled = group_state.signalled OR EXCLUDED.signalled
            """;

    /** Dirty: never evaluated, or changed since its last evaluation. */
    private static final String DIRTY = "(computed_version IS NULL OR group_version > computed_version)";

    private static final String SELECT_DIRTY = """
            SELECT group_key, group_version FROM group_state
            WHERE pipeline_name = ?
            """ + "  AND " + DIRTY + "\n";

    private static final String COUNT_DIRTY = """
            SELECT count(*) FROM group_state
            WHERE pipeline_name = ?
            """ + "  AND " + DIRTY + "\n";

    /** Ready by the idle window: the group has been quiet long enough. Always one of the branches. */
    private static final String READY_IDLE = "last_activity_at <= ?";

    /**
     * Ready by staleness: the label has aged past the pipeline's {@code max_staleness}. Requires a previous
     * computation, so a never-computed group can only become ready through the idle window or a signal —
     * otherwise a group would be evaluated the instant its first member landed.
     */
    private static final String READY_STALE = "(computed_at IS NOT NULL AND computed_at <= ?)";

    /**
     * Ready because a member signalled the group complete. No time bound: the whole point of a signal is to
     * evaluate as soon as the group is declared finished rather than waiting out the idle window.
     */
    private static final String READY_SIGNALLED = "signalled";

    /**
     * Within the pipeline's per-group daily ceiling. {@code IS DISTINCT FROM} rather than {@code <>} so a group
     * whose {@code evaluations_day} is null or belongs to a previous day passes on the day comparison alone,
     * without depending on the count having been reset.
     */
    private static final String WITHIN = "(evaluations_day IS DISTINCT FROM ? OR evaluations < ?)";

    private static final String WITHIN_BUDGET = "  AND " + WITHIN + "\n";

    /** The complement of {@link #WITHIN_BUDGET}, for counting what the ceiling declined. */
    private static final String OVER_BUDGET =
            "  AND evaluations_day IS NOT DISTINCT FROM ? AND evaluations >= ?\n";

    /**
     * The runtime view's counts in one pass over the pipeline's rows. {@code %1$s} is dirty, {@code %2$s} ready and
     * {@code %3$s} within the ceiling, so ready, ceiling-blocked and waiting-idle partition the dirty groups exactly as
     * the sweep's selection and {@link #countCeilingBlocked} split them.
     */
    private static final String COUNTS = """
            SELECT count(*) FILTER (WHERE %1$s),
                   count(*) FILTER (WHERE %1$s AND (%2$s) AND %3$s),
                   count(*) FILTER (WHERE %1$s AND (%2$s) AND NOT %3$s),
                   count(*) FILTER (WHERE %1$s AND NOT (%2$s))
              FROM group_state
             WHERE pipeline_name = ?
            """;

    private static final String OLDEST_FIRST = "ORDER BY last_activity_at\nLIMIT ?\n";

    /**
     * The stamp never moves the version <em>backwards</em>, but it does re-apply at the version already
     * stamped — hence {@code <=} rather than {@code <}. A re-evaluation of an unchanged group is an ordinary
     * event, not a race: {@code POST /v1/pipelines/{name}/groups/{key}/requeue} builds its item at the group's
     * current version, which for a clean group is exactly {@code computed_version}. Under {@code <} that
     * re-drive updated no row, so {@code computed_at} and {@code computed_truncated} kept describing the
     * <em>previous</em> evaluation while the enrichment table carried the new one — and since
     * {@code ready_when.max_staleness} is measured against {@code computed_at}, an operator re-drive did not
     * reset the staleness clock it should have. Only a strictly newer stamp still refuses an older one.
     */
    private static final String STAMP_COMPUTED = """
            UPDATE group_state
            SET computed_version = ?, computed_at = ?, computed_truncated = ?
            WHERE pipeline_name = ? AND group_key = ?
              AND (computed_version IS NULL OR computed_version <= ?)
            """;

    /**
     * Charges one evaluation against the group's daily budget. Deliberately <strong>not</strong> guarded by
     * the stamp's version comparison: two versions of one group can be in flight at once (the sweep enqueues
     * a new version while the previous item is still running), and if they complete out of order the older
     * one's stamp is correctly refused — but its model call was really spent, so refusing the charge with it
     * would silently under-count the ceiling it exists to enforce.
     *
     * <p>The {@code CASE} restarts the count when {@code evaluations_day} is null or belongs to a previous
     * day, which is what makes the daily reset free of a scheduled job.
     */
    private static final String CHARGE_EVALUATION = """
            UPDATE group_state
            SET evaluations_day = ?,
                evaluations = CASE WHEN evaluations_day = ? THEN evaluations + 1 ELSE 1 END
            WHERE pipeline_name = ? AND group_key = ?
            """;

    private static final String EVICT = """
            DELETE FROM group_state
            WHERE pipeline_name = ?
              AND last_activity_at < ?
              AND computed_version IS NOT NULL
              AND group_version <= computed_version
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Upserts a page's group marks, moving each group's version and activity forward only and OR-ing in
     * whether a member signalled the group complete.
     */
    public void upsertMarks(List<GroupStateEntity> marks) {
        if (marks.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(UPSERT_MARK, marks, marks.size(), (ps, mark) -> {
            ps.setString(1, mark.getPipelineName());
            ps.setString(2, mark.getGroupKey());
            ps.setLong(3, mark.getGroupVersion());
            ps.setTimestamp(4, Timestamp.from(mark.getLastActivityAt()));
            ps.setBoolean(5, mark.isSignalled());
        });
    }

    /**
     * The pipeline's groups that are dirty, ready and within budget, oldest activity first, at most
     * {@code limit}.
     *
     * @param today   the current UTC day the evaluation count is compared against
     * @param ceiling the pipeline's per-group daily ceiling; null means unlimited and drops the budget
     *                predicate entirely, so the common case runs the unmodified query
     */
    public List<DirtyGroup> findDirtyAndReady(String pipelineName, Readiness readiness, LocalDate today,
                                              Integer ceiling, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_DIRTY);
        List<Object> args = new ArrayList<>();
        args.add(pipelineName);
        appendReadiness(sql, args, readiness);
        if (ceiling != null) {
            sql.append(WITHIN_BUDGET);
            args.add(today);
            args.add(ceiling);
        }
        sql.append(OLDEST_FIRST);
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), GroupStateWriter::toDirtyGroup, args.toArray());
    }

    /**
     * How many of the pipeline's dirty, ready groups the ceiling declined — the signal that distinguishes a
     * budget doing its job from a pipeline that has stopped working, since both look like silence otherwise.
     *
     * <p>A separate query rather than a count folded into the selection above: the budget predicate keeps
     * declined groups out of that result set entirely, which is exactly what stops them consuming the pass's
     * batch. This runs only for a pipeline that declares a ceiling.
     */
    public int countCeilingBlocked(String pipelineName, Readiness readiness, LocalDate today, int ceiling) {
        StringBuilder sql = new StringBuilder(COUNT_DIRTY);
        List<Object> args = new ArrayList<>();
        args.add(pipelineName);
        appendReadiness(sql, args, readiness);
        sql.append(OVER_BUDGET);
        args.add(today);
        args.add(ceiling);
        Integer blocked = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
        return blocked == null ? 0 : blocked;
    }

    /**
     * One group pipeline's state, counted for the runtime view.
     *
     * @param pending        dirty groups: {@code ready + ceilingBlocked + waitingIdle}
     * @param ready          dirty, ready and within the ceiling: what the sweep would select, ignoring its batch size
     *                       and the groups whose work is already in flight
     * @param ceilingBlocked dirty and ready, but at the daily ceiling; 0 when no ceiling is declared
     * @param waitingIdle    dirty but not yet ready
     */
    public record GroupCounts(long pending, long ready, long ceilingBlocked, long waitingIdle) {
    }

    /**
     * Counts the pipeline's groups by readiness, with the same predicates the sweep selects with.
     *
     * @param ceiling the pipeline's per-group daily ceiling; null means unlimited, so no group is ceiling-blocked
     */
    public GroupCounts counts(String pipelineName, Readiness readiness, LocalDate today, Integer ceiling) {
        List<Object> readyArgs = new ArrayList<>();
        String readyPredicate = readinessPredicate(readyArgs, readiness);
        String within = ceiling == null ? "TRUE" : WITHIN;
        List<Object> withinArgs = ceiling == null ? List.of() : List.of(today, ceiling);

        List<Object> args = new ArrayList<>();
        args.addAll(readyArgs);
        args.addAll(withinArgs);
        args.addAll(readyArgs);
        args.addAll(withinArgs);
        args.addAll(readyArgs);
        args.add(pipelineName);
        return jdbcTemplate.queryForObject(COUNTS.formatted(DIRTY, readyPredicate, within), (rs, i) -> new GroupCounts(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), args.toArray());
    }

    /**
     * The pipeline's readiness declaration, resolved against the current clock reading.
     *
     * <p>One value rather than three parameters because both queries above ask the same question and would
     * otherwise drift apart, and because the branches are a disjunction: a group is ready when <em>any</em>
     * declared condition holds.
     *
     * @param idleBefore      groups whose last activity is at or before this are idle-ready. Always present:
     *                        a pipeline declaring no {@code idle} falls back to the configured default, which is
     *                        what keeps a group that never signals from staying dirty (and therefore
     *                        un-evictable) forever
     * @param stalenessBefore groups computed at or before this are staleness-ready; null when the pipeline
     *                        declares no {@code max_staleness}, which drops that branch entirely
     * @param signal          whether the pipeline declares a {@code ready_when.signal}, which adds a branch on
     *                        the group's recorded verdict. False drops it, so a pipeline with no signal runs the
     *                        unmodified query
     */
    public record Readiness(Instant idleBefore, Instant stalenessBefore, boolean signal) {
    }

    private static void appendReadiness(StringBuilder sql, List<Object> args, Readiness readiness) {
        sql.append("  AND ").append(readinessPredicate(args, readiness)).append("\n");
    }

    /** The disjunction of the declared readiness branches, binding their parameters into {@code args}. */
    private static String readinessPredicate(List<Object> args, Readiness readiness) {
        List<String> branches = new ArrayList<>(3);
        branches.add(READY_IDLE);
        args.add(Timestamp.from(readiness.idleBefore()));
        if (readiness.stalenessBefore() != null) {
            branches.add(READY_STALE);
            args.add(Timestamp.from(readiness.stalenessBefore()));
        }
        if (readiness.signal()) {
            branches.add(READY_SIGNALLED);
        }
        return "(" + String.join(" OR ", branches) + ")";
    }

    /**
     * Records that a group was evaluated at {@code computedVersion}, which is what makes it clean and stops
     * the readiness sweep re-enqueuing it. {@code truncated} records that the input the label came from had
     * been trimmed — the runner-side counterpart of the row's {@code truncated} provenance.
     *
     * <p>Does <strong>not</strong> charge the pipeline's cost ceiling: this is the stamp for outcomes that spent
     * no model call — an empty group, or an assembly ADAS refused. Charging those would let a misconfigured
     * pipeline exhaust a ceiling without ever producing a label.
     *
     * @return how many rows were stamped: 0 when a strictly newer evaluation already stamped the group, or
     *         when its state row has been evicted. Re-stamping at the version already recorded <em>does</em>
     *         stamp, refreshing {@code computed_at} and {@code truncated} for the evaluation that just ran
     */
    public int stampComputed(String pipelineName, String groupKey, long computedVersion, Instant computedAt,
                             boolean truncated) {
        return jdbcTemplate.update(STAMP_COMPUTED, computedVersion, Timestamp.from(computedAt), truncated,
                pipelineName, groupKey, computedVersion);
    }

    /**
     * Stamps a group and charges one evaluation against its pipeline's daily ceiling, for an evaluation that
     * reached the model — whether or not the response proved usable, since the call was spent either way.
     *
     * <p>The two are one transaction, so a group cannot be recorded as evaluated without also being charged
     * for it. They are <em>not</em> one condition: the stamp never moves backwards, while the charge always
     * applies, so an evaluation that completed after a newer one already stamped the group is still charged
     * for the call it spent. Charging first is deliberate — if the process dies between the statements the
     * group is charged but left dirty, which re-evaluates and over-charges rather than silently spending
     * outside the budget.
     *
     * @return whether the charge landed (false only when the group's state row is gone, evicted between the
     *         evaluation and the write) and whether the stamp applied
     */
    @Transactional
    public ChargeOutcome stampComputedAndCharge(String pipelineName, String groupKey, long computedVersion,
                                               Instant computedAt, boolean truncated, LocalDate today) {
        int charged = jdbcTemplate.update(CHARGE_EVALUATION, today, today, pipelineName, groupKey);
        int stamped = stampComputed(pipelineName, groupKey, computedVersion, computedAt, truncated);
        return new ChargeOutcome(charged > 0, stamped > 0);
    }

    /**
     * What {@link #stampComputedAndCharge} did.
     *
     * @param charged whether the group was charged for the evaluation
     * @param stamped whether this evaluation's stamp applied; false means a strictly newer evaluation of the
     *                same group got there first, so this one paid for a label that is already superseded —
     *                or the group's state row is gone
     */
    public record ChargeOutcome(boolean charged, boolean stamped) {

        /**
         * Whether a strictly newer evaluation of the same group had already stamped it.
         *
         * <p>Conditioned on {@code charged} because that is what tells the two zero-row outcomes apart: the
         * charge carries no version guard, so it lands iff the state row exists. Not stamped <em>and</em>
         * charged is the real race; neither means the row was evicted between the evaluation and the write,
         * where there is no newer label and so nothing was superseded.
         */
        public boolean superseded() {
            return charged && !stamped;
        }
    }

    /**
     * Deletes the pipeline's clean groups quiet since before {@code quietBefore}. A dirty group is never
     * evicted — that would discard pending work. Evicting a clean one is safe: a later member re-creates
     * the row with a version above the discarded {@code computed_version}, so it re-fires as a change.
     *
     * <p>The discarded row's {@code signalled} goes with it, so a re-created group is un-signalled until a
     * member signals again — deliberate, and the same reasoning as the discarded evaluation count: a group
     * quiet for longer than its whole readiness window plus the margin is a new conversation in all but
     * identity, and it still fires through the idle window.
     *
     * @return how many rows were evicted
     */
    public int evictQuiet(String pipelineName, Instant quietBefore) {
        return jdbcTemplate.update(EVICT, pipelineName, Timestamp.from(quietBefore));
    }

    /**
     * Deletes every group of a pipeline, dirty or not. Only for a pipeline recreated under the same name: its groups
     * belong to the pipeline that was deleted, and a computed one would otherwise read as already done.
     *
     * @return how many rows were deleted
     */
    public int deleteAll(String pipelineName) {
        return jdbcTemplate.update("DELETE FROM group_state WHERE pipeline_name = ?", pipelineName);
    }

    private static DirtyGroup toDirtyGroup(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DirtyGroup(rs.getString("group_key"), rs.getLong("group_version"));
    }

    /** A group the sweep should enqueue, with the version that identifies the work item. */
    public record DirtyGroup(String groupKey, long groupVersion) {
    }
}
