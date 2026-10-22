package com.epam.aidial.enrichment.adas.dto;

import java.util.List;
import java.util.Map;

/**
 * Response of group assembly: the group's member rows in the source's total order, and whether ADAS's row
 * or response-byte cap trimmed them.
 *
 * <p>There is <strong>no pagination</strong> — when {@code truncated} is true the remainder is unreachable,
 * which is why truncation must be recorded on the enrichment row rather than worked around by fetching
 * again. An unknown or fully filtered-out group is a {@code 200} with no rows, not an error.
 *
 * @param rows      the group's member rows (projected columns only), oldest first
 * @param truncated whether either of ADAS's caps trimmed the group
 */
public record GroupFetchResponse(
        List<Map<String, Object>> rows,
        boolean truncated) {
}
