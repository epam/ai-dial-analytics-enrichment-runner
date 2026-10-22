package com.epam.aidial.enrichment.configuration;

import com.github.kagkarlsson.scheduler.Scheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Starts and stops the db-scheduler {@link Scheduler} with the Spring context. Auto-start is
 * configurable ({@code db-scheduler.auto-start}); some tests disable it to keep reconciliation
 * assertions deterministic (scheduling still writes rows without the poll loop running).
 */
@Slf4j
@Component
public class SchedulerLifecycle implements SmartLifecycle {

    private final Scheduler scheduler;
    private final boolean autoStart;
    private volatile boolean running;

    public SchedulerLifecycle(Scheduler scheduler, DbSchedulerProperties properties) {
        this.scheduler = scheduler;
        this.autoStart = properties.autoStart();
    }

    @Override
    public void start() {
        log.info("Starting db-scheduler");
        scheduler.start();
        running = true;
    }

    @Override
    public void stop() {
        if (running) {
            log.info("Stopping db-scheduler");
            scheduler.stop();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }
}
