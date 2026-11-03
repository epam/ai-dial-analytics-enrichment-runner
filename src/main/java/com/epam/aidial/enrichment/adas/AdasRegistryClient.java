package com.epam.aidial.enrichment.adas;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.PipelineListDto;
import com.epam.aidial.enrichment.configuration.AdasClientConfiguration;
import com.epam.aidial.enrichment.observability.trace.LogExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the ADAS pipeline registry. The sync feed
 * {@code GET /v1/pipelines?kind=enrich&view=compiled} returns
 * the object envelope {@link PipelineListDto} (no ETag/304); change-detection is done client-side off each
 * pipeline's {@code generation}. The runner polls the full list; {@code updatedSince} is supported for a future
 * incremental optimization but cannot report a <em>deletion</em> — a deleted pipeline is simply absent, and
 * absence is indistinguishable from "unchanged" under a watermark — so the poller passes {@code null}.
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
 * <p>Both parameters are sent explicitly. {@code kind=enrich} is load-bearing rather than an optimization: the
 * registry also holds {@code aggregate} pipelines, which ADAS executes itself and which carry none of the
 * fields this client models. {@code enabled} is deliberately <b>not</b> sent. ADAS's {@code enabled} flag is the
 * pipeline's single run control, and under {@code enabled=true} a stopped pipeline and a deleted one are both
 * simply absent — so the runner could not keep a stopped pipeline's queued work while discarding a deleted one's.
 * The unfiltered listing carries each pipeline marked with its flag, and {@code PipelineSyncService} caches a
 * disabled one as stopped and runs only the enabled ones. ADAS omits an incomplete declaration whatever its flag,
 * so a draft never reaches this client.
 *
 * <p><strong>Each entry is parsed on its own.</strong> One unmodelled enum value fails the parse of the object
 * that carries it (see {@code TriggerKind}). An <em>enabled</em> entry that does not parse still fails the whole
 * poll, which keeps the cache and every task as they were rather than reading that pipeline as deleted. A
 * <em>disabled</em> one is skipped with a WARN, once per name and generation, and so reads as absent, exactly as
 * it did while the feed was filtered to {@code enabled=true}: a pipeline nobody is running must not stop sync
 * for every pipeline that is.
 *
 * <p>The body is read as a raw string and parsed with the application {@link JsonMapper} so the
 * runner's case-insensitive enum handling applies to ADAS's lowercase enum wire values. A blank body is an
 * empty list; a body that is not the envelope fails the poll rather than reconciling to zero pipelines,
 * which would cancel every recurring task.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class AdasRegistryClient {

    private static final String ENRICH_KIND = "enrich";
    private static final String COMPILED_VIEW = "compiled";

    @Qualifier(AdasClientConfiguration.ADAS_REST_CLIENT)
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    /** The unparseable disabled entries the previous poll skipped, so each is warned about once, not every poll. */
    private volatile Set<String> skippedLastPoll = Set.of();

    /**
     * Fetches every {@code enrich} pipeline ADAS serves, enabled or not. Passing {@code null} returns all of them
     * (phase-1 default); a non-null {@code updatedSince} adds the {@code updated_since} query filter.
     */
    public List<PipelineDto> fetchPipelines(Instant updatedSince) {
        String body = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v1/pipelines");
                    uriBuilder.queryParam("kind", ENRICH_KIND);
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
        RawListing listing = jsonMapper.readValue(body, RawListing.class);
        if (listing.pipelines() == null) {
            return List.of();
        }
        List<PipelineDto> pipelines = new ArrayList<>(listing.pipelines().size());
        Set<String> skipped = new HashSet<>();
        for (JsonNode entry : listing.pipelines()) {
            try {
                pipelines.add(jsonMapper.treeToValue(entry, PipelineDto.class));
            } catch (JacksonException e) {
                if (!isDisabled(entry)) {
                    throw e;
                }
                String key = entry.path("name").asString("?") + "@" + entry.path("generation").asString("?");
                skipped.add(key);
                if (!skippedLastPoll.contains(key)) {
                    log.warn("Skipping disabled pipeline {} in the sync feed: its entry does not parse ({}); it reads as absent "
                            + "until it does", key, e.getOriginalMessage());
                }
            }
        }
        skippedLastPoll = skipped;
        return pipelines;
    }

    private static boolean isDisabled(JsonNode entry) {
        JsonNode enabled = entry.path("enabled");
        return enabled.isBoolean() && !enabled.booleanValue();
    }

    /**
     * The {@link PipelineListDto} envelope with its entries left unbound, so each is bound on its own. Still an
     * object: a bare array fails the poll here exactly as it does against the typed envelope.
     */
    private record RawListing(List<JsonNode> pipelines) {
    }
}
