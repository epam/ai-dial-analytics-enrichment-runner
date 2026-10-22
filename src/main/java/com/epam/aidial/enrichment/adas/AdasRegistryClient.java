package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.PipelineListDto;
import com.epam.aidial.enrichment.configuration.AdasClientConfiguration;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

/**
 * Reads the ADAS pipeline registry. The sync feed
 * {@code GET /v1/pipelines?kind=enrich&enabled=true&view=compiled} returns
 * the object envelope {@link PipelineListDto} (no ETag/304); change-detection is done client-side off each
 * pipeline's {@code generation}. The runner polls the full list; {@code updatedSince} is supported for a future
 * incremental optimization but cannot report a <em>deletion</em> — a deleted pipeline is simply absent, and
 * absence is indistinguishable from "unchanged" under a watermark — so the poller passes {@code null}.
 * A <em>disable</em> is now recoverable incrementally, since {@code enabled=false&updated_since=…} lists
 * exactly the pipelines switched off since the watermark; an incremental poller would need that second call
 * plus a periodic full sweep for deletions, which is why phase-1 keeps one full poll instead.
 *
 * <p><strong>{@code view=compiled} is mandatory.</strong> ADAS serves two projections of one stored pipeline,
 * differing in resolution and never in naming. The default, {@code view=source}, is the authored declaration:
 * it carries the {@code transform} but none of the five derived members — no resolved {@code response_schema},
 * no {@code grain_key}, no {@code version_column}, no resolved {@code outputs} and no resolved {@code inputs}.
 * Omitting the parameter therefore does not return a degraded feed — it returns a different, cheaper object in
 * which every pipeline is unrunnable and would be refused by the admission gate. It is a fixed part of the
 * request rather than a configurable value, because its only wrong setting disables every pipeline at once and
 * does so silently.
 *
 * <p>All three filters are sent explicitly. {@code kind=enrich} is load-bearing rather than an optimization: the
 * registry also holds {@code aggregate} pipelines, which ADAS executes itself and which carry none of the
 * fields this client models. {@code enabled=true} is sent for the reason it always was — unfiltered, that
 * endpoint is ADAS's operator listing of the whole registry and carries disabled pipelines too.
 * {@code PipelineSyncService} discards those client-side anyway, so this is not the only guard; it keeps the
 * runner from paying for pipelines it would immediately throw away, and states in the request itself which
 * set the runner means. It also bounds a parse failure: one unknown enum value fails the whole response (see
 * {@code TriggerKind}), so excluding the pipelines nobody is running keeps one of them from stopping sync
 * for every pipeline.
 *
 * <p>The body is read as a raw string and parsed with the application {@link JsonMapper} so the
 * runner's case-insensitive enum handling applies to ADAS's lowercase enum wire values. A blank body is an
 * empty list; a body that is not the envelope fails the poll rather than reconciling to zero pipelines,
 * which would cancel every recurring task.
 */
@Component
@RequiredArgsConstructor
@LogExecution
public class AdasRegistryClient {

    private static final String ENRICH_KIND = "enrich";
    private static final String COMPILED_VIEW = "compiled";

    @Qualifier(AdasClientConfiguration.ADAS_REST_CLIENT)
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    /**
     * Fetches the enabled {@code enrich} pipelines from ADAS. Passing {@code null} returns all of them
     * (phase-1 default); a non-null {@code updatedSince} adds the {@code updated_since} query filter.
     */
    public List<PipelineDto> fetchPipelines(Instant updatedSince) {
        String body = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v1/pipelines");
                    uriBuilder.queryParam("kind", ENRICH_KIND);
                    uriBuilder.queryParam("enabled", true);
                    uriBuilder.queryParam("view", COMPILED_VIEW);
                    if (updatedSince != null) {
                        uriBuilder.queryParam("updated_since", updatedSince.toString());
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(String.class);
        if (StringUtils.isBlank(body)) {
            return List.of();
        }
        PipelineListDto listing = jsonMapper.readValue(body, PipelineListDto.class);
        return listing.pipelines() == null ? List.of() : listing.pipelines();
    }
}
