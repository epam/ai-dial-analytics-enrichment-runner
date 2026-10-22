package com.epam.aidial.enrichment.web.dto.backfill;

import java.util.List;

/** Envelope for the backfill run listing (an object, not a bare array, so fields can be added later). */
public record BackfillRunListResponse(List<BackfillRunDto> runs) {
}
