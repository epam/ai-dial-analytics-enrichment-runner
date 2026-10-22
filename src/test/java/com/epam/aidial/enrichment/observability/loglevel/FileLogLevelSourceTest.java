package com.epam.aidial.enrichment.observability.loglevel;

import com.epam.aidial.enrichment.configuration.JsonMapperConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FileLogLevelSourceTest {

    @TempDir
    Path dir;

    private FileLogLevelSource source(Path file) {
        return new FileLogLevelSource(new LogLevelProperties(file, Duration.ofSeconds(10)), new JsonMapperConfiguration().jsonMapper());
    }

    @Test
    void parsesTheDmFormat() throws IOException {
        Path file = Files.writeString(dir.resolve("levels.json"), """
                {"loggerLevels": {
                  "com.epam.aidial.enrichment.class1": {"defaultLevel": "INFO", "configuredLevel": "DEBUG", "validTill": 1790000000000},
                  "org.springframework": {"configuredLevel": "warn"}
                }}""");

        LogLevelSnapshot snapshot = source(file).read().orElseThrow();

        assertThat(snapshot.parsed()).isTrue();
        assertThat(snapshot.loggerLevels()).containsEntry("com.epam.aidial.enrichment.class1", new LoggerLevelEntry("INFO", "DEBUG", 1790000000000L))
                .containsEntry("org.springframework", new LoggerLevelEntry(null, "warn", null));
    }

    @Test
    void sameBytesHashTheSameAndDifferentBytesDiffer() throws IOException {
        Path file = Files.writeString(dir.resolve("levels.json"), "{\"loggerLevels\": {}}");
        String first = source(file).read().orElseThrow().hash();
        String again = source(file).read().orElseThrow().hash();
        Files.writeString(file, "{\"loggerLevels\": {\"a\": {\"configuredLevel\": \"WARN\"}}}");
        String changed = source(file).read().orElseThrow().hash();

        assertThat(again).isEqualTo(first);
        assertThat(changed).isNotEqualTo(first);
    }

    @Test
    void missingFileReadsAsEmpty() {
        assertThat(source(dir.resolve("absent.json")).read()).isEmpty();
    }

    @Test
    void unparsableContentStillHasItsHash() throws IOException {
        Path file = Files.writeString(dir.resolve("levels.json"), "{not json");

        LogLevelSnapshot snapshot = source(file).read().orElseThrow();

        assertThat(snapshot.parsed()).isFalse();
        assertThat(snapshot.hash()).isNotBlank();
        assertThat(snapshot.loggerLevels()).isNull();
    }

    @Test
    void documentWithoutLoggerLevelsIsEmpty() throws IOException {
        Path file = Files.writeString(dir.resolve("levels.json"), "{}");

        assertThat(source(file).read().orElseThrow().loggerLevels()).isEmpty();
    }
}
