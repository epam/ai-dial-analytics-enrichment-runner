package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseValidatorTest {

    private final ResponseValidator validator = new ResponseValidator(new JsonMapperConfiguration().jsonMapper());

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "required", List.of("label"),
            "properties", Map.of("label", Map.of("type", "string")));

    @Test
    void validContentPasses() {
        assertThat(validator.validate(SCHEMA, "{\"label\":\"spam\"}")).isEmpty();
    }

    @Test
    void contentViolatingTheSchemaReturnsErrors() {
        assertThat(validator.validate(SCHEMA, "{\"other\":1}")).isNotEmpty();
    }

    @Test
    void nullSchemaValidatesNothingAboutTheShape() {
        assertThat(validator.validate(null, "{\"whatever\":[1,2,3]}")).isEmpty();
    }

    /**
     * An entry with no response schema still has its reply parsed for output mapping, so a reply that is
     * not JSON is rejected here rather than thrown out of the mapper — where it would dead-letter at
     * `output-map`, with no raw response and without the repair retry ever running.
     */
    @Test
    void nullSchemaStillRequiresJson() {
        assertThat(validator.validate(null, "I'm afraid I can't help with that")).isNotEmpty();
    }

    @Test
    void nullContentReturnsAnError() {
        assertThat(validator.validate(SCHEMA, null)).isNotEmpty();
    }

    /**
     * The library answers an unparseable string by throwing rather than by returning a verdict. Reported as a
     * validation error instead, because a throw here skips the caller's repair retry and dead-letters the unit
     * at a stage that has nothing to do with validation.
     */
    @Test
    void contentThatIsNotJsonIsReportedRatherThanThrown() {
        assertThat(validator.validate(SCHEMA, "not json at all")).isNotEmpty();
    }

    /**
     * The shape a model truncated by {@code max_tokens} actually produces: well-formed as far as it goes, then
     * cut off with the object still open.
     */
    @Test
    void truncatedJsonIsReportedRatherThanThrown() {
        assertThat(validator.validate(SCHEMA, "{\"label\": \"spam\"          ")).isNotEmpty();
    }

    @Test
    void emptyContentIsReportedRatherThanThrown() {
        assertThat(validator.validate(SCHEMA, "")).isNotEmpty();
    }

    /**
     * The compiled-schema cache must not change any verdict: the same schema instance is validated against
     * twice, once populating the cache and once reading it.
     */
    @Test
    void repeatedValidationAgainstOneSchemaKeepsItsVerdicts() {
        assertThat(validator.validate(SCHEMA, "{\"label\":\"spam\"}")).isEmpty();
        assertThat(validator.validate(SCHEMA, "{\"label\":\"spam\"}")).isEmpty();
        assertThat(validator.validate(SCHEMA, "{\"label\":7}")).isNotEmpty();
        assertThat(validator.validate(SCHEMA, "{\"label\":\"ham\"}")).isEmpty();
    }
}
