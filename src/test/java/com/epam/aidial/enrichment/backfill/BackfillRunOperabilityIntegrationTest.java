package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.adas.AdasScanClient;
import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.ScanRequest;
import com.epam.aidial.enrichment.exception.BackfillConflictException;
import com.epam.aidial.enrichment.exception.PipelineCacheColdException;
import com.epam.aidial.enrichment.exception.PipelineCacheNotReadyException;
import com.epam.aidial.enrichment.exception.PipelineNotFoundException;
import com.epam.aidial.enrichment.integration.PostgresContainerSupport;
import com.epam.aidial.enrichment.observability.status.SchedulerQueueReader;
import com.epam.aidial.enrichment.pause.PauseOrigin;
import com.epam.aidial.enrichment.pause.PipelinePauseRegistry;
import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.persistence.RunHistoryRepository;
import com.epam.aidial.enrichment.spend.SpendService;
import com.epam.aidial.enrichment.support.AdasWireMock;
import com.epam.aidial.enrichment.support.CapturingAppender;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.worker.EnrichChunkTask;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A backfill run's state machine, its self-description and its findability: the transition table, the
 * single-writer page commit, the start and resume gates, the pause reasons, the absent-pipeline and cold-cache
 * paths, the generation pin, the batch failure record, and the listing.
 *
 * <p>The scheduler is not started, so the executor and the failure handler are driven directly. Interleavings
 * that would otherwise need two live executions are produced deterministically by a spy on the scan client that
 * acts while a page is "in flight".
 */
@SpringBootTest
@AutoConfigureMockMvc
class BackfillRunOperabilityIntegrationTest {

    private static final WireMockServer ADAS = AdasWireMock.server();

    @Autowired
    private SchedulerQueueReader schedulerQueueReader;

    @Autowired
    private BackfillService backfillService;

    @Autowired
    private BackfillExecutor executor;

    @Autowired
    private BackfillBatchFailureHandler failureHandler;

    @MockitoSpyBean
    private PipelineCache pipelineCache;

    @MockitoSpyBean
    private AdasScanClient scanClient;

    @Autowired
    private PipelinePauseRegistry pauseRegistry;

    @Autowired
    private SpendService spendService;

    @MockitoSpyBean
    private RunHistoryRepository runHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startAdas() {
        ADAS.start();
    }

    @AfterAll
    static void stopAdas() {
        ADAS.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        registry.add("adas.base-url", ADAS::baseUrl);
        registry.add("db-scheduler.auto-start", () -> "false");
        registry.add("adas.pipelines.initial-delay-ms", () -> "3600000");
        registry.add("adas.pipelines.poll-interval-ms", () -> "3600000");
        registry.add("group.sweep-initial-delay", () -> "3600s");
        registry.add("group.sweep-interval", () -> "3600s");
        registry.add("spend.pause.enabled", () -> "true");
        registry.add("spend.budget.daily-calls", () -> "1");
    }

    @BeforeEach
    void reset() {
        ADAS.resetAll();
        jdbcTemplate.execute("TRUNCATE scheduled_tasks, run_history, dlq_item, spend_counter");
        pauseRegistry.paused().keySet().forEach(pauseRegistry::resume);
        pipelineCache.replaceAll(Map.of());
    }

    // ---- 7.1 the transition table -------------------------------------------------------------------------

    @Test
    void pausingRunThatIsNotRunningIsRefusedAndChangesNothing() {
        String name = cache("tt-pause");
        stubSinglePage(name);
        RunHistoryEntity done = backfillService.start(name);
        executor.run(done.getId());
        RunHistoryEntity cancelled = backfillService.start(name);
        backfillService.cancel(cancelled.getId());
        RunHistoryEntity paused = backfillService.start(name);
        backfillService.pause(paused.getId());

        for (RunHistoryEntity run : List.of(done, cancelled, paused)) {
            RunHistoryEntity before = runHistoryRepository.findById(run.getId()).orElseThrow();
            assertThatThrownBy(() -> backfillService.pause(run.getId()))
                    .isInstanceOf(BackfillConflictException.class)
                    .hasMessageContaining(before.getState());
            RunHistoryEntity after = runHistoryRepository.findById(run.getId()).orElseThrow();
            assertThat(after.getState()).isEqualTo(before.getState());
            assertThat(after.getFinishedAt()).isEqualTo(before.getFinishedAt());
        }
    }

    @Test
    void cancellingFinishedOrCancelledRunIsRefusedAndKeepsItsCompletionInstant() {
        String name = cache("tt-cancel");
        stubSinglePage(name);
        RunHistoryEntity done = backfillService.start(name);
        executor.run(done.getId());
        RunHistoryEntity cancelled = backfillService.start(name);
        backfillService.cancel(cancelled.getId());

        for (RunHistoryEntity run : List.of(done, cancelled)) {
            Instant finishedAt = backfillService.get(run.getId()).getFinishedAt();
            assertThat(finishedAt).isNotNull();
            assertThatThrownBy(() -> backfillService.cancel(run.getId())).isInstanceOf(BackfillConflictException.class);
            assertThat(backfillService.get(run.getId()).getFinishedAt()).isEqualTo(finishedAt);
        }
    }

    @Test
    void thePermittedTransitionsSucceed() {
        String name = cache("tt-ok");
        RunHistoryEntity run = backfillService.start(name);

        assertThat(backfillService.pause(run.getId()).getState()).isEqualTo("paused");
        assertThat(backfillService.resume(run.getId()).getState()).isEqualTo("running");
        assertThat(backfillService.cancel(run.getId()).getState()).isEqualTo("cancelled");

        RunHistoryEntity other = backfillService.start(name);
        backfillService.pause(other.getId());
        RunHistoryEntity cancelledWhilePaused = backfillService.cancel(other.getId());
        assertThat(cancelledWhilePaused.getState()).isEqualTo("cancelled");
        assertThat(cancelledWhilePaused.getFinishedAt()).isNotNull();
    }

    // ---- 7.2 concurrency -----------------------------------------------------------------------------------

    @Test
    void cancelMidBatchStopsAtTheNextPageAndKeepsTheCancel() {
        String name = cache("cc-cancel");
        stubThreePages(name);
        RunHistoryEntity run = backfillService.start(name);
        Instant[] cancelledAt = new Instant[1];
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(invocation -> {
            ScanRequest request = invocation.getArgument(1);
            if ("c1".equals(request.cursor()) && fired.compareAndSet(false, true)) {
                cancelledAt[0] = backfillService.cancel(run.getId()).getFinishedAt();
            }
            return invocation.callRealMethod();
        }).when(scanClient).scan(anyString(), any(ScanRequest.class));

        executor.run(run.getId());

        RunHistoryEntity after = backfillService.get(run.getId());
        assertThat(after.getState()).isEqualTo("cancelled");
        assertThat(after.getFinishedAt()).isEqualTo(cancelledAt[0]);
        // Page 1 committed; page 2's commit found the run cancelled and rolled back its chunk.
        assertThat(after.getCursor()).isEqualTo("c1");
        assertThat(after.getRowsDone()).isEqualTo(1);
        assertThat(taskCount(EnrichChunkTask.TASK_NAME)).isEqualTo(1);
        assertThat(after.getLastError()).isNull();
        // No successor chained: only the start's own first batch is in the queue.
        assertThat(taskCount(BackfillTask.TASK_NAME)).isEqualTo(1);
    }

    @Test
    void pauseThenResumeDuringDrainingBatchDrainsOnce() {
        String name = cache("cc-pause-resume");
        stubThreePages(name);
        RunHistoryEntity run = backfillService.start(name);
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(invocation -> {
            ScanRequest request = invocation.getArgument(1);
            if ("c1".equals(request.cursor()) && fired.compareAndSet(false, true)) {
                // While batch A holds page 2 in flight: pause, resume, and let the resume's batch B drain.
                backfillService.pause(run.getId());
                backfillService.resume(run.getId());
                executor.run(run.getId());
            }
            return invocation.callRealMethod();
        }).when(scanClient).scan(anyString(), any(ScanRequest.class));

        executor.run(run.getId());

        RunHistoryEntity after = backfillService.get(run.getId());
        assertThat(after.getState()).isEqualTo("done");
        assertThat(after.getCursor()).isEqualTo("c3");
        // Three pages of one row each, counted once: batch A's second commit was superseded by B.
        assertThat(after.getRowsDone()).isEqualTo(3);
        // One chunk a page, each enqueued once; and the only batches are the start's and the resume's — neither
        // the superseded batch A nor the completed batch B chained a successor.
        assertThat(taskCount(EnrichChunkTask.TASK_NAME)).isEqualTo(3);
        assertThat(taskCount(BackfillTask.TASK_NAME)).isEqualTo(2);
        assertThat(ADAS.findAll(postRequestedFor(urlPathEqualTo(scanPath(name))))).hasSize(4);
    }

    @Test
    void resumeWhileRetryIsPendingOrExecutingSchedulesFreshBatch() {
        String name = cache("cc-retry");
        stubThreePages(name);
        ADAS.stubFor(post(urlPathEqualTo(scanPath(name))).atPriority(1)
                .withRequestBody(containing("\"cursor\":\"c1\""))
                .willReturn(aResponse().withStatus(503)));
        RunHistoryEntity run = backfillService.start(name);
        String firstBatch = BackfillTask.instanceId(run.getId(), null);

        // Fails on page 2: the pending retry keeps the id hashed from the cursor it started at, not the row's.
        assertThatThrownBy(() -> executor.run(run.getId())).isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        assertThat(backfillService.get(run.getId()).getCursor()).isEqualTo("c1");

        backfillService.pause(run.getId());
        backfillService.resume(run.getId());

        // The resume is not swallowed by the still-present first batch: a fresh instance exists beside it.
        List<String> instances = jdbcTemplate.queryForList(
                "SELECT task_instance FROM scheduled_tasks WHERE task_name = ?", String.class, BackfillTask.TASK_NAME);
        assertThat(instances).contains(firstBatch);
        assertThat(instances).anySatisfy(id -> assertThat(id)
                .startsWith(BackfillTask.instanceId(run.getId(), "c1") + "#r"));

        ADAS.resetAll();
        stubThreePages(name);
        // Both executions fire; the second finds the run done and stops.
        executor.run(run.getId());
        executor.run(run.getId());
        RunHistoryEntity after = backfillService.get(run.getId());
        assertThat(after.getState()).isEqualTo("done");
        assertThat(after.getRowsDone()).isEqualTo(3);
    }

    /**
     * Each verb reads the run, and a page commit lands between that read and the verb's write. A verb that
     * saved the entity it read would write the older cursor back; the conditional statements touch only the
     * columns they own.
     */
    @Test
    void anOperatorVerbNeverMovesTheCursorBackwards() {
        String name = cache("cc-cursor");
        RunHistoryEntity run = backfillService.start(name);
        clearInvocations(runHistoryRepository);
        int[] commits = new int[1];
        AtomicBoolean commitOnNextRead = new AtomicBoolean();
        // The repository is a JDK proxy, so the spy reaches it through its default (delegating) answer rather
        // than callRealMethod.
        Answer<?> real = mockingDetails(runHistoryRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object read = real.answer(invocation);
            if (commitOnNextRead.compareAndSet(true, false)) {
                commits[0]++;
                jdbcTemplate.update("UPDATE run_history SET cursor = ?, rows_done = rows_done + 5 WHERE id = ?",
                        "c" + commits[0], run.getId());
            }
            return read;
        }).when(runHistoryRepository).findById(run.getId());

        commitOnNextRead.set(true);
        backfillService.pause(run.getId());
        commitOnNextRead.set(true);
        backfillService.resume(run.getId());
        commitOnNextRead.set(true);
        backfillService.cancel(run.getId());

        assertThat(commits[0]).isEqualTo(3);
        RunHistoryEntity after = backfillService.get(run.getId());
        assertThat(after.getState()).isEqualTo("cancelled");
        assertThat(after.getCursor()).isEqualTo("c3");
        assertThat(after.getRowsDone()).isEqualTo(15);
        verify(runHistoryRepository, never()).save(any());
        verify(runHistoryRepository, never()).saveAndFlush(any());
        verify(runHistoryRepository, never()).saveAll(any());
    }

    @Test
    void concurrentStartsGiveOneRunAndRefuseTheRest() throws Exception {
        String name = cache("cc-starts");
        int attempts = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        backfillService.start(name);
                        return true;
                    } catch (BackfillConflictException e) {
                        return false;
                    }
                }));
            }
            go.countDown();
            int started = 0;
            for (Future<Boolean> result : results) {
                started += result.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(started).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(runHistoryRepository.findByPipelineNameAndState(name, "running")).hasSize(1);
    }

    /** Both sides pass the pre-check; the unique index is what decides, and the loser is told so. */
    @Test
    void startAndResumeRacingPastThePreCheckAreRefusedByTheIndex() {
        String name = cache("cc-index");
        RunHistoryEntity paused = backfillService.start(name);
        backfillService.pause(paused.getId());
        RunHistoryEntity running = backfillService.start(name);
        doReturn(List.of()).when(runHistoryRepository).findByPipelineNameAndState(anyString(), anyString());

        assertThatThrownBy(() -> backfillService.start(name))
                .isInstanceOf(BackfillConflictException.class)
                .hasMessageContaining("became running concurrently")
                .hasMessageContaining("pipeline_name=" + name);
        assertThatThrownBy(() -> backfillService.resume(paused.getId()))
                .isInstanceOf(BackfillConflictException.class)
                .hasMessageContaining("became running concurrently");

        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM run_history WHERE pipeline_name = ? AND state = 'running'", String.class, name))
                .containsExactly(running.getId());
        assertThat(backfillService.get(paused.getId()).getState()).isEqualTo("paused");
        assertThat(backfillService.get(paused.getId()).getPausedReason()).isEqualTo("operator");
    }

    @Test
    void integrityViolationOtherThanTheIndexIsNotReportedAsRace() {
        String name = cache("cc-other-violation");
        DataIntegrityViolationException other = new DataIntegrityViolationException("run_history_pkey");
        doThrow(other).when(runHistoryRepository).saveAndFlush(any(RunHistoryEntity.class));

        assertThatThrownBy(() -> backfillService.start(name)).isSameAs(other);
    }

    // ---- 7.3 the start and resume gates --------------------------------------------------------------------

    @Test
    void startAndResumeIntoPausedPipelineAreRefused() {
        String name = cache("g-paused");
        RunHistoryEntity run = backfillService.start(name);
        backfillService.pause(run.getId());
        backfillService.cancel(backfillService.start(name).getId());
        pauseRegistry.pause(name, PauseOrigin.OPERATOR, "paused by an operator");

        assertThatThrownBy(() -> backfillService.start(name))
                .isInstanceOf(BackfillConflictException.class).hasMessageContaining("paused by an operator");
        assertThatThrownBy(() -> backfillService.resume(run.getId()))
                .isInstanceOf(BackfillConflictException.class).hasMessageContaining("paused by an operator");
        assertThat(backfillService.get(run.getId()).getState()).isEqualTo("paused");

        pauseRegistry.pauseUntil(name, PauseOrigin.BREAKER, "40/50", Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> backfillService.start(name)).hasMessageContaining("DLQ circuit breaker");
    }

    @Test
    void startAndResumeOverBudgetAreRefusedNamingTheReset() {
        String name = cache("g-budget");
        RunHistoryEntity run = backfillService.start(name);
        backfillService.pause(run.getId());
        spendService.record(name, 1, 0, false);

        assertThatThrownBy(() -> backfillService.start(name))
                .isInstanceOf(BackfillConflictException.class).hasMessageContaining("00:00 UTC");
        assertThatThrownBy(() -> backfillService.resume(run.getId()))
                .isInstanceOf(BackfillConflictException.class).hasMessageContaining("spend budget");
        assertThat(runHistoryRepository.findByPipelineNameAndState(name, "running")).isEmpty();
    }

    @Test
    void startForPipelineAbsentFromPopulatedCacheIsNotFound() {
        assertThatThrownBy(() -> backfillService.start("g-absent")).isInstanceOf(PipelineNotFoundException.class);
    }

    @Test
    void startAndResumeBeforeTheFirstSyncAreUnavailableNotNotFound() throws Exception {
        String name = cache("g-cold");
        RunHistoryEntity run = backfillService.start(name);
        backfillService.pause(run.getId());
        doReturn(false).when(pipelineCache).isPopulated();

        assertThatThrownBy(() -> backfillService.resume(run.getId())).isInstanceOf(PipelineCacheNotReadyException.class);
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/pipelines/" + name + "/backfill"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("pipeline_cache_cold"));
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/backfills/" + run.getId() + "/resume"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("pipeline_cache_cold"));
    }

    @Test
    void resumeWhileAnotherRunIsRunningIsRefusedNamingIt() {
        String name = cache("g-running");
        RunHistoryEntity paused = backfillService.start(name);
        backfillService.pause(paused.getId());
        RunHistoryEntity running = backfillService.start(name);

        assertThatThrownBy(() -> backfillService.resume(paused.getId()))
                .isInstanceOf(BackfillConflictException.class).hasMessageContaining(running.getId());
        assertThatThrownBy(() -> backfillService.start(name)).hasMessageContaining(running.getId());
    }

    // ---- 7.4 pause reasons ---------------------------------------------------------------------------------

    @Test
    void eachPauseSiteRecordsItsReasonAndInstant() {
        String name = cache("r-sites");
        RunHistoryEntity operator = backfillService.start(name);
        backfillService.pause(operator.getId());
        assertReason(operator.getId(), BackfillPauseReason.OPERATOR);

        RunHistoryEntity stalled = backfillService.start(name);
        ADAS.stubFor(post(urlPathEqualTo(scanPath(name))).willReturn(okJson(page("e0", "c1", true))));
        ADAS.stubFor(post(urlPathEqualTo(scanPath(name))).atPriority(1)
                .withRequestBody(containing("\"cursor\":\"c1\"")).willReturn(okJson(page("e1", "c1", true))));
        executor.run(stalled.getId());
        assertReason(stalled.getId(), BackfillPauseReason.CURSOR_STALLED);
        assertThat(backfillService.get(stalled.getId()).getCursor()).isEqualTo("c1");

        RunHistoryEntity spent = backfillService.start(name);
        spendService.record(name, 1, 0, false);
        executor.run(spent.getId());
        assertReason(spent.getId(), BackfillPauseReason.SPEND_BUDGET_EXHAUSTED);
    }

    @Test
    void thePipelinePausesTwoOriginsRecordDifferentReasonsAndLogLines() {
        CapturingAppender logs = CapturingAppender.attachAtTrace();
        try {
            String byOperator = cache("r-op");
            RunHistoryEntity first = backfillService.start(byOperator);
            pauseRegistry.pause(byOperator, PauseOrigin.OPERATOR, "paused by an operator");
            executor.run(first.getId());

            String byBreaker = cache("r-br", byOperator);
            RunHistoryEntity second = backfillService.start(byBreaker);
            pauseRegistry.pauseUntil(byBreaker, PauseOrigin.BREAKER, "40/50", Instant.now().plusSeconds(600));
            executor.run(second.getId());

            assertReason(first.getId(), BackfillPauseReason.PIPELINE_PAUSED_OPERATOR);
            assertReason(second.getId(), BackfillPauseReason.PIPELINE_PAUSED_BREAKER);
            // The reason is stored, so a restart clearing the registry does not erase it.
            pauseRegistry.resume(byOperator);
            pauseRegistry.resume(byBreaker);
            assertReason(first.getId(), BackfillPauseReason.PIPELINE_PAUSED_OPERATOR);
            assertThat(logs.containsText("pipeline r-op was paused by an operator")).isTrue();
            assertThat(logs.containsText("pipeline r-br was paused by the DLQ circuit breaker")).isTrue();
        } finally {
            logs.detach(Level.INFO);
        }
    }

    @Test
    void resumeAndCancelClearTheReason() {
        String name = cache("r-clear");
        RunHistoryEntity run = backfillService.start(name);
        backfillService.pause(run.getId());

        RunHistoryEntity resumed = backfillService.resume(run.getId());
        assertThat(resumed.getPausedReason()).isNull();
        assertThat(resumed.getPausedAt()).isNull();

        backfillService.pause(run.getId());
        RunHistoryEntity cancelled = backfillService.cancel(run.getId());
        assertThat(cancelled.getPausedReason()).isNull();
        assertThat(cancelled.getPausedAt()).isNull();
    }

    /** Without authentication (the test suite runs {@code SECURITY_MODE=none}) each verb still names its principal. */
    @Test
    void eachOperatorVerbLogsItsActionPipelineAndRunWithPrincipalDash() {
        String name = cache("r-audit");
        CapturingAppender logs = CapturingAppender.attachAtTrace();
        try {
            RunHistoryEntity run = backfillService.start(name);
            backfillService.pause(run.getId());
            backfillService.resume(run.getId());
            backfillService.cancel(run.getId());

            for (String action : List.of("backfill_start", "backfill_pause", "backfill_resume", "backfill_cancel")) {
                assertThat(logs.containsText("operator action: action=" + action + " pipeline=r-audit run=" + run.getId()
                        + " principal=-")).as(action).isTrue();
            }
        } finally {
            logs.detach(Level.INFO);
        }
    }

    /** A verb that is refused never commits, so it leaves no line claiming it happened. */
    @Test
    void refusedVerbLogsNoOperatorAction() {
        String name = cache("r-refused");
        RunHistoryEntity run = backfillService.start(name);
        CapturingAppender logs = CapturingAppender.attachAtTrace();
        try {
            assertThatThrownBy(() -> backfillService.resume(run.getId())).isInstanceOf(BackfillConflictException.class);
            assertThat(logs.containsText("action=backfill_resume")).isFalse();
        } finally {
            logs.detach(Level.INFO);
        }
    }

    /** A running run whose chain was lost is found by the status report's query, and only then. */
    @Test
    void runningRunWithNoExecutionIsReportedAsOrphaned() {
        String name = cache("r-orphan");
        RunHistoryEntity run = backfillService.start(name);
        assertThat(schedulerQueueReader.orphanedBackfills()).noneMatch(orphan -> orphan.runId().equals(run.getId()));

        jdbcTemplate.update("DELETE FROM scheduled_tasks WHERE task_name = 'backfill-run'");

        assertThat(schedulerQueueReader.orphanedBackfills())
                .contains(new SchedulerQueueReader.OrphanedRun(run.getId(), name));
    }

    @Test
    void runThatIsNotPausedSerializesNoReasonAndNoTotal() throws Exception {
        String name = cache("r-wire");
        RunHistoryEntity run = backfillService.start(name);

        mockMvc.perform(get("/v1/backfills/" + run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("running"))
                .andExpect(jsonPath("$.rows_done").value(0))
                .andExpect(jsonPath("$.paused_reason").doesNotExist())
                .andExpect(jsonPath("$.paused_at").doesNotExist())
                .andExpect(jsonPath("$.last_error").doesNotExist())
                .andExpect(jsonPath("$.rows_total").doesNotExist())
                .andExpect(jsonPath("$.pipeline_generation").doesNotExist());

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/backfills/" + run.getId() + "/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused_reason").value("operator"))
                .andExpect(jsonPath("$.paused_at").exists());
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/backfills/" + run.getId() + "/pause"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("backfill_conflict"));
    }

    @Test
    void batchFiringAfterItsPauseConditionLiftedLeavesTheRunPausedAndDrainsNothing() {
        String name = cache("r-lifted");
        stubSinglePage(name);
        RunHistoryEntity run = backfillService.start(name);
        spendService.record(name, 1, 0, false);
        executor.run(run.getId());
        assertReason(run.getId(), BackfillPauseReason.SPEND_BUDGET_EXHAUSTED);
        RunHistoryEntity paused = backfillService.get(run.getId());

        // The UTC day rolls over: the day's spend no longer counts against the budget. A batch still fires.
        jdbcTemplate.execute("TRUNCATE spend_counter");
        executor.run(run.getId());

        RunHistoryEntity after = backfillService.get(run.getId());
        assertReason(run.getId(), BackfillPauseReason.SPEND_BUDGET_EXHAUSTED);
        assertThat(after.getPausedAt()).isEqualTo(paused.getPausedAt());
        assertThat(after.getCursor()).isNull();
        assertThat(after.getRowsDone()).isZero();
        assertThat(ADAS.findAll(postRequestedFor(urlPathEqualTo(scanPath(name))))).isEmpty();
        assertThat(taskCount(EnrichChunkTask.TASK_NAME)).isZero();
        // Only the start's own batch: nothing chained, nothing resumed.
        assertThat(taskCount(BackfillTask.TASK_NAME)).isEqualTo(1);
    }

    // ---- 7.5 absent pipeline and cold cache ----------------------------------------------------------------

    @Test
    void pipelineLeavingTheCachePausesTheRunWhichWaitsForAnOperator() {
        String name = cache("a-absent");
        PipelineDto pipeline = pipelineCache.get(name);
        stubThreePages(name);
        RunHistoryEntity run = backfillService.start(name);
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(invocation -> {
            Object page = invocation.callRealMethod();
            if (fired.compareAndSet(false, true)) {
                // Disabled in ADAS while page 1 is in flight; the next sync drops it from the cache.
                pipelineCache.replaceAll(Map.of());
            }
            return page;
        }).when(scanClient).scan(anyString(), any(ScanRequest.class));

        executor.run(run.getId());

        assertReason(run.getId(), BackfillPauseReason.PIPELINE_ABSENT);
        assertThat(backfillService.get(run.getId()).getCursor()).isEqualTo("c1");
        assertThatThrownBy(() -> backfillService.resume(run.getId())).isInstanceOf(PipelineNotFoundException.class);
        assertThat(backfillService.get(run.getId()).getState()).isEqualTo("paused");

        // It returns. The paused run blocks nothing, and does not restart by itself.
        pipelineCache.replaceAll(Map.of(name, pipeline));
        RunHistoryEntity fresh = backfillService.start(name);
        assertThat(fresh.getState()).isEqualTo("running");
        assertReason(run.getId(), BackfillPauseReason.PIPELINE_ABSENT);
        backfillService.cancel(fresh.getId());

        ADAS.resetAll();
        stubThreePages(name);
        backfillService.resume(run.getId());
        executor.run(run.getId());
        assertThat(ADAS.findAll(postRequestedFor(urlPathEqualTo(scanPath(name)))).getFirst().getBodyAsString())
                .contains("\"cursor\":\"c1\"");
        assertThat(backfillService.get(run.getId()).getState()).isEqualTo("done");
    }

    @Test
    void batchDueBeforeTheFirstSyncIsHeldNotPaused() {
        String name = cache("a-cold");
        RunHistoryEntity run = backfillService.start(name);
        doThrow(new PipelineCacheColdException("backfill batch " + run.getId()))
                .when(pipelineCache).requirePopulated(anyString());

        Throwable thrown = null;
        try {
            executor.run(run.getId());
        } catch (PipelineCacheColdException e) {
            thrown = e;
        }
        assertThat(thrown).isNotNull();
        @SuppressWarnings("unchecked")
        ExecutionOperations<String> operations = mock(ExecutionOperations.class);
        failureHandler.onFailure(failure(run.getId(), thrown), operations);

        RunHistoryEntity after = backfillService.get(run.getId());
        assertThat(after.getState()).isEqualTo("running");
        assertThat(after.getPausedReason()).isNull();
        assertThat(after.getLastError()).isNull();
        verify(operations).reschedule(any(ExecutionComplete.class), any(Instant.class));
        verify(operations, never()).remove();
    }

    // ---- 7.6 the generation pin ----------------------------------------------------------------------------

    @Test
    void anEditMidRunPausesBeforeTheNextPageAndResumeAdoptsTheNewGeneration() {
        String name = "gen-edit";
        pipelineCache.replaceAll(Map.of(name, pipeline(name, 1)));
        stubSinglePage(name);
        RunHistoryEntity run = backfillService.start(name);
        assertThat(run.getPipelineGeneration()).isEqualTo(1);

        pipelineCache.replaceAll(Map.of(name, pipeline(name, 2)));
        executor.run(run.getId());

        assertReason(run.getId(), BackfillPauseReason.PIPELINE_CHANGED);
        assertThat(ADAS.findAll(postRequestedFor(urlPathEqualTo(scanPath(name))))).isEmpty();

        assertThat(backfillService.resume(run.getId()).getPipelineGeneration()).isEqualTo(2);
        executor.run(run.getId());
        assertThat(backfillService.get(run.getId()).getState()).isEqualTo("done");
    }

    @Test
    void runRecordedBeforeTheGenerationColumnAdoptsOneWithoutPausing() {
        String name = cache("gen-legacy");
        stubSinglePage(name);
        RunHistoryEntity run = backfillService.start(name);
        jdbcTemplate.update("UPDATE run_history SET pipeline_generation = NULL WHERE id = ?", run.getId());

        executor.run(run.getId());

        RunHistoryEntity after = backfillService.get(run.getId());
        assertThat(after.getState()).isEqualTo("done");
        assertThat(after.getPipelineGeneration()).isEqualTo(1);
    }

    /**
     * Batch A drains under generation 1. While its second page is in flight an operator pauses the run, the
     * pipeline is edited to generation 2, and the operator resumes — which adopts 2. A's commit is keyed to 1: it
     * must neither land (its chunk ids are generation 1's) nor, at its next gate, re-pause the run the operator
     * just resumed as {@code pipeline_changed}.
     */
    @Test
    void staleExecutionMidPageDoesNotCommitUnderTheGenerationTheResumeReplaced() {
        String name = cache("gen-stale-page");
        stubThreePages(name);
        RunHistoryEntity run = backfillService.start(name);
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(invocation -> {
            ScanRequest request = invocation.getArgument(1);
            if ("c1".equals(request.cursor()) && fired.compareAndSet(false, true)) {
                backfillService.pause(run.getId());
                pipelineCache.replaceAll(Map.of(name, pipeline(name, 2)));
                assertThat(backfillService.resume(run.getId()).getPipelineGeneration()).isEqualTo(2);
            }
            return invocation.callRealMethod();
        }).when(scanClient).scan(anyString(), any(ScanRequest.class));

        executor.run(run.getId());

        RunHistoryEntity after = backfillService.get(run.getId());
        assertThat(after.getState()).isEqualTo("running");
        assertThat(after.getPausedReason()).isNull();
        assertThat(after.getPipelineGeneration()).isEqualTo(2);
        // Page 1 committed under generation 1; page 2's commit was superseded and rolled back its chunk.
        assertThat(after.getCursor()).isEqualTo("c1");
        assertThat(after.getRowsDone()).isEqualTo(1);
        assertThat(taskCount(EnrichChunkTask.TASK_NAME)).isEqualTo(1);
        // A chained nothing: the start's batch and the resume's.
        assertThat(taskCount(BackfillTask.TASK_NAME)).isEqualTo(2);

        // The resume's chain drains the rest under generation 2.
        executor.run(run.getId());
        RunHistoryEntity drained = backfillService.get(run.getId());
        assertThat(drained.getState()).isEqualTo("done");
        assertThat(drained.getCursor()).isEqualTo("c3");
        assertThat(drained.getRowsDone()).isEqualTo(3);
    }

    /**
     * The same interleaving, landing between the batch's read of the run and its first gate: the batch still
     * holds generation 1, meets generation 2 in the cache, and its {@code pipeline_changed} pause must yield to
     * the operator's resume under 2.
     */
    @Test
    void staleExecutionAtItsGateDoesNotRePauseTheRunTheOperatorResumed() {
        String name = cache("gen-stale-gate");
        stubSinglePage(name);
        RunHistoryEntity run = backfillService.start(name);
        AtomicBoolean fired = new AtomicBoolean();
        doAnswer(invocation -> {
            if (fired.compareAndSet(false, true)) {
                backfillService.pause(run.getId());
                pipelineCache.replaceAll(Map.of(name, pipeline(name, 2)));
                backfillService.resume(run.getId());
            }
            return invocation.callRealMethod();
        }).when(pipelineCache).requirePopulated(anyString());

        executor.run(run.getId());

        RunHistoryEntity after = backfillService.get(run.getId());
        assertThat(after.getState()).isEqualTo("running");
        assertThat(after.getPausedReason()).isNull();
        assertThat(after.getPausedAt()).isNull();
        assertThat(after.getPipelineGeneration()).isEqualTo(2);
        assertThat(ADAS.findAll(postRequestedFor(urlPathEqualTo(scanPath(name))))).isEmpty();

        executor.run(run.getId());
        assertThat(backfillService.get(run.getId()).getState()).isEqualTo("done");
    }

    @Test
    void theRunnersTransitionsAreConditionalOnThePinnedGeneration() {
        String name = cache("gen-repo");
        RunHistoryEntity run = backfillService.start(name);

        assertThat(runHistoryRepository.advance(run.getId(), 2L, null, "c1", 5)).isZero();
        assertThat(runHistoryRepository.advance(run.getId(), null, null, "c1", 5)).isZero();
        assertThat(runHistoryRepository.pauseIfRunning(run.getId(), 2L, "pipeline_changed", Instant.now())).isZero();
        assertThat(runHistoryRepository.pauseIfRunning(run.getId(), null, "pipeline_changed", Instant.now())).isZero();

        RunHistoryEntity untouched = backfillService.get(run.getId());
        assertThat(untouched.getState()).isEqualTo("running");
        assertThat(untouched.getCursor()).isNull();
        assertThat(untouched.getRowsDone()).isZero();
        assertThat(untouched.getPausedReason()).isNull();

        assertThat(runHistoryRepository.advance(run.getId(), 1L, null, "c1", 5)).isEqualTo(1);
        assertThat(runHistoryRepository.pauseIfRunning(run.getId(), 1L, "pipeline_changed", Instant.now())).isEqualTo(1);
        assertReason(run.getId(), BackfillPauseReason.PIPELINE_CHANGED);
    }

    @Test
    void theRunnersTransitionsLeaveCancelledRunAlone() {
        String name = cache("tt-cancel-repo");
        RunHistoryEntity run = backfillService.start(name);
        Instant finishedAt = backfillService.cancel(run.getId()).getFinishedAt();

        assertThat(runHistoryRepository.markDoneIfRunning(run.getId(), Instant.now().plusSeconds(60))).isZero();
        assertThat(runHistoryRepository.pauseIfRunning(run.getId(), 1L, "cursor_stalled", Instant.now())).isZero();
        assertThat(runHistoryRepository.advance(run.getId(), 1L, null, "c1", 5)).isZero();

        RunHistoryEntity after = backfillService.get(run.getId());
        assertThat(after.getState()).isEqualTo("cancelled");
        assertThat(after.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(after.getPausedReason()).isNull();
        assertThat(after.getPausedAt()).isNull();
        assertThat(after.getCursor()).isNull();
    }

    // ---- 7.7 the batch failure record ----------------------------------------------------------------------

    @Test
    void anAdasRejectionIsRecordedWithoutItsBodyCountedAndClearedByTheNextPage() {
        String name = cache("f-reject");
        ADAS.stubFor(post(urlPathEqualTo(scanPath(name))).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":422,\"error\":\"pipeline_validation_failed\",\"message\":\"secret detail\"}")));
        RunHistoryEntity run = backfillService.start(name);
        double before = batchFailures(name);

        Throwable thrown = catchFailure(run.getId());
        @SuppressWarnings("unchecked")
        ExecutionOperations<String> operations = mock(ExecutionOperations.class);
        failureHandler.onFailure(failure(run.getId(), thrown), operations);

        RunHistoryEntity failed = backfillService.get(run.getId());
        assertThat(failed.getState()).isEqualTo("running");
        assertThat(failed.getLastError()).isEqualTo("ADAS 422 pipeline_validation_failed");
        assertThat(failed.getLastErrorAt()).isNotNull();
        assertThat(batchFailures(name)).isEqualTo(before + 1);
        verify(operations).reschedule(any(ExecutionComplete.class), any(Instant.class));

        ADAS.resetAll();
        stubSinglePage(name);
        executor.run(run.getId());
        RunHistoryEntity recovered = backfillService.get(run.getId());
        assertThat(recovered.getLastError()).isNull();
        assertThat(recovered.getLastErrorAt()).isNull();
    }

    @Test
    void cancelledRunKeepsItsLastFailureAndIsNotRetried() {
        String name = cache("f-cancel");
        ADAS.stubFor(post(urlPathEqualTo(scanPath(name))).willReturn(aResponse().withStatus(422)));
        RunHistoryEntity run = backfillService.start(name);
        Throwable thrown = catchFailure(run.getId());
        @SuppressWarnings("unchecked")
        ExecutionOperations<String> first = mock(ExecutionOperations.class);
        failureHandler.onFailure(failure(run.getId(), thrown), first);

        backfillService.cancel(run.getId());
        @SuppressWarnings("unchecked")
        ExecutionOperations<String> second = mock(ExecutionOperations.class);
        failureHandler.onFailure(failure(run.getId(), thrown), second);

        RunHistoryEntity after = backfillService.get(run.getId());
        assertThat(after.getState()).isEqualTo("cancelled");
        assertThat(after.getLastError()).isEqualTo("ADAS 422");
        verify(second).remove();
        verify(second, never()).reschedule(any(ExecutionComplete.class), any(Instant.class));
    }

    // ---- 7.8 the listing -----------------------------------------------------------------------------------

    @Test
    void theListingNarrowsByPipelineStateAndReasonNewestFirst() throws Exception {
        insertRun("l-1", "p-a", "done", null, "2026-09-01T00:00:00Z");
        insertRun("l-2", "p-a", "paused", "cursor_stalled", "2026-09-02T00:00:00Z");
        insertRun("l-3", "p-b", "paused", "cursor_stalled", "2026-09-03T00:00:00Z");
        insertRun("l-4", "p-b", "paused", "operator", "2026-09-04T00:00:00Z");
        insertRun("l-5", "p-b", "running", null, "2026-09-05T00:00:00Z");

        mockMvc.perform(get("/v1/backfills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.length()").value(5))
                .andExpect(jsonPath("$.runs[0].id").value("l-5"))
                .andExpect(jsonPath("$.runs[4].id").value("l-1"));
        mockMvc.perform(get("/v1/backfills").param("pipeline_name", "p-a"))
                .andExpect(jsonPath("$.runs.length()").value(2));
        mockMvc.perform(get("/v1/backfills").param("state", "paused"))
                .andExpect(jsonPath("$.runs.length()").value(3));
        mockMvc.perform(get("/v1/backfills").param("paused_reason", "cursor_stalled"))
                .andExpect(jsonPath("$.runs.length()").value(2))
                .andExpect(jsonPath("$.runs[0].id").value("l-3"));
        mockMvc.perform(get("/v1/backfills").param("pipeline_name", "p-b").param("state", "paused")
                        .param("paused_reason", "operator"))
                .andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].id").value("l-4"));
    }

    @Test
    void theValueRunReportsIsTheValueTheFilterAcceptsInAnyCase() throws Exception {
        insertRun("l-run", "p-a", "running", null, "2026-09-01T00:00:00Z");

        mockMvc.perform(get("/v1/backfills").param("state", "running"))
                .andExpect(jsonPath("$.runs[0].id").value("l-run"));
        mockMvc.perform(get("/v1/backfills").param("state", "RUNNING"))
                .andExpect(jsonPath("$.runs[0].id").value("l-run"));
    }

    @Test
    void misspelledFilterIsRefusedNamingThePermittedValues() throws Exception {
        mockMvc.perform(get("/v1/backfills").param("state", "runing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(containsString("running, paused, done, cancelled")));
        mockMvc.perform(get("/v1/backfills").param("paused_reason", "stalled"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("cursor_stalled")));
    }

    @Test
    void theLimitIsClampedAndListedRunMatchesFetchedOne() throws Exception {
        String name = cache("l-shape");
        RunHistoryEntity run = backfillService.start(name);
        backfillService.pause(run.getId());
        insertRun("l-old", "p-a", "done", null, "2020-01-01T00:00:00Z");

        mockMvc.perform(get("/v1/backfills").param("limit", "0"))
                .andExpect(jsonPath("$.runs.length()").value(1));
        mockMvc.perform(get("/v1/backfills").param("limit", "100000"))
                .andExpect(jsonPath("$.runs.length()").value(2));

        String listed = mockMvc.perform(get("/v1/backfills").param("pipeline_name", name))
                .andReturn().getResponse().getContentAsString();
        String fetched = mockMvc.perform(get("/v1/backfills/" + run.getId()))
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).isEqualTo("{\"runs\":[" + fetched + "]}");
    }

    @Test
    void theListingNeverReturnsMoreThanItsCeilingNewestFirst() throws Exception {
        // One more finished run than the ceiling, one a second apart; finished runs are not bound by the
        // one-running-per-pipeline index.
        jdbcTemplate.update("""
                INSERT INTO run_history (id, pipeline_name, kind, state, started_at, finished_at)
                SELECT 'bulk-' || g, 'p-bulk', 'backfill', 'done',
                       TIMESTAMPTZ '2026-09-01T00:00:00Z' + g * INTERVAL '1 second',
                       TIMESTAMPTZ '2026-09-01T00:00:00Z' + g * INTERVAL '1 second'
                  FROM generate_series(1, ?) AS g""", BackfillService.MAX_LIMIT + 1);

        List<RunHistoryEntity> listed = backfillService.list(null, null, null, 100_000);
        assertThat(listed).hasSize(BackfillService.MAX_LIMIT);
        assertThat(listed.getFirst().getId()).isEqualTo("bulk-" + (BackfillService.MAX_LIMIT + 1));
        assertThat(listed.getLast().getId()).isEqualTo("bulk-2");
        mockMvc.perform(get("/v1/backfills").param("limit", "100000"))
                .andExpect(jsonPath("$.runs.length()").value(BackfillService.MAX_LIMIT));
    }

    @Test
    void theListingTrimsThePipelineName() throws Exception {
        insertRun("l-trim", "p", "done", null, "2026-09-01T00:00:00Z");
        insertRun("l-other", "p-other", "done", null, "2026-09-02T00:00:00Z");

        assertThat(backfillService.list(" p ", null, null, 10)).extracting(RunHistoryEntity::getId)
                .containsExactly("l-trim");
        assertThat(backfillService.list("   ", null, null, 10)).hasSize(2);
        mockMvc.perform(get("/v1/backfills").param("pipeline_name", " p "))
                .andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].id").value("l-trim"));
    }

    @Test
    void runsStartedAtTheSameInstantAreListedInStableOrder() {
        insertRun("l-a", "p-a", "done", null, "2026-09-01T00:00:00Z");
        insertRun("l-c", "p-a", "done", null, "2026-09-01T00:00:00Z");
        insertRun("l-b", "p-a", "done", null, "2026-09-01T00:00:00Z");

        assertThat(backfillService.list(null, null, null, 10)).extracting(RunHistoryEntity::getId)
                .containsExactly("l-c", "l-b", "l-a");
    }

    // ---- 7.10 the schema's constraints ---------------------------------------------------------------------

    @Test
    void theSchemaRejectsAnUnknownReasonReasonWithoutItsInstantAndSecondRunningRun() {
        insertRun("s-1", "p-s", "running", null, "2026-09-01T00:00:00Z");

        assertThatThrownBy(() -> insertRun("s-2", "p-s", "paused", "typo", "2026-09-01T00:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("run_history_paused_reason_check");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO run_history (id, pipeline_name, kind, state, paused_reason)
                VALUES ('s-3', 'p-s', 'backfill', 'paused', 'operator')"""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("run_history_paused_reason_at_check");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO run_history (id, pipeline_name, kind, state, paused_at)
                VALUES ('s-4', 'p-s', 'backfill', 'paused', now())"""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("run_history_paused_reason_at_check");
        assertThatThrownBy(() -> insertRun("s-5", "p-s", "running", null, "2026-09-02T00:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(BackfillService.ONE_RUNNING_PER_PIPELINE_UK);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** Caches a row pipeline at generation 1, keeping any others already cached. */
    private String cache(String name, String... keep) {
        Map<String, PipelineDto> pipelines = new HashMap<>();
        for (String other : keep) {
            pipelines.put(other, pipelineCache.get(other));
        }
        pipelines.put(name, pipeline(name, 1));
        pipelineCache.replaceAll(pipelines);
        return name;
    }

    private static PipelineDto pipeline(String name, long generation) {
        return TestPipelines.builder(name).generation(generation)
                .transformInputs(TestPipelines.columnVar("prompt", "request_body")).build();
    }

    private void assertReason(String runId, BackfillPauseReason reason) {
        RunHistoryEntity run = backfillService.get(runId);
        assertThat(run.getState()).isEqualTo("paused");
        assertThat(run.getPausedReason()).isEqualTo(reason.dbValue());
        assertThat(run.getPausedAt()).isNotNull();
    }

    private Throwable catchFailure(String runId) {
        try {
            executor.run(runId);
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected the batch to fail");
    }

    private static ExecutionComplete failure(String runId, Throwable cause) {
        TaskInstance<String> instance = new TaskInstance<>(BackfillTask.TASK_NAME,
                BackfillTask.instanceId(runId, null), runId);
        Execution execution = new Execution(Instant.now(), instance);
        return ExecutionComplete.failure(execution, Instant.now(), Instant.now(), cause);
    }

    private double batchFailures(String pipelineName) {
        Counter counter = meterRegistry.find("runner.backfill.batch.failures").tag("pipeline", pipelineName).counter();
        return counter == null ? 0 : counter.count();
    }

    private void insertRun(String id, String pipelineName, String state, String reason, String startedAt) {
        jdbcTemplate.update("""
                INSERT INTO run_history (id, pipeline_name, kind, state, paused_reason, paused_at, started_at)
                VALUES (?, ?, 'backfill', ?, ?, CASE WHEN CAST(? AS TEXT) IS NULL THEN NULL ELSE now() END, ?::timestamptz)""",
                id, pipelineName, state, reason, reason, startedAt);
    }

    private long taskCount(String taskName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Long.class, taskName);
        return count == null ? 0 : count;
    }

    private static String scanPath(String name) {
        return "/v1/pipelines/" + name + "/scan";
    }

    private static void stubSinglePage(String name) {
        ADAS.stubFor(post(urlPathEqualTo(scanPath(name))).willReturn(okJson(page("e0", "c1", false))));
    }

    /** start → c1 → c2 → c3 (end), one row a page, routed by the cursor each request carries. */
    private static void stubThreePages(String name) {
        ADAS.stubFor(post(urlPathEqualTo(scanPath(name))).atPriority(5)
                .withRequestBody(notContaining("\"cursor\"")).willReturn(okJson(page("e0", "c1", true))));
        ADAS.stubFor(post(urlPathEqualTo(scanPath(name))).atPriority(5)
                .withRequestBody(containing("\"cursor\":\"c1\"")).willReturn(okJson(page("e1", "c2", true))));
        ADAS.stubFor(post(urlPathEqualTo(scanPath(name))).atPriority(5)
                .withRequestBody(containing("\"cursor\":\"c2\"")).willReturn(okJson(page("e2", "c3", false))));
    }

    private static String page(String eventId, String nextCursor, boolean hasMore) {
        return """
                {"rows":[{"event_id":"%s","request_body":"hi","_ingested_at":"2026-07-15T10:00:00Z"}],
                 "next_cursor":"%s","has_more":%s,"lag_seconds":3}
                """.formatted(eventId, nextCursor, hasMore);
    }
}
