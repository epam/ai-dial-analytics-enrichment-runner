package com.epam.aidial.enrichment.observability.loglevel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogLevelPropertiesTest {

    private static LogLevelProperties bind(String interval) {
        MapConfigurationPropertySource properties = new MapConfigurationPropertySource(Map.of(
                "observability.log-levels.path", "/app/log-config/logging.levels.json",
                "observability.log-levels.interval", interval));
        return new Binder(properties).bind("observability.log-levels", LogLevelProperties.class).get();
    }

    @Test
    void bindsDurationStrings() {
        LogLevelProperties properties = bind("10s");

        assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.path()).isEqualTo(Path.of("/app/log-config/logging.levels.json"));
    }

    @Test
    void oneSecondIsTheMinimum() {
        assertThat(bind("1s").interval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void intervalBelowOneSecondIsRejected() {
        assertThatThrownBy(() -> bind("500ms")).isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("at least 1s");
    }

    @Test
    void bareNumberIsMillisecondsAndSoRejected() {
        assertThatThrownBy(() -> bind("10")).isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("at least 1s");
    }
}
