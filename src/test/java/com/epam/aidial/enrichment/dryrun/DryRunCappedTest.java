package com.epam.aidial.enrichment.dryrun;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DryRunCappedTest {

    @Test
    void absentTextIsAbsent() {
        assertThat(DryRunService.Capped.of(null)).isEqualTo(new DryRunService.Capped(null, null));
    }

    @Test
    void textWithinTheCapIsReturnedWhole() {
        String text = "a".repeat(DryRunService.RETURNED_BYTES);

        assertThat(DryRunService.Capped.of(text)).isEqualTo(new DryRunService.Capped(text, false));
    }

    @Test
    void multiByteTextIsCutAtCodePointWithinTheCap() {
        // A three-byte character and a four-byte one (a surrogate pair), so the cap falls inside a character.
        String text = "€😀".repeat(DryRunService.RETURNED_BYTES / 7 + 1);

        DryRunService.Capped capped = DryRunService.Capped.of(text);

        assertThat(capped.flag()).isTrue();
        int bytes = capped.text().getBytes(StandardCharsets.UTF_8).length;
        assertThat(bytes).isLessThanOrEqualTo(DryRunService.RETURNED_BYTES).isGreaterThan(DryRunService.RETURNED_BYTES - 4);
        assertThat(Character.isHighSurrogate(capped.text().charAt(capped.text().length() - 1))).isFalse();
        assertThat(text).startsWith(capped.text());
    }
}
