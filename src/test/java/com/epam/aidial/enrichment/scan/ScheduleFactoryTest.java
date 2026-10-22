package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.adas.dto.TriggerKind;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.support.TestPipelines;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.CronStyle;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.epam.aidial.enrichment.support.TestPipelines.class1;
import static com.epam.aidial.enrichment.support.TestPipelines.group;
import static com.epam.aidial.enrichment.support.TestPipelines.schedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleFactoryTest {

    private static final Duration GROUP_SCAN_CADENCE = Duration.ofSeconds(75);
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    private final ScheduleFactory factory = new ScheduleFactory(
            new GroupProperties(500, 0, Duration.ofDays(7), Duration.ofMinutes(10), 200, 60_000, GROUP_SCAN_CADENCE));

    @Test
    void scheduleUsesCronExpression() {
        Schedule schedule = factory.forPipeline(schedule("r2", 1, "0 0 3 * * *"));
        assertThat(schedule).isEqualTo(new CronSchedule("0 0 3 * * *", ZoneOffset.UTC, CronStyle.SPRING53));
    }

    /** One clock for the row grain, on both task names: a Class-1 pipeline runs on its cron too. */
    @Test
    void class1UsesItsCronExpression() {
        Schedule schedule = factory.forPipeline(class1("r3", 1, "23 * * * * *"));
        assertThat(schedule).isEqualTo(new CronSchedule("23 * * * * *", ZoneOffset.UTC, CronStyle.SPRING53));
    }

    /** The mark scan's clock is the runner's, whatever the pipeline's readiness window says. */
    @Test
    void groupUsesTheConfiguredScanCadence() {
        Schedule schedule = factory.forPipeline(group("g1", 1, "session_id", "30m"));
        assertThat(schedule).isEqualTo(FixedDelay.of(GROUP_SCAN_CADENCE));
    }

    /** A schedule pipeline without a cron is never scheduled, so it has no cadence rather than an invented one either. */
    @Test
    void scheduleWithoutCronIsRejected() {
        var noCron = schedule("r2", 1, " ");
        assertThatThrownBy(() -> factory.forPipeline(noCron)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.cadence(noCron, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    /** No fallback to a fixed delay: the admission gate refuses the retired kind, and this is the backstop. */
    @Test
    void retiredOnIngestHasNoSchedule() {
        var onIngest = TestPipelines.builder("r1").triggerKind(TriggerKind.ON_INGEST).triggerCron(null).build();
        assertThatThrownBy(() -> factory.forPipeline(onIngest)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.cadence(onIngest, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    /** An entry with no trigger at all has no clock either; the admission gate refuses it first. */
    @Test
    void pipelineWithoutTriggerHasNoSchedule() {
        var noTrigger = TestPipelines.builder("r1").triggerKind(null).build();
        assertThatThrownBy(() -> factory.forPipeline(noTrigger)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.cadence(noTrigger, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cadenceOfScheduleIsTheGapBetweenItsNextTwoFires() {
        assertThat(factory.cadence(schedule("r2", 1, "0 0 3 * * *"), NOW)).isEqualTo(Duration.ofDays(1));
        assertThat(factory.cadence(class1("r3", 1, "23 * * * * *"), NOW)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void cadenceOfGroupIsTheConfiguredScanCadence() {
        assertThat(factory.cadence(group("g1", 1, "session_id", "30m"), NOW)).isEqualTo(GROUP_SCAN_CADENCE);
    }

    /**
     * Reconcile reschedules when the stored schedule differs from the derived one, so equality must hold for a
     * schedule built twice from the same declaration — otherwise every poll would reschedule every pipeline.
     */
    @Test
    void derivedSchedulesAreEqualAcrossCalls() {
        assertThat(factory.forPipeline(schedule("r2", 1, "0 0 3 * * *")))
                .isEqualTo(factory.forPipeline(schedule("r2", 2, "0 0 3 * * *")));
        assertThat(factory.forPipeline(group("g1", 1, "session_id", "30m")))
                .isEqualTo(factory.forPipeline(group("g1", 2, "session_id", "10m")));
    }
}
