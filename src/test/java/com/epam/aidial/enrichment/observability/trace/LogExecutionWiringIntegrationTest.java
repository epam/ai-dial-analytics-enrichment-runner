package com.epam.aidial.enrichment.observability.trace;

import com.epam.aidial.enrichment.adas.AdasGroupFetchClient;
import com.epam.aidial.enrichment.adas.AdasMaterializeClient;
import com.epam.aidial.enrichment.adas.AdasRegistryClient;
import com.epam.aidial.enrichment.adas.AdasScanClient;
import com.epam.aidial.enrichment.adas.AdasUpsertClient;
import com.epam.aidial.enrichment.backfill.BackfillBatchFailureHandler;
import com.epam.aidial.enrichment.backfill.BackfillExecutor;
import com.epam.aidial.enrichment.class1.Class1MaterializeExecutor;
import com.epam.aidial.enrichment.dial.DialClient;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.scan.PipelineScanExecutor;
import com.epam.aidial.enrichment.sweep.GroupReadinessSweep;
import com.epam.aidial.enrichment.sync.PipelineSyncService;
import com.epam.aidial.enrichment.worker.ChunkFailureHandler;
import com.epam.aidial.enrichment.worker.WorkerService;
import com.epam.aidial.enrichment.worker.group.GroupFailureHandler;
import com.epam.aidial.enrichment.worker.group.GroupWorkerService;
import com.epam.aidial.enrichment.worker.upsert.UpsertFailureHandler;
import com.epam.aidial.enrichment.worker.upsert.UpsertService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.aop.support.AopUtils.isAopProxy;

/** Every {@link LogExecution}-annotated component is proxied in the real application context. */
@SpringBootTest
class LogExecutionWiringIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    @Test
    void annotatedComponentsAreAopProxies() {
        List<Class<?>> annotated = List.of(
                PipelineScanExecutor.class, Class1MaterializeExecutor.class, GroupReadinessSweep.class, WorkerService.class,
                GroupWorkerService.class, BackfillExecutor.class, AdasGroupFetchClient.class, AdasMaterializeClient.class,
                AdasRegistryClient.class, AdasScanClient.class, AdasUpsertClient.class, DialClient.class, UpsertService.class,
                PipelineSyncService.class, ChunkFailureHandler.class, GroupFailureHandler.class, UpsertFailureHandler.class,
                BackfillBatchFailureHandler.class);
        assertThat(annotated).allSatisfy(type -> {
            assertThat(type).hasAnnotation(LogExecution.class);
            assertThat(isAopProxy(context.getBean(type))).as(type.getSimpleName()).isTrue();
        });
    }
}
