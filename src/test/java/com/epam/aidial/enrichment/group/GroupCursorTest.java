package com.epam.aidial.enrichment.group;

import com.epam.aidial.enrichment.exception.InvalidGroupCursorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupCursorTest {

    @Test
    void roundTripsAtNanosecondPrecision() {
        GroupCursor cursor = new GroupCursor(GroupListOrder.NEWEST, Instant.parse("2026-10-05T10:15:30.123456789Z"), "chat-a");

        String encoded = cursor.encode();

        assertThat(encoded).doesNotContain("=", "+", "/");
        assertThat(GroupCursor.decode(encoded)).isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"team/a", "a:b", "team/a:b:c", ":", " leading space", "ключ/юникод", " ", ""})
    void roundTripsWhateverKeyEndsThePage(String key) {
        GroupCursor cursor = new GroupCursor(GroupListOrder.OLDEST, Instant.parse("1969-12-31T23:59:59.5Z"), key);

        assertThat(GroupCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage!", "djE", "", "v2:oldest:1.0:k", "v1:sideways:1.0:k", "v1:oldest:1:k",
        "v1:oldest:1.0", "v1:oldest:x.0:k", "v1:oldest:1.x:k",
        "v1:oldest:1.-1:k", "v1:oldest:1.1000000000:k", "v1:oldest:99999999999999999999.0:k",
        "v1:oldest:31556889864403199.0:k", "v1:oldest:-62135596801.0:k"})
    void refusesWhatTheListingDidNotIssue(String raw) {
        String cursor = raw.startsWith("v") ? encode(raw) : raw;

        assertThatThrownBy(() -> GroupCursor.decode(cursor)).isInstanceOf(InvalidGroupCursorException.class);
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
