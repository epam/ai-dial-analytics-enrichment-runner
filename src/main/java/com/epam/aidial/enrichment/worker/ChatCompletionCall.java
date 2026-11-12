package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.adas.dto.TransformDto;
import com.epam.aidial.enrichment.adas.dto.TransformPreset;
import com.epam.aidial.enrichment.dial.DialClient;
import com.epam.aidial.enrichment.dial.dto.ChatResponse;
import com.epam.aidial.enrichment.exception.ErrorMessages;
import com.epam.aidial.enrichment.spend.SpendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * The {@code chat_completion} preset: the body carries {@code response_format = json_schema}, the call goes to the
 * deployment's chat-completions endpoint, the output is the first choice's message content, a mismatch is repaired
 * with a corrective message, and usage is counted in tokens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ChatCompletionCall implements DialCall {

    private final DialClient dialClient;
    private final DialRequestBuilder requestBuilder;
    private final JsonMapper jsonMapper;

    @Override
    public TransformPreset preset() {
        return TransformPreset.CHAT_COMPLETION;
    }

    @Override
    public String body(String renderedTemplate, TransformDto transform, Map<String, Object> responseSchema) {
        return requestBuilder.build(renderedTemplate, transform.params(), responseSchema);
    }

    @Override
    public String call(TransformDto transform, String body) {
        return dialClient.chatCompletion(transform.model(), body);
    }

    @Override
    public Reply read(PipelineDto pipeline, TransformDto transform, String raw) {
        ChatResponse response = parse(pipeline, raw);
        String content = response != null ? response.firstContent() : null;
        return new Reply(content, content, tokens(response), SpendService.TOKENS_UNIT);
    }

    @Override
    public boolean repairs() {
        return true;
    }

    @Override
    public String withCorrection(String body, List<String> errors) {
        return requestBuilder.withCorrectiveMessage(body, errors);
    }

    private ChatResponse parse(PipelineDto pipeline, String raw) {
        try {
            return jsonMapper.readValue(raw, ChatResponse.class);
        } catch (RuntimeException e) {
            // A malformed chat envelope is treated as "no content" so the repair/validate gate handles it. Logged by
            // its classified type only: the parser's message quotes the envelope, which carries model output.
            log.warn("Malformed DIAL chat envelope for pipeline {}; treating it as no content: {}", pipeline.name(),
                    ErrorMessages.classify(e));
            return null;
        }
    }

    private static long tokens(ChatResponse response) {
        if (response == null || response.usage() == null || response.usage().totalTokens() == null) {
            return 0;
        }
        return response.usage().totalTokens();
    }
}
