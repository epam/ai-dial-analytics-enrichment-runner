package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.configuration.DlqProperties;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/** Deletes dead-lettered items past their retention window. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqSweeper {

    private final DlqItemRepository dlqItemRepository;
    private final DlqProperties dlqProperties;

    @Scheduled(cron = "${dlq.sweep-cron:0 0 3 * * *}")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(dlqProperties.retentionDays()));
        long deleted = dlqItemRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("DLQ TTL sweep deleted {} items older than {} days", deleted, dlqProperties.retentionDays());
        }
    }
}
