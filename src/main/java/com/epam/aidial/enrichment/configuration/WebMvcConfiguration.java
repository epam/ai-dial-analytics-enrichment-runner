package com.epam.aidial.enrichment.configuration;

import com.epam.aidial.enrichment.backfill.BackfillPauseReason;
import com.epam.aidial.enrichment.backfill.RunState;
import com.epam.aidial.enrichment.dlq.DlqLane;
import com.epam.aidial.enrichment.group.GroupListOrder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.function.Function;

/**
 * Case-insensitive binding of the closed vocabularies the listings filter on, in the lowercase spelling the
 * response bodies report.
 *
 * <p>Spring binds a {@code @RequestParam} enum with {@code Enum.valueOf}, which is case-sensitive, and Jackson's
 * {@code ACCEPT_CASE_INSENSITIVE_ENUMS} does not apply to query parameters. Without these converters the value an
 * operator copies from a run body — {@code "state": "running"} — would be a {@code 400}. A value outside the set
 * still fails conversion, which surfaces as {@code MethodArgumentTypeMismatchException} and a {@code 400} naming
 * the permitted values; a blank value binds as absent.
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, RunState.class, lenient(RunState::fromDbValue));
        registry.addConverter(String.class, BackfillPauseReason.class, lenient(BackfillPauseReason::fromDbValue));
        registry.addConverter(String.class, DlqLane.class, lenient(DlqLane::fromDbValue));
        registry.addConverter(String.class, GroupListOrder.class, lenient(GroupListOrder::fromWireValue));
    }

    private static <E> Converter<String, E> lenient(Function<String, E> parse) {
        return value -> value.isBlank() ? null : parse.apply(value.trim());
    }
}
