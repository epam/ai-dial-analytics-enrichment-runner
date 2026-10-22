package com.epam.aidial.enrichment.sweep;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.exception.LogSafe;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.sync.PipelineCache;
import com.epam.aidial.enrichment.sync.PipelineRouting;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Bounds {@code group_state} by deleting rows for groups that have been quiet long past any readiness pipeline
 * that could still fire them.
 *
 * <p>Without this the table grows with the source's group-key cardinality forever, since a closed
 * conversation is never revisited. The cutoff is the pipeline's longer readiness window plus a configured
 * margin, so eviction can never race a {@code max_staleness} pipeline that would still have re-fired.
 *
 * <p>A <em>dirty</em> group is never evicted — that would silently discard a pending evaluation. Evicting a
 * clean one is safe: if a member arrives later the row is re-created, and its version will exceed the
 * {@code computed_version} that was discarded, so the group re-fires exactly as a change should.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupStateEvictor {

    private final PipelineCache pipelineCache;
    private final GroupStateWriter groupStateWriter;
    private final GroupProperties groupProperties;
    private final Clock clock;

    @Scheduled(cron = "${group.eviction-cron:0 30 3 * * *}")
    public void evict() {
        Instant now = clock.instant();
        int evicted = 0;
        for (PipelineDto pipeline : pipelineCache.getAll()) {
            if (!PipelineRouting.isGroup(pipeline)) {
                continue;
            }
            try {
                Duration retention = PipelineRouting.retention(pipeline, groupProperties.defaultIdle())
                        .plus(groupProperties.evictionMargin());
                evicted += groupStateWriter.evictQuiet(pipeline.name(), now.minus(retention));
            } catch (RuntimeException e) {
                log.warn("Group state eviction failed for pipeline {}: {}", pipeline.name(), ErrorMessages.classify(e),
                        LogSafe.loggable(e));
            }
        }
        if (evicted > 0) {
            log.info("Group state eviction removed {} quiet group(s)", evicted);
        }
    }
}
