package com.epam.aidial.enrichment.group;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which group keys the requeue endpoint can and cannot address, on a real Tomcat. Runs in {@code none} security mode
 * so a refusal shows its own status: under OIDC the refusal's error dispatch is unauthenticated and reads as a
 * {@code 401} (see {@link EncodedSlashGroupKeyIntegrationTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EncodedSlashFirewallIntegrationTest {

    private static final String PIPELINE = "g-firewall";

    @LocalServerPort
    private int port;

    @Autowired
    private PipelineCache pipelineCache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        pipelineCache.replaceAll(Map.of(PIPELINE, TestPipelines.group(PIPELINE, 1, "chat_id", "10m")));
    }

    /**
     * An encoded slash that climbs out of its segment is caught on the decoded request URI, which the stock firewall
     * never checks; a double-encoded slash leaves a literal {@code %}. Both are the firewall's {@code 400}, before
     * routing, so nothing is enqueued.
     */
    @ParameterizedTest
    @ValueSource(strings = {"x%2F..%2F..%2F..%2Fcache%2Frequeue", "x%2F..", "x%2F.%2Fy", ".%2Fx", "team%252Fa:b"})
    void anEncodedSlashCannotEscapeTheSegment(String segment) {
        assertThat(post(segment)).isEqualTo(400);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM scheduled_tasks", Long.class)).isZero();
    }

    /** Keys the firewall or Tomcat refuse however they are encoded; the spec lists them as the exceptions. */
    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "a/./b", "a/../b", "./a", "a/..", "a//b", "a%b", "a;b", "a\\b", "a\nb", "a\rb",
        "a b", "a b"})
    void keysOutsideTheAddressableSetAreRefused(String key) {
        mark(key);

        assertThat(post(UriUtils.encodePathSegment(key, StandardCharsets.UTF_8))).isEqualTo(400);
    }

    /** Edge cases that stay addressable: a slash at either end, a lone slash, dots that are not a whole segment. */
    @ParameterizedTest
    @ValueSource(strings = {"/a", "a/", "/", " ", "..a", "a.", "...", "a+b", "a?b", "a#b", "ключ/юникод"})
    void edgeKeysRemainAddressable(String key) {
        mark(key);

        assertThat(post(UriUtils.encodePathSegment(key, StandardCharsets.UTF_8))).isEqualTo(202);
    }

    private void mark(String groupKey) {
        jdbcTemplate.update("""
                INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at)
                VALUES (?, ?, ?, ?)
                """, PIPELINE, groupKey, 1L, Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")));
    }

    /** Pre-encoded and passed as a URI, so nothing re-encodes the '%' or decodes the slash on the way out. */
    private int post(String segment) {
        URI uri = URI.create("http://localhost:" + port + "/v1/pipelines/" + PIPELINE + "/groups/" + segment + "/requeue");
        return RestClient.create().post().uri(uri).exchange((req, res) -> res.getStatusCode().value());
    }
}
