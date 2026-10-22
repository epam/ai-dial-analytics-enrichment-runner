package com.epam.aidial.enrichment.worker;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A group's assembled member list is interpolated as a JSON array, which is what lets one prompt describe
 * many members. Scalar rendering must be untouched by that, since the row path depends on it.
 */
class TemplateRendererCollectionTest {

    private final TemplateRenderer renderer = new TemplateRenderer(JsonMapper.builder().build());

    @Test
    void memberListRendersAsAnEscapedJsonArray() {
        // Escaped, because a prompt interpolates the member list inside the template's content string.
        String rendered = renderer.render("{\"content\":\"Summarize: {{members}}\"}",
                Map.of("members", List.of(member("user", "hi"), member("assistant", "hello"))));

        assertThat(rendered).isEqualTo("{\"content\":\"Summarize: "
                + "[{\\\"role\\\":\\\"user\\\",\\\"text\\\":\\\"hi\\\"},"
                + "{\\\"role\\\":\\\"assistant\\\",\\\"text\\\":\\\"hello\\\"}]\"}");
    }

    @Test
    void renderedTemplateWithMemberListIsValidJson() {
        // The property that actually matters: whatever the members contain, the request body still parses.
        String rendered = renderer.render("{\"content\":\"Summarize: {{members}}\"}",
                Map.of("members", List.of(member("user", "he said \"hi\"\nthen left"))));

        assertThat(JsonMapper.builder().build().readValue(rendered, new TypeReference<Map<String, Object>>() { }))
                .extracting("content").asString().contains("he said");
    }

    @Test
    void emptyMemberListRendersAsAnEmptyArray() {
        assertThat(renderer.render("{{members}}", Map.of("members", List.of()))).isEqualTo("[]");
    }

    @Test
    void mapValuesAreEscapedLikeLists() {
        assertThat(renderer.render("{{m}}", Map.of("m", Map.of("k", "v"))))
                .isEqualTo("{\\\"k\\\":\\\"v\\\"}");
    }

    @Test
    void scalarRenderingIsUnaffected() {
        assertThat(renderer.render("{{a}}|{{b}}|{{c}}",
                Map.of("a", "text", "b", 42, "c", true))).isEqualTo("text|42|true");
    }

    private static Map<String, Object> member(String role, String text) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("role", role);
        member.put("text", text);
        return member;
    }
}
