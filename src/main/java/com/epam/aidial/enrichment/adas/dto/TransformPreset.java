package com.epam.aidial.enrichment.adas.dto;

/**
 * How an {@code llm} transform calls its DIAL deployment, mirroring ADAS's {@code transform.preset}: through the
 * deployment's chat-completions endpoint, or through one of an application's custom routes
 * ({@code /v1/deployments/{id}/route/{route}}).
 */
public enum TransformPreset {
    CHAT_COMPLETION,
    DIAL_APP_ROUTE
}
