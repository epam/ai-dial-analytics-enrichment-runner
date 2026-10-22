package com.epam.aidial.enrichment.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Singleton PostgreSQL Testcontainer shared by every full-context ({@code @SpringBootTest})
 * integration test. The runner owns a PostgreSQL runtime-state schema (db-scheduler job state +
 * cursors/DLQ/spend), so the Spring context needs PostgreSQL reachable at startup — Boot's Flyway
 * auto-configuration migrates eagerly and JPA needs the {@code DataSource}. The container is started
 * once in a static initializer and reused for the JVM lifetime (Testcontainers' Ryuk reaper removes
 * it on exit); container startup dominates wall-clock, so reuse keeps the suite fast.
 *
 * <p>{@link #registerProperties(DynamicPropertyRegistry)} wires the container into
 * {@code spring.datasource.*} and leaves Flyway enabled so the real runner-state provisioning runs —
 * that provisioning is part of the plumbing under test. Referencing the method also triggers class
 * initialization, starting the container on first use.
 *
 * <p>Requires a running Docker daemon.
 */
public final class PostgresContainerSupport {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    static {
        POSTGRES.start();
    }

    private PostgresContainerSupport() {
    }

    /**
     * Connection pool size per test context. Spring caches one context per distinct configuration and
     * every cached context keeps its Hikari pool open for the whole JVM, so the suite's connection demand
     * is (contexts × pool size) — which at Hikari's default of 10 exhausts the image's
     * {@code max_connections} of 100 as test classes accumulate. Tests drive their beans on the calling
     * thread, so a small pool is ample; the only concurrent user is db-scheduler, which most contexts
     * disable. The other half of the bound is the context-cache size in {@code src/test/resources/spring.properties}.
     */
    private static final int TEST_POOL_SIZE = 4;

    /** Wires the singleton container's connection settings into {@code spring.datasource.*}. */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> TEST_POOL_SIZE);
        registry.add("spring.flyway.enabled", () -> "true");
    }
}
