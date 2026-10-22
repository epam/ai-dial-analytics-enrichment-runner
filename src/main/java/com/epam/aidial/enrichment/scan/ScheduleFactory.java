package com.epam.aidial.enrichment.scan;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.github.kagkarlsson.scheduler.task.schedule.CronSchedule;
import com.github.kagkarlsson.scheduler.task.schedule.CronStyle;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Builds the db-scheduler {@link Schedule} for a pipeline from its trigger. There is one clock per lane: a
 * {@code schedule} pipeline runs on its cron expression (Spring 6-field style, evaluated in UTC), on the
 * {@code pipeline-scan} and {@code class1-materialize} lanes alike, and a {@code group} pipeline's mark scan runs on a
 * fixed delay of the runner's {@code group.scan-cadence}.
 *
 * <p>There is no per-pipeline cadence and no lane default. ADAS retired {@code on_ingest} and
 * {@code advanced.scan_every}, and stores an explicit cron on every row-grain pipeline, so a fallback here would only
 * hide a producer bug. Any other trigger throws; the admission gate refuses those entries first, so the throw is a
 * backstop rather than a path.
 */
@Component
@RequiredArgsConstructor
public class ScheduleFactory {

    private final GroupProperties groupProperties;

    public Schedule forPipeline(PipelineDto pipeline) {
        return switch (pipeline.triggerKind()) {
            case SCHEDULE -> cronSchedule(cron(pipeline));
            case GROUP -> Schedules.fixedDelay(groupProperties.scanCadence());
            case ON_INGEST -> throw unschedulable(pipeline);
            case null -> throw unschedulable(pipeline);
        };
    }

    /**
     * The db-scheduler schedule for a cron expression: Spring 6-field style, evaluated in UTC. The admission gate builds
     * it too, to refuse a cron this parser rejects, so the two can never disagree about what is runnable.
     */
    public static CronSchedule cronSchedule(String cron) {
        return new CronSchedule(cron, ZoneOffset.UTC, CronStyle.SPRING53);
    }

    /**
     * How often the pipeline fires: for a cron trigger the gap between its next two fires after {@code now}, for a
     * group pipeline the configured mark-scan cadence. The status heartbeat measures a stall in multiples of it.
     * Throws where {@link #forPipeline} throws, so a pipeline that is never scheduled reports no cadence rather than
     * an invented one.
     */
    public Duration cadence(PipelineDto pipeline, Instant now) {
        return switch (pipeline.triggerKind()) {
            case SCHEDULE -> cronGap(pipeline, now);
            case GROUP -> groupProperties.scanCadence();
            case ON_INGEST -> throw unschedulable(pipeline);
            case null -> throw unschedulable(pipeline);
        };
    }

    /** The gap between the cron's next two fires after {@code now}, or null when it never fires twice. */
    private static Duration cronGap(PipelineDto pipeline, Instant now) {
        CronExpression cron = CronExpression.parse(cron(pipeline));
        ZonedDateTime first = cron.next(now.atZone(ZoneOffset.UTC));
        ZonedDateTime second = first == null ? null : cron.next(first);
        return second == null ? null : Duration.between(first, second);
    }

    private static String cron(PipelineDto pipeline) {
        if (StringUtils.isBlank(pipeline.triggerCron())) {
            throw new IllegalArgumentException("schedule pipeline " + pipeline.name() + " declares no trigger.cron");
        }
        return pipeline.triggerCron();
    }

    private static IllegalArgumentException unschedulable(PipelineDto pipeline) {
        return new IllegalArgumentException("pipeline " + pipeline.name() + " has trigger kind "
                + pipeline.triggerKind() + ", which has no schedule");
    }
}
