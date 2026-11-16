package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.adas.dto.TransformDto;
import com.epam.aidial.enrichment.adas.dto.TransformPreset;
import com.epam.aidial.enrichment.adas.dto.TransformType;
import com.epam.aidial.enrichment.configuration.WorkerProperties;
import com.epam.aidial.enrichment.dial.DialClient;
import com.epam.aidial.enrichment.dial.DialRateLimiter;
import com.epam.aidial.enrichment.expression.JsonataEvaluator;
import com.epam.aidial.enrichment.observability.RunnerMetrics;
import com.epam.aidial.enrichment.observability.RunnerTracer;
import com.epam.aidial.enrichment.spend.SpendService;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The invoker's index of preset calls: complete and unambiguous at startup, and the source of each preset's spend unit. */
class TransformInvokerTest {

    private final DialClient dialClient = mock(DialClient.class);
    private final DialRequestBuilder requestBuilder = mock(DialRequestBuilder.class);
    private final JsonMapper jsonMapper = mock(JsonMapper.class);
    private final ChatCompletionCall chat = new ChatCompletionCall(dialClient, requestBuilder, jsonMapper);
    private final AppRouteCall route = new AppRouteCall(dialClient, requestBuilder, mock(JsonataEvaluator.class),
            jsonMapper);

    private TransformInvoker invoker(List<DialCall> calls) {
        return new TransformInvoker(calls, mock(DialRateLimiter.class), mock(TemplateRenderer.class),
                mock(ResponseValidator.class), mock(OutputMapper.class), mock(SpendService.class),
                mock(RunnerMetrics.class), new RunnerTracer(OpenTelemetry.noop()), mock(WorkerProperties.class),
                jsonMapper);
    }

    @Test
    void presetWithNoCallFailsAtStartup() {
        assertThatThrownBy(() -> invoker(List.of(chat)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no DIAL call implements preset DIAL_APP_ROUTE");
    }

    @Test
    void presetWithTwoCallsFailsAtStartup() {
        assertThatThrownBy(() -> invoker(List.of(chat, route, chat)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("two DIAL calls implement preset CHAT_COMPLETION");
    }

    @Test
    void eachPresetIsChargedInItsOwnUnitAndAbsentPresetIsChatCompletion() {
        TransformInvoker invoker = invoker(List.of(chat, route));

        assertThat(invoker.usageUnit(transform(TransformPreset.DIAL_APP_ROUTE))).isEqualTo(SpendService.CALLS_UNIT);
        assertThat(invoker.usageUnit(transform(TransformPreset.CHAT_COMPLETION))).isEqualTo(SpendService.TOKENS_UNIT);
        assertThat(invoker.usageUnit(transform(null))).isEqualTo(SpendService.TOKENS_UNIT);
    }

    private static TransformDto transform(TransformPreset preset) {
        return new TransformDto(TransformType.LLM, preset, "m", null, "{}", null, null, null);
    }
}
