package com.tailor.transcritorata.deps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    @Test
    void reportsNewerPatchVersion() {
        assertTrue(UpdateChecker.isNewer("1.0.8", "1.0.7"));
    }

    @Test
    void reportsSameVersionAsNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.0.7", "1.0.7"));
    }

    @Test
    void reportsOlderVersionAsNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.0.6", "1.0.7"));
    }

    @Test
    void comparesNumericallyNotLexicographically() {
        // A naive string comparison would say "1.0.9" > "1.0.10" -- must not.
        assertTrue(UpdateChecker.isNewer("1.0.10", "1.0.9"));
        assertFalse(UpdateChecker.isNewer("1.0.9", "1.0.10"));
    }

    @Test
    void handlesDifferentSegmentCounts() {
        assertTrue(UpdateChecker.isNewer("1.1", "1.0.9"));
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.1"));
    }

    @Test
    void toleratesNonNumericSuffix() {
        assertTrue(UpdateChecker.isNewer("1.0.8-beta", "1.0.7"));
    }
}
