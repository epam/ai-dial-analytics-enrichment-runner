package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.exception.UnresolvedPlaceholderException;
import com.epam.aidial.enrichment.worker.group.GroupInputAssembler;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRendererTest {

    private final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();
    private final TemplateRenderer renderer = new TemplateRenderer(jsonMapper);

    @Test
    void escapesStringValueSoResultStaysValidJson() {
        String out = renderer.render("{\"content\":\"{{prompt}}\"}", Map.of("prompt", "he said \"hi\""));

        assertThat(parse(out)).containsEntry("content", "he said \"hi\"");
    }

    @Test
    void insertsNumberAsJsonLiteral() {
        String out = renderer.render("{\"n\":{{count}}}", Map.of("count", 42));

        assertThat(parse(out)).containsEntry("n", 42);
    }

    @Test
    void rendersNullAsEmpty() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("x", null);

        String out = renderer.render("{\"v\":\"{{x}}\"}", inputs);

        assertThat(out).isEqualTo("{\"v\":\"\"}");
    }

    /**
     * The guard the whole fold turns on. A template naming a variable nothing bound must fail here, before a
     * request is even assembled — rendering the placeholder through would ask the model a question with a
     * hole in it, get a well-formed answer, be charged for it, and store the answer as a label. Nothing
     * about that looks wrong afterwards, which is what makes it the most expensive failure available.
     */
    @Test
    void refusesToRenderPlaceholderNothingBound() {
        assertThatThrownBy(() -> renderer.render("{\"content\":\"{{prompt}} {{missing}}\"}",
                Map.of("prompt", "hello")))
                .isInstanceOf(UnresolvedPlaceholderException.class)
                .hasMessageContaining("missing")
                .hasMessageNotContaining("prompt");
    }

    /** Every offending name in one exception: a renamed variable typically breaks several at once. */
    @Test
    void namesEveryUnboundPlaceholderAtOnce() {
        assertThatThrownBy(() -> renderer.render("{{a}}{{b}}{{a}}", Map.of()))
                .isInstanceOf(UnresolvedPlaceholderException.class)
                .hasMessageContaining("a, b")
                .hasMessageContaining("2 variable(s)");
    }

    /**
     * A bound variable whose value is null is an absent optional the declaration asked for, not an unbound
     * name — so it renders as empty (pinned above) rather than failing the item.
     */
    @Test
    void boundNullIsNotAnUnresolvedPlaceholder() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("x", null);

        assertThat(renderer.render("{\"v\":\"{{x}}\"}", inputs)).isEqualTo("{\"v\":\"\"}");
    }

    /**
     * The check reads the <em>template</em>, not the rendered output. A row whose own payload contains
     * {@code {{…}}} is source content the runner passes through untouched, and scanning the output for
     * surviving braces would dead-letter a perfectly good row for its data's punctuation.
     */
    @Test
    void bracesInRowValueAreDataAndDoNotFailTheItem() {
        String out = renderer.render("{\"content\":\"{{prompt}}\"}",
                Map.of("prompt", "the template said {{unbound}}"));

        assertThat(parse(out))
                .containsEntry("content", "the template said {{unbound}}");
    }

    /**
     * The narrower case the test above does not reach, and the one that made "passed through untouched"
     * false: a value containing a placeholder for a variable that <em>is</em> bound. Substituting each
     * variable in turn over the accumulated result re-scanned text that was already data, so
     * {@code {{topic}}} inside the prompt was replaced as soon as {@code topic} came round in the map —
     * and whether it was depended on iteration order and on what else the declaration happened to bind.
     * One pass over the template leaves it as content, which is what it is.
     */
    @Test
    void boundValueContainingAnotherVariablesPlaceholderIsNotSubstituted() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("prompt", "summarise {{topic}} for me");
        inputs.put("topic", "billing");

        String out = renderer.render("{\"content\":\"{{prompt}}\",\"topic\":\"{{topic}}\"}", inputs);

        assertThat(parse(out))
                .containsEntry("content", "summarise {{topic}} for me")
                .containsEntry("topic", "billing");
    }

    /** And the same holds whichever order the map hands the variables over in. */
    @Test
    void theSubstitutionIsIndependentOfInputOrder() {
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("topic", "billing");
        reversed.put("prompt", "summarise {{topic}} for me");

        String out = renderer.render("{\"content\":\"{{prompt}}\"}", reversed);

        assertThat(parse(out))
                .containsEntry("content", "summarise {{topic}} for me");
    }

    /**
     * A substituted value that happens to look like a regex replacement is content too. Without
     * {@code Matcher.quoteReplacement} a {@code $1} or a trailing backslash in a row's data would either
     * corrupt the request or throw out of the renderer.
     */
    @Test
    void dollarAndBackslashInValuesAreContentNotSyntax() {
        String out = renderer.render("{\"content\":\"{{prompt}}\"}",
                Map.of("prompt", "cost $1 or $0, path C:\\tmp"));

        assertThat(parse(out))
                .containsEntry("content", "cost $1 or $0, path C:\\tmp");
    }

    /**
     * Named rather than an NPE. The admission gate refuses an {@code llm} transform declaring no
     * request_template, so this is unreachable from the feed — but the old behaviour here was to return
     * null (or throw an NPE, depending on whether anything was bound) and let the request builder fail to
     * parse it, which reached the DLQ as a Jackson error at {@code output_map}.
     */
    @Test
    void anAbsentTemplateIsNamedRatherThanNullPointing() {
        assertThatThrownBy(() -> renderer.render(null, Map.of("prompt", "hi")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request_template");
    }

    /**
     * The five member variables a group assembly supplies are bound on every group evaluation, so a template
     * interpolating them needs no exemption from the assertion — it finds them bound. Asserted against the
     * assembly's own inputs rather than a hand-written map, so a variable added there without being bound
     * fails here.
     */
    @Test
    void theGroupAssemblyOwnVariablesAreAlwaysBound() {
        Map<String, Object> inputs = new GroupInputAssembler.AssembledInput(
                List.of(Map.of("text", "hello")), false, 0, false).asInputs();

        String template = "{\"members\":\"{{members}}\",\"t\":{{members_truncated}},"
                + "\"i\":{{members_included}},\"o\":{{members_omitted}},\"n\":\"{{members_notice}}\"}";

        assertThat(renderer.render(template, inputs)).isNotBlank();
    }

    private Map<String, Object> parse(String json) {
        return jsonMapper.readValue(json, new TypeReference<>() { });
    }
}
