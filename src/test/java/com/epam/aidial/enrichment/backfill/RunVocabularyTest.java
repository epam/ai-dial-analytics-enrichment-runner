package com.epam.aidial.enrichment.backfill;

import com.epam.aidial.enrichment.dlq.DlqLane;
import com.epam.aidial.enrichment.exception.UnknownEnumValueException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The run's transition table and the lenient spellings the listings accept. */
class RunVocabularyTest {

    @Test
    void pauseAdmitsOnlyRunning() {
        assertThat(RunState.RUNNING.canPause()).isTrue();
        assertThat(RunState.PAUSED.canPause()).isFalse();
        assertThat(RunState.DONE.canPause()).isFalse();
        assertThat(RunState.CANCELLED.canPause()).isFalse();
    }

    @Test
    void resumeAdmitsOnlyPaused() {
        assertThat(RunState.PAUSED.canResume()).isTrue();
        assertThat(RunState.RUNNING.canResume()).isFalse();
        assertThat(RunState.DONE.canResume()).isFalse();
        assertThat(RunState.CANCELLED.canResume()).isFalse();
    }

    @Test
    void cancelAdmitsRunningAndPausedButNeverFinishedRun() {
        assertThat(RunState.RUNNING.canCancel()).isTrue();
        assertThat(RunState.PAUSED.canCancel()).isTrue();
        assertThat(RunState.DONE.canCancel()).isFalse();
        assertThat(RunState.CANCELLED.canCancel()).isFalse();
    }

    @Test
    void theSpellingBodyReportsParsesInAnyCase() {
        assertThat(RunState.fromDbValue("running")).isEqualTo(RunState.RUNNING);
        assertThat(RunState.fromDbValue("RUNNING")).isEqualTo(RunState.RUNNING);
        assertThat(BackfillPauseReason.fromDbValue("Cursor_Stalled")).isEqualTo(BackfillPauseReason.CURSOR_STALLED);
        assertThat(DlqLane.fromDbValue("LIVE")).isEqualTo(DlqLane.LIVE);
    }

    @Test
    void anUnknownValueNamesThePermittedOnes() {
        assertThatThrownBy(() -> RunState.fromDbValue("runing"))
                .isInstanceOf(UnknownEnumValueException.class)
                .satisfies(e -> assertThat(((UnknownEnumValueException) e).getPermitted())
                        .containsExactly("running", "paused", "done", "cancelled"));
        assertThatThrownBy(() -> BackfillPauseReason.fromDbValue(null))
                .isInstanceOf(UnknownEnumValueException.class)
                .hasMessageContaining("pipeline_changed");
    }

    /** The database constrains the column to exactly this set; a drift between the two is a migration. */
    @Test
    void pauseReasonsAreTheSevenTheSchemaAdmits() {
        assertThat(Arrays.stream(BackfillPauseReason.values()).map(BackfillPauseReason::dbValue))
                .containsExactly("operator", "pipeline_paused_operator", "pipeline_paused_breaker",
                        "spend_budget_exhausted", "cursor_stalled", "pipeline_absent", "pipeline_changed");
    }

    @Test
    void resumeInstanceIsFreshAndContinuationDedupsAgainstItself() {
        String continuation = BackfillTask.instanceId("run-1", "c1");
        assertThat(BackfillTask.instanceId("run-1", "c1")).isEqualTo(continuation);
        String resume = BackfillTask.resumeInstanceId("run-1", "c1", Instant.ofEpochMilli(42));
        assertThat(resume).startsWith(continuation).endsWith("#r42").isNotEqualTo(continuation);
    }
}
