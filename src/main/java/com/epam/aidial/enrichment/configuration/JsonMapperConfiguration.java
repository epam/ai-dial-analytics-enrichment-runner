package com.epam.aidial.enrichment.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the application's primary {@link JsonMapper}, kept in lockstep with ADAS so the runner's
 * clients bind ADAS responses without surprises.
 *
 * <p>Enum handling matters for the ADAS enrichment contract: ADAS writes enums lowercased
 * ({@code schedule}, {@code group}, {@code live}, {@code chat_completion}, …) and accepts them
 * case-insensitively. {@link MapperFeature#ACCEPT_CASE_INSENSITIVE_ENUMS} here lets the runner bind
 * those values into its own enums regardless of casing.
 *
 * <p>The Jackson 2/3 package split is intentional: Jackson 3 moved databind to
 * {@code tools.jackson.*} but kept the public annotations under {@code com.fasterxml.jackson.annotation.*}
 * (hence {@link JsonInclude}). Switching either group to the other package will not compile.
 */
@Configuration
public class JsonMapperConfiguration {

    @Bean
    @Primary
    public JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(EnumFeature.WRITE_ENUMS_TO_LOWERCASE)
                .disable(EnumFeature.READ_ENUMS_USING_TO_STRING)
                .disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING)
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .changeDefaultPropertyInclusion(incl -> incl
                        .withValueInclusion(JsonInclude.Include.NON_NULL)
                        .withContentInclusion(JsonInclude.Include.ALWAYS))
                .build();
    }
}
