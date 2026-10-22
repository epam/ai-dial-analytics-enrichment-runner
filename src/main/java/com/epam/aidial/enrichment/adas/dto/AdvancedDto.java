package com.epam.aidial.enrichment.adas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A pipeline's execution knobs, as declared. Every member is optional and ADAS writes no default into the
 * projection — a default echoed back would be indistinguishable from an operator having chosen it — so an
 * absent member means "apply the runner's own configured default". The whole object is absent when the
 * declaration set none, which is why {@link PipelineDto} reads each knob through a null-safe accessor rather
 * than exposing this record to its callers.
 *
 * <p>There is no scan clock among them. ADAS retired {@code scan_every} (once {@code cadence}): a row-grain
 * pipeline fires on its {@code trigger.cron}, and a group pipeline's mark scan on the runner's
 * {@code group.scan-cadence}.
 *
 * @param rowsPerScan    upper bound on rows one scan tick may claim (was {@code batch_scan_limit})
 * @param rowsPerCall    how many rows the runner evaluates per work item (was {@code batch_chunk})
 * @param rateRpm        per-pipeline model-call ceiling per minute — moved location, not meaning
 * @param sampleFraction fraction of eligible keys to evaluate, in (0, 1] (was {@code sampling})
 */
public record AdvancedDto(
        @JsonProperty("rows_per_scan") Integer rowsPerScan,
        @JsonProperty("rows_per_call") Integer rowsPerCall,
        @JsonProperty("rate_rpm") Integer rateRpm,
        @JsonProperty("sample_fraction") Double sampleFraction) {
}
