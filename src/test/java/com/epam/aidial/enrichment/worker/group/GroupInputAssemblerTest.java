package com.epam.aidial.enrichment.worker.group;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ReadyWhenDto;
import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.adas.dto.VarDto;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.exception.InputMappingException;
import com.epam.aidial.enrichment.expression.JsonataEvaluator;
import com.epam.aidial.enrichment.scan.InputMapper;
import com.epam.aidial.enrichment.support.TestPipelines;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupInputAssemblerTest {

    private static final PipelineDto PIPELINE = groupPipeline(roleThenTextVars());

    private GroupInputAssembler assembler(int tokenBudget) {
        return new GroupInputAssembler(new InputMapper(new JsonataEvaluator()),
                new GroupProperties(500, 0, Duration.ofDays(7), Duration.ofMinutes(10), 200, tokenBudget, Duration.ofMinutes(1)));
    }

    @Test
    void eachMemberBecomesOneObjectWithItsFieldsTogether() {
        GroupInputAssembler.AssembledInput input = assembler(0).assemble(PIPELINE, List.of(
                member("user", "hi"), member("assistant", "hello"), member("user", "bye")), false);

        assertThat(input.truncated()).isFalse();
        assertThat(input.members()).containsExactly(
                Map.of("role", "user", "text", "hi"),
                Map.of("role", "assistant", "text", "hello"),
                Map.of("role", "user", "text", "bye"));
    }

    @Test
    void membersAreExposedUnderTheCollectionVariable() {
        GroupInputAssembler.AssembledInput input = assembler(0).assemble(PIPELINE, List.of(member("user", "hi")), false);

        assertThat(input.asInputs()).containsOnlyKeys(GroupInputAssembler.MEMBERS_VAR,
                GroupInputAssembler.TRUNCATED_VAR, GroupInputAssembler.INCLUDED_VAR,
                GroupInputAssembler.OMITTED_VAR, GroupInputAssembler.NOTICE_VAR);
        assertThat(input.asInputs().get(GroupInputAssembler.MEMBERS_VAR)).isEqualTo(input.members());
    }

    /**
     * A complete group's notice is empty, so a template interpolating it unconditionally says nothing — the
     * whole reason the sentence is composed here rather than in each transform's template.
     */
    @Test
    void completeInputCarriesNoNotice() {
        GroupInputAssembler.AssembledInput input = assembler(0)
                .assemble(PIPELINE, List.of(member("user", "hi"), member("assistant", "hello")), false);

        assertThat(input.asInputs())
                .containsEntry(GroupInputAssembler.TRUNCATED_VAR, false)
                .containsEntry(GroupInputAssembler.INCLUDED_VAR, 2)
                .containsEntry(GroupInputAssembler.OMITTED_VAR, 0)
                .containsEntry(GroupInputAssembler.NOTICE_VAR, "");
    }

    @Test
    void trimmedInputTellsTheModelWhatItIsMissing() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(member("user", "message-number-" + i));
        }

        GroupInputAssembler.AssembledInput input = assembler(10).assemble(PIPELINE, rows, false);

        int included = input.members().size();
        assertThat(input.omitted()).isEqualTo(6 - included);
        assertThat(input.asInputs())
                .containsEntry(GroupInputAssembler.TRUNCATED_VAR, true)
                .containsEntry(GroupInputAssembler.INCLUDED_VAR, included)
                .containsEntry(GroupInputAssembler.OMITTED_VAR, 6 - included);
        assertThat((String) input.asInputs().get(GroupInputAssembler.NOTICE_VAR))
                .contains("partial view")
                .contains(included + " most recent")
                .contains((6 - included) + " earlier item(s) were omitted");
    }

    /**
     * ADAS's caps trimmed the group, the runner's budget did not. The input is still partial and must still
     * say so — but with no count, because {@code group-rows} has no pagination and the remainder cannot be
     * known.
     */
    @Test
    void adasTruncationIsAnnouncedWithoutCount() {
        GroupInputAssembler.AssembledInput input = assembler(100_000)
                .assemble(PIPELINE, List.of(member("user", "hi")), true);

        assertThat(input.truncated()).isTrue();
        assertThat(input.omitted()).isZero();
        assertThat((String) input.asInputs().get(GroupInputAssembler.NOTICE_VAR))
                .contains("partial view")
                .contains("earlier items were omitted")
                .doesNotContain("0 earlier");
    }

    @Test
    void overBudgetDropsOldestFirstAndReportsTrimming() {
        // Each member costs roughly (keys + value chars)/4 tokens; a budget of 10 fits only the newest few.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(member("user", "message-number-" + i));
        }

        GroupInputAssembler.AssembledInput input = assembler(10).assemble(PIPELINE, rows, false);

        assertThat(input.truncated()).isTrue();
        assertThat(input.members()).isNotEmpty().hasSizeLessThan(6);
        // The tail is what survives: the newest member must still be present, the oldest must not.
        assertThat(input.members().getLast()).containsEntry("text", "message-number-5");
        assertThat(input.members()).noneMatch(m -> "message-number-0".equals(m.get("text")));
    }

    @Test
    void underBudgetIsNotTrimmed() {
        GroupInputAssembler.AssembledInput input = assembler(100_000)
                .assemble(PIPELINE, List.of(member("user", "hi"), member("assistant", "hello")), false);

        assertThat(input.truncated()).isFalse();
        assertThat(input.members()).hasSize(2);
    }

    @Test
    void singleOversizedMemberIsTruncatedRatherThanDropped() {
        // ADAS returns an oversized single member on purpose, so dropping it would label nothing.
        String huge = "x".repeat(10_000);

        GroupInputAssembler.AssembledInput input = assembler(20).assemble(PIPELINE, List.of(member("user", huge)), false);

        assertThat(input.truncated()).isTrue();
        assertThat(input.itemsCut()).isTrue();
        assertThat(input.members()).hasSize(1);
        String text = (String) input.members().getFirst().get("text");
        assertThat(text).isNotEmpty().hasSizeLessThan(huge.length());
    }

    /**
     * The direction is the whole point: these fields are payloads such as {@code assembled_response}, whose
     * newest content is at the end. A head-keeping cut would answer a question about how a conversation ended
     * from how it opened, and would look exactly like a complete short field.
     */
    @Test
    void anOversizedFieldKeepsItsEndNotItsBeginning() {
        String body = "OPENING" + "-".repeat(5_000) + "CLOSING";

        GroupInputAssembler.AssembledInput input = assembler(200).assemble(PIPELINE, List.of(member("user", body)), false);

        String text = (String) input.members().getFirst().get("text");
        assertThat(text).endsWith("CLOSING").doesNotContain("OPENING");
    }

    /** A cut field says so in the value: a fragment starting mid-payload otherwise reads as the whole field. */
    @Test
    void cutFieldIsMarkedInPlace() {
        GroupInputAssembler.AssembledInput input = assembler(200)
                .assemble(PIPELINE, List.of(member("user", "y".repeat(5_000))), false);

        assertThat((String) input.members().getFirst().get("text")).startsWith("…[earlier characters omitted]…");
    }

    /**
     * A short field must not reserve an allowance it cannot use. Splitting the budget evenly across the
     * fields would charge {@code role} ("user") the same share as the body and throw away most of the room
     * the body could have had.
     */
    @Test
    void theBudgetGoesToTheFieldThatNeedsIt() {
        int budget = 200;

        GroupInputAssembler.AssembledInput input = assembler(budget)
                .assemble(PIPELINE, List.of(member("user", "z".repeat(5_000))), false);

        Map<String, Object> only = input.members().getFirst();
        assertThat(only).containsEntry("role", "user");
        // Evenly split, the body would have got half the allowance; water-filling leaves it nearly all of it.
        assertThat((String) only.get("text")).hasSizeGreaterThan(budget * 4 * 3 / 4);
    }

    /**
     * The budget is a bound on what reaches the model, so it cannot be one only a {@code String} can trip: a
     * JSONata binding that returns the message array is exactly the case that used to pass through whole.
     */
    @Test
    void anOversizedNonStringFieldIsCutToo() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chat_id", "chat-a");
        row.put("event_kind", "user");
        // An object-typed column (or a JSONata binding returning one) arrives as a Map, not a String.
        row.put("request_body", Map.of("body", "q".repeat(20_000)));

        GroupInputAssembler.AssembledInput input = assembler(100).assemble(PIPELINE, List.of(row), false);

        assertThat(input.itemsCut()).isTrue();
        assertThat(String.valueOf(input.members().getFirst().get("text")).length())
                .isLessThanOrEqualTo(100 * 4);
    }

    /**
     * An oversized list keeps its newest elements rather than becoming text: the oldest go first, as members
     * do. The cut is marked as an <em>element</em> — a silently shortened array is exactly the
     * complete-looking fragment the text marker exists to prevent.
     */
    @Test
    void anOversizedListDropsItsLeadingElementsAndSaysSo() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chat_id", "chat-a");
        row.put("event_kind", "user");
        row.put("request_body", List.of("oldest-" + "a".repeat(400), "newest-" + "b".repeat(80)));
        PipelineDto listPipeline = groupPipeline(Map.of("text", new VarDto("request_body", null)));

        GroupInputAssembler.AssembledInput input = assembler(40).assemble(listPipeline, List.of(row), false);

        assertThat(input.itemsCut()).isTrue();
        List<?> kept = (List<?>) input.members().getFirst().get("text");
        assertThat(kept).hasSize(2);
        assertThat(kept.getFirst()).isEqualTo("…[earlier elements omitted]…");
        assertThat((String) kept.getLast()).startsWith("newest-");
    }

    /**
     * A one-element list that does not fit dropped no element — only characters — so it carries the text
     * marker its element was cut with and does not also claim that earlier elements were omitted.
     */
    @Test
    void singleElementListIsCutWithoutClaimingDroppedElements() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chat_id", "chat-a");
        row.put("event_kind", "user");
        row.put("request_body", List.of("only-" + "c".repeat(5_000)));
        PipelineDto listPipeline = groupPipeline(Map.of("text", new VarDto("request_body", null)));

        GroupInputAssembler.AssembledInput input = assembler(40).assemble(listPipeline, List.of(row), false);

        List<?> kept = (List<?>) input.members().getFirst().get("text");
        assertThat(kept).hasSize(1);
        assertThat((String) kept.getFirst())
                .startsWith("…[earlier characters omitted]…")
                .endsWith("ccc");
    }

    /**
     * The token estimate rounds up by a token, so a member can be over budget by the estimate and still fit
     * once the allowance is shared out. Nothing was removed, so nothing may be claimed: a caveat telling the
     * model its input was cut is as wrong as one hiding that it was.
     */
    @Test
    void memberTheAllowanceAccommodatesIsNotReportedCut() {
        // 20 tokens is 80 characters: the two keys (8) and "user" (4) leave exactly 68 for the body, which
        // the estimate's own +1 token still counts as over budget.
        String body = "x".repeat(68);

        GroupInputAssembler.AssembledInput input = assembler(20).assemble(PIPELINE, List.of(member("user", body)), false);

        assertThat(input.itemsCut()).isFalse();
        assertThat(input.truncated()).isFalse();
        assertThat(input.members()).containsExactly(Map.of("role", "user", "text", body));
    }

    /**
     * A member that was cut rather than dropped omits no earlier item, and the caveat must not invent one —
     * but it must disclose that the item it does carry is itself incomplete, which no member count can say.
     */
    @Test
    void cutMemberIsDisclosedWithoutClaimingOmissions() {
        GroupInputAssembler.AssembledInput input = assembler(20)
                .assemble(PIPELINE, List.of(member("user", "x".repeat(10_000))), false);

        assertThat(input.omitted()).isZero();
        assertThat((String) input.asInputs().get(GroupInputAssembler.NOTICE_VAR))
                .contains("partial view")
                .contains("1 most recent item(s) are shown")
                .contains("themselves cut")
                .doesNotContain("earlier item(s) were omitted")
                .doesNotContain("earlier items were omitted");
    }

    @Test
    void zeroBudgetDisablesRunnerTrimming() {
        List<Map<String, Object>> rows = List.of(member("user", "x".repeat(50_000)));

        GroupInputAssembler.AssembledInput input = assembler(0).assemble(PIPELINE, rows, false);

        assertThat(input.truncated()).isFalse();
        assertThat(input.members()).hasSize(1);
    }

    @Test
    void unmappableMemberFailsTheWholeGroup() {
        // A group is 1:0..1, so there is no "skip this member" outcome — the group dead-letters instead.
        PipelineDto jsonataPipeline = groupPipeline(Map.of("text", new VarDto(null, "$bad(")));

        assertThatThrownBy(() -> assembler(0).assemble(jsonataPipeline, List.of(member("user", "hi")), false))
                .isInstanceOf(InputMappingException.class);
    }

    @Test
    void emptyMemberListAssemblesToNothing() {
        GroupInputAssembler.AssembledInput input = assembler(100).assemble(PIPELINE, List.of(), false);

        assertThat(input.members()).isEmpty();
        assertThat(input.truncated()).isFalse();
    }

    /** An ordered {@code vars} map: declaration order is the order a member's bound fields are rendered. */
    private static Map<String, VarDto> roleThenTextVars() {
        Map<String, VarDto> vars = new LinkedHashMap<>();
        vars.put("role", new VarDto("event_kind", null));
        vars.put("text", new VarDto("request_body", null));
        return vars;
    }

    private static PipelineDto groupPipeline(Map<String, VarDto> vars) {
        return TestPipelines.builder("g1")
                .triggerKind(TriggerKind.GROUP)
                .groupBy("chat_id")
                .grainKey("chat_id")
                .readyWhen(new ReadyWhenDto(null, "10m", null, null))
                .transformInputs(vars)
                .build();
    }

    private static Map<String, Object> member(String kind, String body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chat_id", "chat-a");
        row.put("event_kind", kind);
        row.put("request_body", body);
        return row;
    }
}
