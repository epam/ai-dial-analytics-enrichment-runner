package com.epam.aidial.enrichment.group;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The group listing pages by keyset in {@code (last_activity_at, group_key)} order, oldest or newest first, and
 * carries the pipeline's group count on every page.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupListingIntegrationTest {

    private static final String PIPELINE = "g-list";
    private static final String URL = "/v1/pipelines/{name}/groups";
    private static final int PAGE_SIZE = 2;
    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00.123456Z");

    @Autowired
    private PipelineCache pipelineCache;

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
        registry.add("group.sweep-initial-delay", () -> "3600s");
        registry.add("group.sweep-interval", () -> "3600s");
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, group_state");
        pipelineCache.replaceAll(Map.of(
                PIPELINE, TestPipelines.group(PIPELINE, 1, "chat_id", "10m"),
                "g-other", TestPipelines.group("g-other", 1, "chat_id", "10m")));
    }

    @Test
    void walksOldestFirstToExhaustionWithoutGapsOrDuplicatesAcrossTies() throws Exception {
        mark(PIPELINE, "e", T0.plusSeconds(3));
        mark(PIPELINE, "c", T0.plusSeconds(1));
        mark(PIPELINE, "b", T0.plusSeconds(1));
        mark(PIPELINE, "a", T0);
        mark(PIPELINE, "d", T0.plusSeconds(2));
        mark("g-other", "z", T0);

        List<Page> pages = walk(null);

        assertThat(pages).extracting(p -> p.keys().size()).containsExactly(2, 2, 1);
        assertThat(pages).extracting(Page::hasMore).containsExactly(true, true, false);
        assertThat(pages).extracting(Page::total).containsOnly(5L);
        assertThat(pages.getLast().nextCursor()).isNull();
        assertThat(pages.stream().flatMap(p -> p.keys().stream())).containsExactly("a", "b", "c", "d", "e");
    }

    @Test
    void walksNewestFirstWhenAsked() throws Exception {
        mark(PIPELINE, "a", T0);
        mark(PIPELINE, "b", T0.plusSeconds(1));
        mark(PIPELINE, "c", T0.plusSeconds(1));
        mark(PIPELINE, "d", T0.plusSeconds(2));

        // limit=2 puts the tied c and b on either side of a page boundary, so the second page is read past c by the
        // descending keyset's group_key tie-break.
        List<Page> pages = walk("newest");

        assertThat(pages).extracting(Page::keys).containsExactly(List.of("d", "c"), List.of("b", "a"));
        assertThat(page(get(URL, PIPELINE).param("order", "NEWEST")).keys()).containsExactly("d", "c", "b", "a");
    }

    @Test
    void oldestFirstIsTheDefault() throws Exception {
        mark(PIPELINE, "new", T0.plusSeconds(60));
        mark(PIPELINE, "old", T0);

        assertThat(page(get(URL, PIPELINE)).keys()).containsExactly("old", "new");
        assertThat(page(get(URL, PIPELINE).param("order", "oldest")).keys()).containsExactly("old", "new");
        assertThat(page(get(URL, PIPELINE).param("order", " ")).keys()).containsExactly("old", "new");
    }

    @Test
    void cursorReplayedUnderTheOtherOrderIsRefused() throws Exception {
        mark(PIPELINE, "a", T0);
        mark(PIPELINE, "b", T0.plusSeconds(1));
        String cursor = page(get(URL, PIPELINE).param("limit", "1")).nextCursor();

        mockMvc.perform(get(URL, PIPELINE).param("order", "newest").param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_cursor"));
    }

    @Test
    void anUndecodableCursorIsRefused() throws Exception {
        mockMvc.perform(get(URL, PIPELINE).param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_cursor"));
    }

    @Test
    void anUnknownOrderIsRefused() throws Exception {
        mockMvc.perform(get(URL, PIPELINE).param("order", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("Parameter 'order' must be one of: oldest, newest"));
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "-1, 1", "2, 2", "999999, 3"})
    void limitIsClampedRatherThanRefused(String limit, int expectedSize) throws Exception {
        mark(PIPELINE, "a", T0);
        mark(PIPELINE, "b", T0.plusSeconds(1));
        mark(PIPELINE, "c", T0.plusSeconds(2));

        Page page = page(get(URL, PIPELINE).param("limit", limit));

        assertThat(page.keys()).hasSize(expectedSize);
        assertThat(page.hasMore()).isEqualTo(expectedSize < 3);
    }

    @Test
    void limitIsClampedToTheMaximum() throws Exception {
        for (int i = 0; i <= GroupListingService.MAX_LIMIT; i++) {
            mark(PIPELINE, "k%04d".formatted(i), T0);
        }

        Page page = page(get(URL, PIPELINE).param("limit", String.valueOf(Integer.MAX_VALUE)));

        assertThat(page.keys()).hasSize(GroupListingService.MAX_LIMIT);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.total()).isEqualTo(GroupListingService.MAX_LIMIT + 1L);
    }

    @Test
    void emptyListingHasNoNextPageAndZeroTotal() throws Exception {
        mockMvc.perform(get(URL, PIPELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(0))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist())
                .andExpect(jsonPath("$.total").value(0));
    }

    private void mark(String pipelineName, String groupKey, Instant lastActivityAt) {
        jdbcTemplate.update("""
                INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at)
                VALUES (?, ?, ?, ?)
                """, pipelineName, groupKey, 1L, Timestamp.from(lastActivityAt));
    }

    /** Walks every page at {@link #PAGE_SIZE} rows per page, following {@code next_cursor} to the end. */
    private List<Page> walk(String order) throws Exception {
        List<Page> pages = new ArrayList<>();
        String cursor = null;
        do {
            MockHttpServletRequestBuilder request = get(URL, PIPELINE).param("limit", String.valueOf(PAGE_SIZE));
            if (order != null) {
                request.param("order", order);
            }
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            Page page = page(request);
            pages.add(page);
            cursor = page.nextCursor();
        } while (cursor != null);
        return pages;
    }

    private Page page(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> nextCursor = JsonPath.read(body, "$..next_cursor");
        return new Page(JsonPath.read(body, "$.groups[*].group_key"), JsonPath.read(body, "$.has_more"),
                nextCursor.isEmpty() ? null : nextCursor.getFirst(), JsonPath.<Number>read(body, "$.total").longValue());
    }

    private record Page(List<String> keys, boolean hasMore, String nextCursor, long total) {
    }
}
