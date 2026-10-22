package com.epam.aidial.enrichment.group;

import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.scan.DeterministicId;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.web.security.JwtTestTokens;
import com.epam.aidial.enrichment.web.security.TestSecurityConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A group key containing {@code /} is addressable when the slash is sent as {@code %2F}. Needs a real Tomcat: MockMvc
 * bypasses the connector, which is one of the two layers that refuse an encoded slash by default. Runs under OIDC so
 * the authorization rules are shown to still match such a path, not merely the firewall to let it through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
class EncodedSlashGroupKeyIntegrationTest {

    private static final String PIPELINE = "g-slash";
    private static final String GROUP_KEY = "team/a:b";
    private static final long GROUP_VERSION = 7L;
    private static final String FULL_ADMIN_TOKEN = JwtTestTokens.tokenWithRoles("admin");

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
        TestSecurityConfig.registerOidcProvider(registry);
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, group_state");
        pipelineCache.replaceAll(Map.of(PIPELINE, TestPipelines.group(PIPELINE, 1, "chat_id", "10m")));
        jdbcTemplate.update("""
                INSERT INTO group_state (pipeline_name, group_key, group_version, last_activity_at)
                VALUES (?, ?, ?, ?)
                """, PIPELINE, GROUP_KEY, GROUP_VERSION, Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")));
    }

    @Test
    void listedSlashKeyIsRequeuedThroughAnEncodedSlash() {
        RestClient client = RestClient.create();
        String body = client.get().uri(uri("/v1/pipelines/" + PIPELINE + "/groups"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_ADMIN_TOKEN)
                .retrieve().body(String.class);
        List<String> keys = JsonPath.read(body, "$.groups[*].group_key");
        assertThat(keys).containsExactly(GROUP_KEY);

        // Pre-encoded and passed as a URI, so nothing re-encodes the '%' or decodes the slash on the way out.
        URI requeue = uri("/v1/pipelines/" + PIPELINE + "/groups/team%2Fa:b/requeue");
        assertThat(post(client, requeue, FULL_ADMIN_TOKEN).value()).isEqualTo(202);

        String instanceId = DeterministicId.groupInstanceId(PIPELINE, 1, GROUP_KEY, GROUP_VERSION);
        Long tasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_instance = ?", Long.class, instanceId);
        assertThat(tasks).isEqualTo(1L);
    }

    @Test
    void anEncodedSlashPathIsStillGatedByAuthorization() {
        URI requeue = uri("/v1/pipelines/" + PIPELINE + "/groups/team%2Fa:b/requeue");

        assertThat(post(RestClient.create(), requeue, null).value()).isEqualTo(401);
    }

    @Test
    void anUnknownSlashKeyIsNotFoundRatherThanRefused() {
        URI requeue = uri("/v1/pipelines/" + PIPELINE + "/groups/team%2Fnope/requeue");

        assertThat(post(RestClient.create(), requeue, FULL_ADMIN_TOKEN).value()).isEqualTo(404);
    }

    /**
     * An encoded slash that would climb out of the segment, or a double-encoded one, is refused by the firewall before
     * routing, and nothing is enqueued — even with a valid token. The firewall answers {@code 400}, but in OIDC mode
     * that answer is an error dispatch to {@code /error}, which carries no credential and so reads as {@code 401}.
     * {@link EncodedSlashFirewallIntegrationTest} shows the {@code 400} itself. Either way it is not the {@code 404} an
     * unknown key gets, which is what a routed traversal would answer.
     */
    @ParameterizedTest
    @ValueSource(strings = {"x%2F..%2F..%2F..%2Fcache%2Frequeue", "team%252Fa:b"})
    void anEncodedSlashCannotEscapeTheSegment(String segment) {
        URI requeue = uri("/v1/pipelines/" + PIPELINE + "/groups/" + segment + "/requeue");

        assertThat(post(RestClient.create(), requeue, FULL_ADMIN_TOKEN).value()).isEqualTo(401);
        Long tasks = jdbcTemplate.queryForObject("SELECT count(*) FROM scheduled_tasks", Long.class);
        assertThat(tasks).isZero();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpStatusCode post(RestClient client, URI uri, String token) {
        RestClient.RequestBodySpec request = client.post().uri(uri);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request.exchange((req, res) -> res.getStatusCode());
    }
}
