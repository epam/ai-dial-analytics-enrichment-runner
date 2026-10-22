package com.epam.aidial.enrichment.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@code V8} applied over a database that already holds rows, which is the only way it ever runs in
 * production. Every other migration test in this suite exercises the empty-database path, and an empty
 * database cannot show what this migration actually does: rename the five state tables onto the pipeline
 * name, and then throw their contents away.
 *
 * <p>This test owns its own container rather than sharing the suite's, because the shared one is migrated to
 * the latest version at class-initialization time and there is no way back to {@code V7} from there.
 *
 * <p>The rows seeded here are keyed by <b>rule UUID</b>, which is what the tables hold when the migration
 * runs. There is no remap step to model: the {@code id → name} mapping lives in ADAS's catalog and this
 * Flyway cannot read it, so the state is discarded rather than carried across. What that costs — every
 * enabled pipeline re-deriving its target once, and a group lane re-evaluating every group when it is next
 * enabled — is set out in the migration's own header.
 */
class PipelineRenameMigrationIntegrationTest {

    private static final String RULE_ID = "9d3f0e4c-0000-4000-8000-00000000000a";

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
    }

    @Test
    void v8RenamesTheTablesOntoThePipelineName() throws Exception {
        migrateTo("7");
        seedStateKeyedByRuleId();

        migrateTo("8");

        assertThat(tableExists("pipeline_cursor")).isTrue();
        assertThat(columnExists("pipeline_cursor", "pipeline_name")).isTrue();
        assertThat(columnExists("run_history", "pipeline_name")).isTrue();
        assertThat(columnExists("dlq_item", "pipeline_name")).isTrue();
        assertThat(columnExists("spend_counter", "pipeline_name")).isTrue();
        assertThat(columnExists("group_state", "pipeline_name")).isTrue();

        // The old spellings are gone rather than duplicated — a leftover rule_cursor would be read by
        // nothing and swept by nothing.
        assertThat(tableExists("rule_cursor")).isFalse();
        assertThat(columnExists("group_state", "rule_id")).isFalse();
        assertThat(columnExists("dlq_item", "rule_id")).isFalse();
    }

    /**
     * The discard is all-or-nothing across the five tables, and that is the property worth asserting rather
     * than any one of them: the cursor advances at chunk enqueue, so a cursor kept beside a cleared queue
     * loses rows permanently, and {@code deadLetter} stamps a group computed before it writes the DLQ row,
     * so a {@code dlq_item} cleared beside a surviving {@code group_state} strands every dead-lettered group
     * as computed-but-unlabelled with nothing left to point at it.
     */
    @Test
    void v8DiscardsEveryStateTableTogether() throws Exception {
        migrateTo("7");
        seedStateKeyedByRuleId();

        migrateTo("8");

        assertThat(queryLong("SELECT count(*) FROM pipeline_cursor")).isZero();
        assertThat(queryLong("SELECT count(*) FROM run_history")).isZero();
        assertThat(queryLong("SELECT count(*) FROM dlq_item")).isZero();
        assertThat(queryLong("SELECT count(*) FROM spend_counter")).isZero();
        assertThat(queryLong("SELECT count(*) FROM group_state")).isZero();
    }

    /**
     * A recurring instance carries a UUID in both its instance id and its serialized payload once the
     * identity moves. {@code class1-materialize} keeps its task name, so it would otherwise simply be picked
     * up with a UUID where a pipeline name is expected; deleting it is free, because the next sync recreates
     * whatever is missing.
     */
    @Test
    void v8ClearsRecurringInstancesUnderBothSpellingsOfTheScanTask() throws Exception {
        migrateTo("7");
        seedScheduledTask("rule-scan", RULE_ID);
        seedScheduledTask("pipeline-scan", "usage-client-identity-live");
        seedScheduledTask("class1-materialize", RULE_ID);

        migrateTo("8");

        assertThat(queryLong("SELECT count(*) FROM scheduled_tasks")).isZero();
    }

    /**
     * A work item that crossed the window is unreachable — its deterministic id embeds the identity in the
     * hashed basis, not only the prefix. An earlier revision of this migration <em>refused</em> to run while
     * any survived, because the designed cut-over drained the queue by disabling every rule first. That
     * quiesce is gone with the remap, and there is nothing left for a surviving item to be consistent with
     * once the cursor that would have skipped its rows has been truncated, so they are deleted instead of
     * asserted upon: the rows they stood for are re-scanned from the beginning like everything else.
     */
    @Test
    void v8ClearsWorkItemsThatCrossedTheWindow() throws Exception {
        migrateTo("7");
        seedScheduledTask("enrich-group", RULE_ID + "#g#abc");
        seedScheduledTask("enrich-chunk", RULE_ID + "#c#def");
        seedScheduledTask("upsert-rows", RULE_ID + "#u#ghi");
        seedScheduledTask("backfill-run", RULE_ID + "#b#jkl");

        migrateTo("8");

        assertThat(queryLong("SELECT count(*) FROM scheduled_tasks")).isZero();
    }

    private static void migrateTo(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/postgres")
                .target(target)
                .load()
                .migrate();
    }

    private static void seedStateKeyedByRuleId() throws Exception {
        execute(
                "INSERT INTO rule_cursor (rule_id, cursor) VALUES ('" + RULE_ID + "', 'opaque-cursor')",
                "INSERT INTO run_history (id, rule_id, kind, state) "
                        + "VALUES ('run-1', '" + RULE_ID + "', 'backfill', 'paused')",
                "INSERT INTO dlq_item (rule_id, grain_key, evaluator_version, stage) "
                        + "VALUES ('" + RULE_ID + "', 'chat-7', 2, 'group_fetch')",
                "INSERT INTO spend_counter (rule_id, evaluator_version, day, calls, usage_unit) "
                        + "VALUES ('" + RULE_ID + "', 2, DATE '2026-09-01', 42, 'tokens')",
                "INSERT INTO group_state (rule_id, group_key, group_version, last_activity_at) "
                        + "VALUES ('" + RULE_ID + "', 'chat-7', 1700000000000, now())");
    }

    private static void seedScheduledTask(String taskName, String instanceId) throws Exception {
        execute("INSERT INTO scheduled_tasks (task_name, task_instance, execution_time, picked, version) "
                + "VALUES ('" + taskName + "', '" + instanceId + "', now(), false, 1)");
    }

    private static void execute(String... statements) throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static long queryLong(String sql) throws Exception {
        return query(sql, rs -> rs.getLong(1));
    }

    private static <T> T query(String sql, ResultReader<T> reader) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return fail("no row for: " + sql);
            }
            return reader.read(rs);
        }
    }

    private static boolean tableExists(String table) throws Exception {
        return queryLong("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = '" + table + "'") > 0;
    }

    private static boolean columnExists(String table, String column) throws Exception {
        return queryLong("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                + "AND column_name = '" + column + "'") > 0;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Reads one value off a positioned {@link ResultSet}. */
    private interface ResultReader<T> {
        T read(ResultSet rs) throws Exception;
    }
}
