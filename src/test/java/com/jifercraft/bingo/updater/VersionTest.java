package com.jifercraft.bingo.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void parsesStandardVersions() {
        Version v = Version.parse("1.2.3");
        assertEquals("1.2.3", v.toString());
        assertEquals("1.2.3", v.clean());
        assertEquals(Version.parse("1.2.3"), v);
    }

    @Test
    void ignoresLeadingVAndPrefixes() {
        assertEquals(Version.parse("1.0.0"), Version.parse("v1.0.0"));
        assertEquals(Version.parse("2.5.1"), Version.parse("V2.5.1"));
        assertEquals(Version.parse("0.2.0"), Version.parse("BingoRush-0.2.0"));
        assertEquals("0.2.0", Version.parse("BingoRush-0.2.0").clean());
        assertEquals("0.2.0", Version.parse("v0.2.0").clean());
    }

    @Test
    void comparesMajorMinorPatch() {
        assertTrue(Version.parse("2.0.0").isNewerThan(Version.parse("1.9.9")));
        assertTrue(Version.parse("1.1.0").isNewerThan(Version.parse("1.0.9")));
        assertTrue(Version.parse("1.0.1").isNewerThan(Version.parse("1.0.0")));
        assertFalse(Version.parse("1.0.0").isNewerThan(Version.parse("1.0.0")));
        assertFalse(Version.parse("0.1.0").isNewerThan(Version.parse("0.2.0")));
    }

    @Test
    void handlesDifferentSegmentLengths() {
        assertEquals(0, Version.parse("1.0").compareTo(Version.parse("1.0.0")));
        assertTrue(Version.parse("1.0.1").isNewerThan(Version.parse("1.0")));
        assertTrue(Version.parse("1.1").isNewerThan(Version.parse("1.0.5")));
    }

    @Test
    void releaseIsNewerThanPreRelease() {
        assertTrue(Version.parse("1.0.0").isNewerThan(Version.parse("1.0.0-rc1")));
        assertTrue(Version.parse("1.0.0").isNewerThan(Version.parse("1.0.0-beta.2")));
        assertTrue(Version.parse("1.0.0-beta.2").isNewerThan(Version.parse("1.0.0-beta.1")));
        assertFalse(Version.parse("1.0.0-alpha").isNewerThan(Version.parse("1.0.0")));
    }

    @Test
    void ignoresBuildMetadata() {
        assertEquals(0, Version.parse("1.0.0+build12").compareTo(Version.parse("1.0.0+build99")));
    }
}
