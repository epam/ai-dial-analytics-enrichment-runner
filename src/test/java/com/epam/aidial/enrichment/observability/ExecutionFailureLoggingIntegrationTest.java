package com.epam.aidial.enrichment.observability;

import com.epam.aidial.enrichment.backfill.BackfillBatchFailureHandler;
import com.epam.aidial.enrichment.backfill.BackfillExecutor;
import com.epam.aidial.enrichment.backfill.BackfillTask;
import com.epam.aidial.enrichment.class1.Class1MaterializeExecutor;
import com.epam.aidial.enrichment.class1.Class1MaterializeTask;
import com.epam.aidial.enrichment.configuration.DbSchedulerConfiguration;
import com.epam.aidial.enrichment.configuration.DbSchedulerProperties;
import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dlq.DlqStage;
import com.epam.aidial.enrichment.exception.TransientChunkException;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.persistence.DlqItemWriter;
import com.epam.aidial.enrichment.scan.PipelineScanExecutor;
import com.epam.aidial.enrichment.scan.PipelineScanTask;
import com.epam.aidial.enrichment.support.EventCapture;
import com.epam.aidial.enrichment.worker.ChunkFailureHandler;
import com.epam.aidial.enrichment.worker.ChunkPayload;
import com.epam.aidial.enrichment.worker.ChunkRow;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.epam.aidial.enrichment.worker.WorkerMdc;
import com.epam.aidial.enrichment.worker.WorkerService;
import com.epam.aidial.enrichment.worker.group.GroupChunkTask;
import com.epam.aidial.enrichment.worker.group.GroupFailureHandler;
import com.epam.aidial.enrichment.worker.group.GroupWorkerService;
import com.epam.aidial.enrichment.worker.upsert.UpsertFailureHandler;
import com.epam.aidial.enrichment.worker.upsert.UpsertRowsTask;
import com.epam.aidial.enrichment.worker.upsert.UpsertService;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.PlainScheduleAndData;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * A db-scheduler execution's identity reaches every record of its failure — the handler's, the listener's and the
 * failure handler's; the library's own failure line is switched off — and the failure is described once, with at most one stack and never an
 * upstream body. Runs a real scheduler, built by the runner's own {@link DbSchedulerConfiguration}, against the
 * runner's PostgreSQL; the executors behind the tasks are mocks that fail on cue.
 */
@Slf4j
@SpringBootTest
class ExecutionFailureLoggingIntegrationTest {

    private static final String MARKER = "BODY-MARKER-c07d";
    private static final Pattern WORKER_THREAD = Pattern.compile(Scheduler.THREAD_PREFIX + "-pool-\\d+-thread-\\d+");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExecutionMdcListener executionMdcListener;

    private final PipelineScanExecutor scanExecutor = mock(PipelineScanExecutor.class);
    private final WorkerService workerService = mock(WorkerService.class);
    private PipelineScanTask scanTask;
    private EnrichChunkTask chunkTask;
    private Scheduler scheduler;
    private EventCapture capture;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
    }

    @BeforeEach
    void startScheduler() {
        jdbcTemplate.execute("TRUNCATE scheduled_tasks");
        scanTask = new PipelineScanTask(provider(PipelineScanExecutor.class, scanExecutor));
        // The real chunk failure handler, backing off far enough that a retried chunk is not re-run by the test.
        chunkTask = new EnrichChunkTask(provider(WorkerService.class, workerService), new ChunkFailureHandler(
                mock(DlqItemWriter.class), new JsonMapperConfiguration().jsonMapper(), new WorkerProperties(1, 1, 5, Duration.ofHours(1))));
        scheduler = new DbSchedulerConfiguration().scheduler(dataSource, scanTask, chunkTask,
                new BackfillTask(provider(BackfillExecutor.class, mock(BackfillExecutor.class)), mock(BackfillBatchFailureHandler.class)),
                new Class1MaterializeTask(provider(Class1MaterializeExecutor.class, mock(Class1MaterializeExecutor.class))),
                new GroupChunkTask(provider(GroupWorkerService.class, mock(GroupWorkerService.class)), mock(GroupFailureHandler.class)),
                new UpsertRowsTask(provider(UpsertService.class, mock(UpsertService.class)), mock(UpsertFailureHandler.class)),
                executionMdcListener, new DbSchedulerProperties(Duration.ofSeconds(45), Duration.ofMillis(100), 1, false));
        capture = EventCapture.attach();
        scheduler.start();
    }

    @AfterEach
    void stopScheduler() {
        scheduler.stop();
        capture.detach();
        jdbcTemplate.execute("TRUNCATE scheduled_tasks");
    }

    @Test
    void failedScanNamesItsPipelineOnEveryRecordWithOneLineAndOneStack() throws InterruptedException {
        doAnswer(invocation -> {
            log.info("scan tick running");
            throw new IllegalStateException("scan broke");
        }).when(scanExecutor).execute(anyString());

        scheduler.schedule(scanTask.task().schedulableInstance("r-logs",
                new PlainScheduleAndData(Schedules.fixedDelay(Duration.ofHours(1)), "r-logs")));
        awaitLine("execution failed: task=pipeline-scan");

        List<LogEvent> failure = workerRecords();
        assertThat(failure).extracting(e -> e.getMessage().getFormattedMessage())
                .anyMatch(m -> m.contains("scan tick running"))
                .noneMatch(m -> m.contains("during execution of task with name"));
        assertThat(failure).allSatisfy(e -> {
            assertThat(e.getContextData().<String>getValue(WorkerMdc.PIPELINE_NAME)).isEqualTo("r-logs");
            assertThat(e.getContextData().<String>getValue(WorkerMdc.TASK)).isEqualTo(PipelineScanTask.TASK_NAME);
            assertThat(e.getContextData().<String>getValue(WorkerMdc.TASK_INSTANCE)).isEqualTo("r-logs");
        });
        assertThat(failure).filteredOn(message(m -> m.startsWith("execution failed:"))).singleElement()
                .satisfies(e -> assertThat(e.getMessage().getFormattedMessage())
                        .contains("task=pipeline-scan", "instance=r-logs", "ms=", "cause=IllegalStateException"));
        assertThat(failure).filteredOn(e -> e.getThrown() != null).hasSize(1);
    }

    @Test
    void retriedChunkSaysWhyWithoutStackAndWithoutDialBody() throws InterruptedException {
        byte[] body = ("{\"message\":\"model overloaded for " + MARKER + "\"}").getBytes(StandardCharsets.UTF_8);
        doThrow(new TransientChunkException(DlqStage.DIAL_CALL, HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable", HttpHeaders.EMPTY, body, StandardCharsets.UTF_8)))
                .when(workerService).process(anyString(), any());

        String chunkId = "r-logs#h#c0";
        scheduler.schedule(chunkTask.task().instance(chunkId, new ChunkPayload("r-logs", 3, List.of(new ChunkRow()))),
                Instant.now());
        awaitLine("retrying in");

        List<LogEvent> failure = workerRecords();
        assertThat(failure).filteredOn(message(m -> m.contains("retrying in"))).singleElement()
                .satisfies(e -> assertThat(e.getMessage().getFormattedMessage())
                        .contains("Chunk " + chunkId + " failed: ", "503", "stage=dial_call", "pipeline=r-logs"));
        // The retry line is the failure's one line; the listener's own is DEBUG for a task whose handler logs.
        assertThat(failure).filteredOn(message(m -> m.startsWith("execution failed:")))
                .noneMatch(e -> e.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.INFO));
        assertThat(failure).allSatisfy(e ->
                assertThat(e.getContextData().<String>getValue(WorkerMdc.PIPELINE_NAME)).isEqualTo("r-logs"));
        assertThat(failure).filteredOn(e -> e.getThrown() != null).isEmpty();
        assertThat(capture.events()).extracting(EventCapture::rendered).noneMatch(text -> text.contains(MARKER));
    }

    private List<LogEvent> workerRecords() {
        return capture.events().stream().filter(e -> WORKER_THREAD.matcher(e.getThreadName()).matches()).toList();
    }

    @SuppressWarnings("BusyWait") // polling a log capture: there is no completion signal to wait on instead
    private void awaitLine(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (capture.events().stream().anyMatch(message(m -> m.contains(fragment)))) {
                // The listener's line is the last one of a failure; give the reschedule a moment to commit.
                Thread.sleep(200);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no line containing '" + fragment + "'");
    }

    private static Predicate<LogEvent> message(Predicate<String> test) {
        return e -> test.test(e.getMessage().getFormattedMessage());
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        return new StaticListableBeanFactory(Map.of(type.getSimpleName(), bean)).getBeanProvider(type);
    }
}
