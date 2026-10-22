package com.epam.aidial.enrichment.dlq;

import com.epam.aidial.enrichment.exception.InvalidDlqCursorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DlqCursorTest {

    @Test
    void roundTripsAtNanosecondPrecision() {
        DlqCursor cursor = new DlqCursor(Instant.parse("2026-10-02T10:15:30.123456789Z"), 42L);

        String encoded = cursor.encode();

        assertThat(encoded).doesNotContain("=", "+", "/");
        assertThat(DlqCursor.decode(encoded)).isEqualTo(cursor);
    }

    @Test
    void roundTripsBeforeTheEpoch() {
        DlqCursor cursor = new DlqCursor(Instant.parse("1969-12-31T23:59:59.5Z"), 1L);

        assertThat(DlqCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage!", "djE", "", "v2:1.0:1", "v1:1:1", "v1:1.0", "v1:x.0:1", "v1:1.0:x",
        "v1:1.0:1:extra", "v1:99999999999999999999.0:1", "v1:1.-1:1", "v1:1.1000000000:1",
        "v1:31556889864403199.0:1", "v1:-62135596801.0:1", "v1:1.0:0", "v1:1.0:-5"})
    void refusesWhatTheListingDidNotIssue(String raw) {
        String cursor = raw.startsWith("v") ? encode(raw) : raw;

        assertThatThrownBy(() -> DlqCursor.decode(cursor)).isInstanceOf(InvalidDlqCursorException.class);
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
