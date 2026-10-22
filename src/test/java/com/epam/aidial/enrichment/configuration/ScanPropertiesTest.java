package com.epam.aidial.enrichment.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScanPropertiesTest {

    private final ScanProperties properties =
            new ScanProperties(1000, 10_000, 25, 1000, 50);

    @Test
    void unsetScanLimitFallsBackToTheDefault() {
        assertThat(properties.resolveScanLimit(null)).isEqualTo(1000);
    }

    @Test
    void declaredScanLimitIsHonoured() {
        assertThat(properties.resolveScanLimit(250)).isEqualTo(250);
    }

    @Test
    void nonPositiveScanLimitIsFlooredAtOne() {
        assertThat(properties.resolveScanLimit(0)).isEqualTo(1);
        assertThat(properties.resolveScanLimit(-5)).isEqualTo(1);
    }

    @Test
    void overLargeScanLimitIsCappedRatherThanSentAndRejected() {
        // ADAS answers an over-limit scan with a 400 instead of clamping, so a pipeline declaring a
        // rows_per_scan above ADAS's ceiling is accepted by the registry and then fails on every fire —
        // its lane would never advance at all. Same guard Class1Properties applies to the window.
        assertThat(properties.resolveScanLimit(5_000_000)).isEqualTo(10_000);
    }

    @Test
    void unsetChunkFallsBackToTheDefaultAndNonPositiveIsFlooredAtOne() {
        assertThat(properties.resolveChunk(null)).isEqualTo(25);
        assertThat(properties.resolveChunk(0)).isEqualTo(1);
    }
}
