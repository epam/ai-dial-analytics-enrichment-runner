package com.epam.aidial.enrichment.runtime;

import com.epam.aidial.enrichment.configuration.GroupProperties;
import com.epam.aidial.enrichment.exception.PipelineCacheNotReadyException;
import com.epam.aidial.enrichment.exception.PipelineNotFoundException;
import com.epam.aidial.enrichment.observability.status.PipelineActivity;
import com.epam.aidial.enrichment.observability.status.SchedulerQueueReader;
import com.epam.aidial.enrichment.persistence.DlqItemRepository;
import com.epam.aidial.enrichment.persistence.GroupStateWriter;
import com.epam.aidial.enrichment.persistence.PipelineCursorRepository;
import com.epam.aidial.enrichment.scan.InflightCounter;
import com.epam.aidial.enrichment.spend.SpendService;
import com.epam.aidial.enrichment.sync.PipelineCache;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Before the first sync the runner cannot say whether a pipeline exists, so it must not answer "not found". */
class PipelineRuntimeServiceTest {

    private final PipelineCache pipelineCache = new PipelineCache();
    private final PipelineCursorRepository cursorRepository = mock(PipelineCursorRepository.class);

    private final PipelineRuntimeService service = new PipelineRuntimeService(Clock.systemUTC(), pipelineCache,
            mock(PipelineStatusResolver.class), mock(PipelineRunOutcome.class), mock(PipelineActivity.class),
            cursorRepository, mock(SchedulerQueueReader.class), mock(InflightCounter.class),
            mock(GroupStateWriter.class), mock(SpendService.class), mock(DlqItemRepository.class),
            mock(GroupProperties.class));

    @Test
    void coldCacheIsNotReadyRatherThanNotFound() {
        assertThatThrownBy(() -> service.runtime("labels")).isInstanceOf(PipelineCacheNotReadyException.class);
        verifyNoInteractions(cursorRepository);
    }

    @Test
    void pipelineMissingFromSyncedCacheIsNotFound() {
        pipelineCache.replaceAll(Map.of());

        assertThatThrownBy(() -> service.runtime("labels")).isInstanceOf(PipelineNotFoundException.class);
        verifyNoInteractions(cursorRepository);
    }
}
