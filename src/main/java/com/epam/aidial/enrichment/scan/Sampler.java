package com.epam.aidial.enrichment.scan;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic, monotone sampling: {@code keep(key) ⇔ hash64(key) mod 100 < threshold}, where the
 * threshold comes from the pipeline's {@code advanced.sample_fraction}. The hash is a portable 64-bit
 * FNV-1a over the key's UTF-8 bytes — stable across JVMs and re-scans (unlike {@link String#hashCode()}),
 * so a key's keep/drop decision never changes and raising the fraction only ever adds work.
 *
 * <p>The key is the grain key on the row lane and the group key on the group lane, so the decision is
 * always taken at the grain the work is keyed by.
 *
 * <p>The fraction is interpreted leniently: {@code null} keeps everything; a value in {@code [0,1]} is
 * treated as a fraction (×100); a value {@code >1} as a percentage. Note the intentional discontinuity at
 * 1: {@code 1.0} means 100% (keep all), whereas {@code 2} means 2% — express "one percent" as {@code 0.01},
 * never as {@code 1}. ADAS refuses anything outside {@code (0, 1]} at registration, so the {@code >1} arm
 * is unreachable through the feed and kept only so a hand-seeded cache cannot silently sample nothing.
 */
@Component
public class Sampler {

    public boolean keep(String key, Double sampleFraction) {
        double threshold = threshold(sampleFraction);
        if (threshold >= 100.0) {
            return true;
        }
        if (threshold <= 0.0) {
            return false;
        }
        long bucket = Long.remainderUnsigned(hash64(key), 100);
        return bucket < threshold;
    }

    static double threshold(Double sampleFraction) {
        if (sampleFraction == null) {
            return 100.0;
        }
        return sampleFraction <= 1.0 ? sampleFraction * 100.0 : sampleFraction;
    }

    static long hash64(String key) {
        final long fnvOffset = 0xcbf29ce484222325L;
        final long fnvPrime = 0x100000001b3L;
        long hash = fnvOffset;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= fnvPrime;
        }
        return hash;
    }
}
