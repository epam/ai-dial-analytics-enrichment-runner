package com.epam.aidial.enrichment.web.dto.dlq;

/** Result of a DLQ requeue operation. */
public record RequeueResponse(int requeued) {
}
