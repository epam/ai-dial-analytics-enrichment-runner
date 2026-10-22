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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@code V9} applied over a database that already holds rows, which is the only way it ever runs in
 * production. An empty database cannot show what this migration does: rename two columns that each form
 * part of a uniqueness key, widen them, and rewrite <b>no row</b>.
 *
 * <p>Like {@link PipelineRenameMigrationIntegrationTest} this owns its own container, because the suite's
 * shared one is migrated to the latest version at class-initialization time and there is no way back to
 * {@code V8} from there.
 *
 * <p>The rows seeded here carry small evaluator-version integers under the old name, which is what the
 * tables hold when the migration runs. They are historical facts — no generation can be invented for them
 * after the fold — so the assertions below pin that they survive <em>unchanged</em> as much as that the
 * columns moved.
 */
class GenerationRekeyMigrationIntegrationTest {

    private static final String PIPELINE = "sentiment-live";

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
    void v9RenamesBothColumnsAndWidensThemToBigint() throws Exception {
        migrateTo("8");
        seedPreFoldRows();

        migrateTo("9");

        assertThat(columnType("dlq_item", "pipeline_generation")).isEqualTo("bigint");
        assertThat(columnType("spend_counter", "pipeline_generation")).isEqualTo("bigint");
        // The old spelling is gone rather than duplicated: a column named for an evaluator version holding
        // generations is the exact defect the rename exists to avoid, and leaving both would be worse still.
        assertThat(columnType("dlq_item", "evaluator_version")).isNull();
        assertThat(columnType("spend_counter", "evaluator_version")).isNull();
    }

    /**
     * The indexes <b>follow</b> the renamed column rather than being dropped and recreated, so this asserts
     * they still enforce what they enforced before — by trying to violate them. A rename that silently lost
     * the partial unique index would let a repeated per-row failure accumulate duplicate dead-letter rows
     * instead of upserting one, which is the failure {@code DlqItemWriter} is built around.
     */
    @Test
    void v9LeavesThePartialUniqueIndexAndThePrimaryKeyEnforcing() throws Exception {
        migrateTo("8");
        seedPreFoldRows();

        migrateTo("9");

        assertThat(rowIdentityIndexExists()).isTrue();
        assertThatThrownBy(() -> execute("INSERT INTO dlq_item (pipeline_name, grain_key, pipeline_generation, "
                + "stage) VALUES ('" + PIPELINE + "', 'chat-7', 2, 'validate')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO spend_counter (pipeline_name, pipeline_generation, day, "
                + "calls, usage_unit) VALUES ('" + PIPELINE + "', 2, DATE '2026-09-01', 1, 'tokens')"))
                .isInstanceOf(SQLException.class);
    }

    /**
     * A chunk-level dead-letter carries no grain key, so it falls outside the index predicate and the
     * uniqueness does not apply to it. Pinned because the partial-ness is what lets one statement serve both
     * kinds of archive.
     */
    @Test
    void v9KeepsTheIndexPartialSoGrainlessArchivesStillInsert() throws Exception {
        migrateTo("8");

        migrateTo("9");

        execute("INSERT INTO dlq_item (pipeline_name, pipeline_generation, stage) "
                + "VALUES ('" + PIPELINE + "', 17, 'dial_call')");
        execute("INSERT INTO dlq_item (pipeline_name, pipeline_generation, stage) "
                + "VALUES ('" + PIPELINE + "', 17, 'dial_call')");

        assertThat(queryLong("SELECT count(*) FROM dlq_item WHERE grain_key IS NULL")).isEqualTo(2);
    }

    /**
     * No row is rewritten, and that is a decision rather than an omission: a pre-cut-over value records which
     * evaluator version produced that item, and inventing a generation for it would be a fabrication in a
     * column whose whole purpose is provenance. The cost is stated in the migration header and asserted here
     * — the small integers survive, so a post-fold failure for the same grain writes a second row beside the
     * first rather than collapsing onto it.
     */
    @Test
    void v9RewritesNoRow() throws Exception {
        migrateTo("8");
        seedPreFoldRows();

        migrateTo("9");

        assertThat(queryLong("SELECT pipeline_generation FROM dlq_item WHERE grain_key = 'chat-7'"))
                .isEqualTo(2);
        assertThat(queryLong("SELECT calls FROM spend_counter WHERE pipeline_name = '" + PIPELINE + "'"))
                .isEqualTo(42);
        assertThat(queryLong("SELECT pipeline_generation FROM spend_counter WHERE pipeline_name = '"
                + PIPELINE + "'")).isEqualTo(2);

        // The same grain, failing again at a post-fold generation, is a SECOND row — visible, not corrupt,
        // and cleared by the retention sweep rather than by this migration.
        execute("INSERT INTO dlq_item (pipeline_name, grain_key, pipeline_generation, stage) "
                + "VALUES ('" + PIPELINE + "', 'chat-7', 17, 'validate')");
        assertThat(queryLong("SELECT count(*) FROM dlq_item WHERE grain_key = 'chat-7'")).isEqualTo(2);
    }

    /** A generation past {@code 2^31} fits, which is the whole reason the widening is not cosmetic. */
    @Test
    void v9AcceptsGenerationBeyondTheIntRange() throws Exception {
        migrateTo("8");

        migrateTo("9");

        long beyondInt = 3_000_000_000L;
        execute("INSERT INTO spend_counter (pipeline_name, pipeline_generation, day, calls, usage_unit) "
                + "VALUES ('" + PIPELINE + "', " + beyondInt + ", DATE '2026-09-18', 1, 'tokens')");

        assertThat(queryLong("SELECT pipeline_generation FROM spend_counter WHERE pipeline_name = '"
                + PIPELINE + "'")).isEqualTo(beyondInt);
    }

    private static void migrateTo(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/postgres")
                .target(target)
                .load()
                .migrate();
    }

    /** One dead-letter row and one spend row, both keyed on an evaluator version nothing will produce again. */
    private static void seedPreFoldRows() throws Exception {
        execute(
                "INSERT INTO dlq_item (pipeline_name, grain_key, evaluator_version, stage) "
                        + "VALUES ('" + PIPELINE + "', 'chat-7', 2, 'group_fetch')",
                "INSERT INTO spend_counter (pipeline_name, evaluator_version, day, calls, usage_unit) "
                        + "VALUES ('" + PIPELINE + "', 2, DATE '2026-09-01', 42, 'tokens')");
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

    /** The column's SQL type, or null when the column does not exist. */
    private static String columnType(String table, String column) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + table + "' "
                        + "AND column_name = '" + column + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static boolean rowIdentityIndexExists() throws Exception {
        return queryLong("SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' "
                + "AND indexname = 'dlq_item_row_identity_uk'") > 0;
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
