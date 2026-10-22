package com.epam.aidial.enrichment.configuration;

import com.epam.aidial.enrichment.backfill.BackfillTask;
import com.epam.aidial.enrichment.class1.Class1MaterializeTask;
import com.epam.aidial.enrichment.observability.ExecutionMdcListener;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.logging.LogLevel;
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;

/**
 * Wires the db-scheduler {@link Scheduler} against the runner's PostgreSQL datasource.
 *
 * <p>The datasource is wrapped in a {@link TransactionAwareDataSourceProxy} so that a
 * {@code scheduler.schedule(...)} call made inside a Spring {@code @Transactional} unit joins that
 * same transaction — the foundation for M3's atomic "enqueue chunks + advance cursor" commit.
 * {@link JacksonSerializer} is used for task data (never Java serialization).
 *
 * <p>The {@link ExecutionMdcListener} puts each execution's identity in the MDC for its whole length and logs the
 * runner's one failure line. The library's own failure line is switched off: it prints the thrown exception's
 * message, which for an upstream rejection embeds the response body, and the listener's line already says
 * everything it would. A failure is logged with at most one stack trace — from the failure handler that decides
 * it, or from the listener.
 *
 * <p>Lifecycle (start/stop) is handled by {@link SchedulerLifecycle}, not here.
 */
@Configuration
public class DbSchedulerConfiguration {

    @Bean
    public Scheduler scheduler(DataSource dataSource, PipelineScanTask pipelineScanTask,
                               EnrichChunkTask enrichChunkTask, BackfillTask backfillTask,
                               Class1MaterializeTask class1MaterializeTask, GroupChunkTask groupChunkTask,
                               UpsertRowsTask upsertRowsTask, ExecutionMdcListener executionMdcListener,
                               DbSchedulerProperties properties) {
        DataSource txAware = new TransactionAwareDataSourceProxy(dataSource);
        return Scheduler.create(txAware, pipelineScanTask.task(), enrichChunkTask.task(), backfillTask.task(),
                        class1MaterializeTask.task(), groupChunkTask.task(), upsertRowsTask.task())
                .enablePriority()
                .serializer(new JacksonSerializer())
                .threads(properties.threads())
                .pollingInterval(properties.pollingInterval())
                .heartbeatInterval(properties.heartbeat())
                .addSchedulerListener(executionMdcListener)
                .failureLogging(LogLevel.OFF, false)
                .build();
    }
}
