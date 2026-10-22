package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DLQ listing pages by keyset in {@code (created_at DESC, id DESC)} order and carries the filter's counts on
 * every page.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DlqListingIntegrationTest {

    private static final String PIPELINE = "p-list";
    private static final Instant SAME_INSTANT = Instant.parse("2026-10-01T12:00:00.123456Z");

    @Autowired
    private DlqItemRepository dlqItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, dlq_item, run_history, group_state");
    }

    @Test
    void walksToExhaustionWithoutGapsOrDuplicatesWhenEveryItemSharesOneInstant() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(archive(PIPELINE, null, true, SAME_INSTANT).getId());
        }

        Page first = page(get("/v1/dlq").param("pipeline_name", PIPELINE).param("limit", "2"));
        Page second = page(get("/v1/dlq").param("pipeline_name", PIPELINE).param("limit", "2")
                .param("cursor", first.nextCursor()));
        Page third = page(get("/v1/dlq").param("pipeline_name", PIPELINE).param("limit", "2")
                .param("cursor", second.nextCursor()));

        assertThat(List.of(first.ids().size(), second.ids().size(), third.ids().size())).containsExactly(2, 2, 1);
        assertThat(List.of(first.hasMore(), second.hasMore(), third.hasMore())).containsExactly(true, true, false);
        assertThat(third.nextCursor()).isNull();
        List<Long> walked = new ArrayList<>();
        walked.addAll(first.ids());
        walked.addAll(second.ids());
        walked.addAll(third.ids());
        assertThat(walked).containsExactlyElementsOf(ids.reversed());
    }

    @Test
    void ordersNewestFirst() throws Exception {
        long older = archive(PIPELINE, null, true, SAME_INSTANT.minusSeconds(60)).getId();
        long newer = archive(PIPELINE, null, true, SAME_INSTANT).getId();

        assertThat(page(get("/v1/dlq").param("pipeline_name", PIPELINE)).ids()).containsExactly(newer, older);
    }

    @Test
    void anItemRemovedMidWalkIsOmittedAndNothingRepeats() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(archive(PIPELINE, null, true, SAME_INSTANT.minusSeconds(i)).getId());
        }

        Page first = page(get("/v1/dlq").param("pipeline_name", PIPELINE).param("limit", "2"));
        dlqItemRepository.deleteById(ids.get(2));
        Page second = page(get("/v1/dlq").param("pipeline_name", PIPELINE).param("limit", "2")
                .param("cursor", first.nextCursor()));

        assertThat(first.ids()).containsExactly(ids.get(0), ids.get(1));
        assertThat(second.ids()).containsExactly(ids.get(3));
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void countsFollowTheFiltersAndNotTheCursor() throws Exception {
        archive(PIPELINE, null, true, SAME_INSTANT);
        archive(PIPELINE, null, false, SAME_INSTANT.minusSeconds(1));
        archive(PIPELINE, "run-1", true, SAME_INSTANT.minusSeconds(2));
        archive("p-other", null, true, SAME_INSTANT);

        String cursor = null;
        int pages = 0;
        do {
            MockHttpServletRequestBuilder request = get("/v1/dlq").param("pipeline_name", PIPELINE).param("limit", "1");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            Page page = page(request);
            assertThat(List.of(page.total(), page.requeueableTotal())).containsExactly(3L, 2L);
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);

        mockMvc.perform(get("/v1/dlq").param("pipeline_name", PIPELINE).param("lane", "live"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.requeueable_total").value(1));
        mockMvc.perform(get("/v1/dlq").param("run_id", "run-1"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.requeueable_total").value(1));
        mockMvc.perform(get("/v1/dlq"))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.requeueable_total").value(3));
    }

    @Test
    void anEmptyListingHasNoNextPageAndZeroCounts() throws Exception {
        mockMvc.perform(get("/v1/dlq").param("pipeline_name", PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.requeueable_total").value(0));
    }

    @Test
    void anUndecodableCursorIsRefused() throws Exception {
        mockMvc.perform(get("/v1/dlq").param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_cursor"));
    }

    private DlqItemEntity archive(String pipelineName, String runId, boolean withPayload, Instant createdAt) {
        DlqItemEntity saved = dlqItemRepository.save(DlqItemEntity.builder()
                .pipelineName(pipelineName).pipelineGeneration(3L).stage("dial_call").error("exhausted")
                .runId(runId).payload(withPayload ? "{}" : null).build());
        jdbcTemplate.update("UPDATE dlq_item SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), saved.getId());
        return saved;
    }

    private Page page(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(body, "$.items[*].id");
        List<String> nextCursor = JsonPath.read(body, "$..next_cursor");
        return new Page(ids.stream().map(Number::longValue).toList(), JsonPath.read(body, "$.has_more"),
                nextCursor.isEmpty() ? null : nextCursor.getFirst(),
                JsonPath.<Number>read(body, "$.total").longValue(), JsonPath.<Number>read(body, "$.requeueable_total").longValue());
    }

    private record Page(List<Long> ids, boolean hasMore, String nextCursor, long total, long requeueableTotal) {
    }
}
