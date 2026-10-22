package com.epam.aidial.enrichment.worker.group;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.scan.InputMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a group's member rows into the transform's template input.
 *
 * <p>The pipeline's {@code transform.inputs} are applied to <strong>each</strong> member row — the same
 * {@link InputMapper} the row path uses — producing one object per member, and the collection is exposed to
 * the template under a single variable, {@value #MEMBERS_VAR}, which
 * {@link com.epam.aidial.enrichment.worker.TemplateRenderer} renders as a JSON array.
 *
 * <p>Per-member objects rather than parallel per-variable arrays: a member's bound fields stay together, so
 * a template can talk about "this member's role and its text" instead of relying on two arrays lining up by
 * index. That correlation is the whole point once members carry more than one field.
 *
 * <p>ADAS has already capped the group by rows and by response bytes; this applies the runner's token budget
 * on top. Members arrive oldest-first, so trimming drops from the <em>front</em>: the newest members are the
 * ones most likely to bear on a label about the group's current state. Per-member truncation is a last
 * resort, for the case ADAS deliberately allows — a single member larger than the whole byte cap — and it
 * cuts in the <em>same direction</em>: a field too long to fit keeps its <b>tail</b>, and says so where it
 * was cut. The fields this reaches are payload columns such as {@code assembled_response} and
 * {@code request_body}, whose newest content is at the end; keeping the head would answer a question about
 * how a conversation ended from how it opened.
 *
 * <p><strong>The assembly declares its own partiality to the model.</strong> A trimmed group looks exactly
 * like a complete one in the prompt, so a transform asked to summarize "the conversation" would describe a
 * suffix of it as the whole thing. The enrichment row's {@code truncated} provenance tells a later
 * <em>reader</em> that, but the judgement was already formed by then. The reserved variables below let the
 * template say so where it matters — before the model answers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupInputAssembler {

    /**
     * The template variable a group's member list is bound to. This is a runner-defined contract: ADAS fixes
     * no convention, and every group transform's {@code request_template} is written against this name.
     *
     * <p>This variable and the four below are supplied by <em>this</em> assembly rather than by
     * {@code transform.inputs}, and they are bound on every group evaluation — which is what keeps them
     * outside the render-time placeholder assertion without needing an exemption list there: a template
     * interpolating any of them finds it bound, because {@link AssembledInput#asInputs()} always carries all
     * five.
     */
    public static final String MEMBERS_VAR = "members";

    /** Whether the member list is a partial view of the group, from either side's trimming. */
    public static final String TRUNCATED_VAR = "members_truncated";

    /** How many members the prompt actually carries. */
    public static final String INCLUDED_VAR = "members_included";

    /** How many members the runner's budget dropped; 0 when only ADAS's caps trimmed (see below). */
    public static final String OMITTED_VAR = "members_omitted";

    /**
     * A ready-to-interpolate caveat, empty when the input is complete.
     *
     * <p>{@link com.epam.aidial.enrichment.worker.TemplateRenderer} substitutes and nothing more — it has no
     * conditionals — so a template cannot render a caveat only when one applies. A variable that is empty
     * when there is nothing to say is what makes a bare {@code {{members_notice}}} in the template correct in
     * both cases, which is why the sentence is composed here rather than left to each transform.
     */
    public static final String NOTICE_VAR = "members_notice";

    /** Rough characters-per-token ratio. A budget guard, not an accounting figure — no tokenizer needed. */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * What a cut text field opens with, so the cut is visible in the value and not only in
     * {@value #NOTICE_VAR}.
     *
     * <p>Without it the model receives a payload that begins mid-sentence — or mid-JSON — with nothing saying
     * why, and reads the fragment as the whole field. It leads rather than trails because the cut is at the
     * <em>start</em> of what survives, and it is spent from the field's own allowance, so marking a cut costs
     * content rather than budget — except when the allowance is smaller than the marker itself, where saying
     * the field was cut is worth more than the handful of characters it displaces (see {@link #keepTail}).
     */
    private static final String CUT_MARKER = "…[earlier characters omitted]… ";

    /**
     * The element a cut list opens with. A list keeps its shape when it is cut, so the cut is marked the same
     * way it happened: as an element, in the place the dropped ones held. Silently returning a shorter list
     * would hand the model a complete-looking array — the very reading {@link #CUT_MARKER} exists to prevent.
     */
    private static final String CUT_ELEMENTS_MARKER = "…[earlier elements omitted]…";

    /** What rendering one more list element costs beyond the element itself (the {@code ", "} between them). */
    private static final int SEPARATOR_CHARS = 2;

    private final InputMapper inputMapper;
    private final GroupProperties groupProperties;

    /**
     * @param members   the per-member input objects, oldest first
     * @param truncated whether the member list is partial — either the runner's budget trimmed it or ADAS's
     *                  caps did. The two are deliberately not distinguished: what a reader and a model both
     *                  need to know is that the judgement is partial, not whose limit made it so
     * @param omitted   how many whole members the runner's budget dropped. Zero when ADAS's caps did the
     *                  trimming, because {@code group-rows} has no pagination and the remainder is
     *                  genuinely unknowable — so this is a floor, and {@code truncated} is the fact
     * @param itemsCut  whether a member that <em>is</em> present had its own content cut. Distinct from
     *                  {@code omitted}: a member list can be complete while the one member it carries is
     *                  half a payload, and a caveat that only counts dropped members would present that
     *                  fragment as a whole item
     */
    public record AssembledInput(List<Map<String, Object>> members, boolean truncated, int omitted, boolean itemsCut) {

        /** The template inputs this assembly renders into. */
        public Map<String, Object> asInputs() {
            return Map.of(
                    MEMBERS_VAR, members,
                    TRUNCATED_VAR, truncated,
                    INCLUDED_VAR, members.size(),
                    OMITTED_VAR, omitted,
                    NOTICE_VAR, notice());
        }

        /** The same assembly, known partial because ADAS's caps trimmed it as well as (or instead of) us. */
        AssembledInput markTruncated() {
            return truncated ? this : new AssembledInput(members, true, omitted, itemsCut);
        }

        /**
         * The caveat, or an empty string. Phrased as a fact about the input rather than an instruction, so it
         * reads the same wherever a template places it: what the model does about a partial group is the
         * transform author's call.
         */
        private String notice() {
            if (!truncated) {
                return "";
            }
            StringBuilder notice = new StringBuilder("Note: this is a partial view of the group — only the ")
                    .append(members.size()).append(" most recent item(s) are shown");
            // Only claimed when something was actually dropped: a single oversized member is cut rather than
            // dropped, and telling the model that earlier items were omitted when none existed is a lie about
            // the input that it cannot check. With no count and nothing cut, ADAS's caps did the trimming and
            // the remainder is unknowable — the omission is a fact, its size is not.
            if (omitted > 0) {
                notice.append(" and ").append(omitted).append(" earlier item(s) were omitted");
            } else if (!itemsCut) {
                notice.append(" and earlier items were omitted");
            }
            notice.append('.');
            if (itemsCut) {
                notice.append(" The item(s) shown are themselves cut: a field too long to fit keeps only its"
                        + " most recent end, marked in place with \"").append(CUT_MARKER.strip()).append("\" in text or \"")
                        .append(CUT_ELEMENTS_MARKER).append("\" in a list.");
            }
            return notice.toString();
        }
    }

    /**
     * Maps every member row through the pipeline's input bindings and applies the token budget.
     *
     * @param fetchTruncated whether ADAS's own row/byte caps already trimmed the group. Folded in here rather
     *                       than by the caller so the template inputs and the row's provenance cannot end up
     *                       disagreeing about whether the judgement was partial
     * @throws com.epam.aidial.enrichment.exception.InputMappingException if a member cannot be mapped —
     *         a group is 1:0..1, so one unmappable member fails the whole group rather than being skipped
     */
    public AssembledInput assemble(PipelineDto pipeline, List<Map<String, Object>> memberRows, boolean fetchTruncated) {
        List<Map<String, Object>> mapped = new ArrayList<>(memberRows.size());
        for (Map<String, Object> row : memberRows) {
            mapped.add(inputMapper.map(pipeline, row));
        }
        AssembledInput budgeted = applyBudget(mapped);
        return fetchTruncated ? budgeted.markTruncated() : budgeted;
    }

    private AssembledInput applyBudget(List<Map<String, Object>> members) {
        int budget = groupProperties.inputTokenBudget();
        if (budget <= 0 || members.isEmpty()) {
            return new AssembledInput(members, false, 0, false);
        }

        long[] costs = new long[members.size()];
        long total = 0;
        for (int i = 0; i < members.size(); i++) {
            costs[i] = estimateTokens(members.get(i));
            total += costs[i];
        }
        if (total <= budget) {
            return new AssembledInput(members, false, 0, false);
        }

        // Drop oldest-first until the remainder fits.
        int start = 0;
        while (start < members.size() && total > budget) {
            total -= costs[start];
            start++;
        }
        if (start < members.size()) {
            log.info("Trimmed {} oldest member(s) of {} to fit the input budget", start, members.size());
            return new AssembledInput(new ArrayList<>(members.subList(start, members.size())), true, start,
                    false);
        }

        // Even the newest member alone exceeds the budget: keep it, cut down. ADAS returns an oversized
        // single member on purpose (so an empty group is never confused with "no members"), which makes
        // per-member cutting the only thing between that member and the model.
        int omitted = members.size() - 1;
        CutMember newest = cutToFit(members.getLast(), budget);
        if (newest.cut()) {
            log.info("Newest member of {} alone exceeds the input budget; cut its oversized field(s) to fit "
                    + "{} character(s) of content", members.size(), (long) budget * CHARS_PER_TOKEN);
        }
        // The estimate rounds up by a token, so being over it is not proof that anything had to go: when the
        // shared allowance turns out to accommodate every field, nothing was removed and neither the row nor
        // the caveat may claim a trimming that did not happen.
        return new AssembledInput(List.of(newest.member()), omitted > 0 || newest.cut(), omitted, newest.cut());
    }

    /** A member cut down to the budget, and whether anything was actually removed to get it there. */
    private record CutMember(Map<String, Object> member, boolean cut) {
    }

    /**
     * A member's field, measured once. {@link #renderedLength} on a multi-megabyte value is not free, and the
     * allocation below would otherwise re-measure the same value at every comparison the sort makes.
     */
    private record Field(String key, long length) {
    }

    /**
     * Cuts a single member's oversized fields down to the budget, keeping each field's <b>most recent</b>
     * content. Applies to <em>every</em> field, whatever its type: the budget is a bound on what reaches the
     * model, and a bound that only a {@code String} can trip is no bound at all — a pipeline binding a JSONata
     * expression that returns the message array would otherwise pass through at full size.
     *
     * <p>Fields keep their own order and their keys; only the values that do not fit are touched.
     */
    private static CutMember cutToFit(Map<String, Object> member, int budget) {
        // Keys are rendered whatever their values do, so they are spent before anything is shared out.
        long allowance = (long) budget * CHARS_PER_TOKEN;
        List<Field> fields = new ArrayList<>(member.size());
        for (Map.Entry<String, Object> entry : member.entrySet()) {
            allowance -= entry.getKey().length();
            fields.add(new Field(entry.getKey(), renderedLength(entry.getValue())));
        }
        Map<String, Integer> caps = allocate(fields, Math.max(0, allowance));
        if (caps.isEmpty()) {
            return new CutMember(member, false);
        }
        Map<String, Object> cut = new LinkedHashMap<>(member);
        caps.forEach((key, cap) -> cut.put(key, cutValue(member.get(key), cap)));
        return new CutMember(cut, true);
    }

    /**
     * Shares the character allowance between a member's fields and returns a cap for each field that has to
     * be cut; a field absent from the result fits as it is and is passed through untouched.
     *
     * <p>Shortest field first, re-dividing what is left at every step: a short field cannot reserve room it
     * has no use for, so the whole surplus lands on the payload that actually needs it. Dividing the
     * allowance evenly instead — the obvious reading of "share the budget between them" — charges a
     * {@code role} field of four characters the same allowance as an {@code assembled_response} of two
     * megabytes, which throws away most of the room the one oversized field could have used.
     */
    private static Map<String, Integer> allocate(List<Field> fields, long allowance) {
        List<Field> shortestFirst = new ArrayList<>(fields);
        shortestFirst.sort(Comparator.comparingLong(Field::length));
        Map<String, Integer> caps = new LinkedHashMap<>();
        long remaining = allowance;
        int fieldsLeft = shortestFirst.size();
        for (Field field : shortestFirst) {
            long share = remaining / fieldsLeft;
            if (field.length() <= share) {
                remaining -= field.length();    // its surplus rolls into the next field's share
            } else {
                caps.put(field.key(), (int) share);
                remaining -= share;
            }
            fieldsLeft--;
        }
        return caps;
    }

    /**
     * Cuts one value to {@code cap} characters, keeping its end.
     *
     * <p>A list is cut by dropping <em>leading</em> elements, which keeps it a list and matches how whole
     * members are dropped — the oldest message of a bound message array goes first. Anything else is cut as
     * text: a map or a JSON document sliced anywhere is no longer a map or a document, so there is nothing to
     * preserve by pretending otherwise, and the marker says what happened.
     */
    private static Object cutValue(Object value, int cap) {
        if (value instanceof List<?> items) {
            return cutList(items, cap);
        }
        return keepTail(String.valueOf(value), cap);
    }

    /**
     * Keeps the newest elements of {@code items} that fit beside the marker, dropping leading ones.
     *
     * <p>The marker takes an element's place rather than riding along for free: a list cut to exactly its cap
     * and the same list plus a notice are the same input to the model, and only one of them is inside the
     * budget.
     */
    private static Object cutList(List<?> items, int cap) {
        if (items.isEmpty()) {
            return items;
        }
        long marker = CUT_ELEMENTS_MARKER.length() + SEPARATOR_CHARS;
        List<Object> kept = new ArrayList<>();
        long length = marker;
        for (int i = items.size() - 1; i >= 0; i--) {
            long element = renderedLength(items.get(i)) + SEPARATOR_CHARS;
            if (length + element > cap) {
                break;
            }
            length += element;
            kept.add(items.get(i));
        }
        int dropped = items.size() - kept.size();
        if (dropped == 0) {
            return items;                       // it fits after all: there is no cut to mark
        }
        Collections.reverse(kept);
        if (kept.isEmpty()) {
            // Not even the newest element fits beside the marker, so that element is cut as text instead —
            // and a one-element list has then dropped no element, only characters, which its own marker says.
            long room = dropped > 1 ? cap - marker : cap - SEPARATOR_CHARS;
            kept.add(keepTail(String.valueOf(items.getLast()), (int) Math.max(0, room)));
            dropped--;
        }
        if (dropped > 0) {
            kept.addFirst(CUT_ELEMENTS_MARKER);
        }
        return kept;
    }

    /**
     * Keeps the last {@code cap} characters of {@code text}, marked. A cap too small for even the marker
     * yields the marker alone: the field then says it was cut rather than reading as an empty value, which
     * only a budget of a few tokens can produce.
     */
    private static String keepTail(String text, int cap) {
        if (text.length() <= cap) {
            return text;
        }
        int keep = cap - CUT_MARKER.length();
        if (keep <= 0) {
            return CUT_MARKER;
        }
        int start = text.length() - keep;
        // Never begin on the low half of a surrogate pair: the character would arrive at the model as a lone
        // surrogate, which is not text and does not survive JSON encoding intact.
        if (Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        return CUT_MARKER + text.substring(start);
    }

    /** A value's cost in characters, measured exactly as {@link #estimateTokens} counts it. */
    private static long renderedLength(Object value) {
        return value == null ? 0 : String.valueOf(value).length();
    }

    private static long estimateTokens(Map<String, Object> member) {
        long chars = 0;
        for (Map.Entry<String, Object> entry : member.entrySet()) {
            chars += entry.getKey().length() + renderedLength(entry.getValue());
        }
        return chars / CHARS_PER_TOKEN + 1;
    }
}
