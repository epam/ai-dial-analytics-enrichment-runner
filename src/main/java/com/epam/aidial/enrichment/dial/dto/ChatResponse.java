package com.epam.aidial.enrichment.dial.dto;

import java.util.List;

/**
 * Minimal view of a DIAL Core {@code chat/completions} response. With {@code response_format =
 * json_schema}, the model's structured output is the first choice's message content (a JSON string).
 */
public record ChatResponse(List<Choice> choices, Usage usage) {

    /** The structured JSON content of the first choice, or {@code null} if absent. */
    public String firstContent() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Choice choice = choices.getFirst();
        return choice != null && choice.message() != null ? choice.message().content() : null;
    }
}
