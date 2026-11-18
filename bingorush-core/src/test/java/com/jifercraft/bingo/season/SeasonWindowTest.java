package com.jifercraft.bingo.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeasonWindowTest {

    @Test
    void rejectsDisorderedTimestamps() {
        assertThrows(IllegalArgumentException.class, () ->
                new SeasonWindow(2000L, 1000L, 3000L, 4000L));
        assertThrows(IllegalArgumentException.class, () ->
                new SeasonWindow(1000L, 3000L, 2000L, 4000L));
        assertThrows(IllegalArgumentException.class, () ->
                new SeasonWindow(1000L, 2000L, 4000L, 3000L));
    }

    @Test
    void transitionsAtExactMillisecondBoundaries() {
        long pre = 1000L;
        long start = 2000L;
        long end = 3000L;
        long wrap = 4000L;

        SeasonWindow window = new SeasonWindow(pre, start, end, wrap);

        assertEquals(SeasonPhase.INACTIVE, window.phaseAt(999L));
        assertEquals(SeasonPhase.PRE_SEASON, window.phaseAt(1000L));
        assertEquals(SeasonPhase.PRE_SEASON, window.phaseAt(1999L));
        assertEquals(SeasonPhase.ACTIVE, window.phaseAt(2000L));
        assertTrue(window.isActive(2000L));
        assertEquals(SeasonPhase.ACTIVE, window.phaseAt(3000L));
        assertTrue(window.isActive(3000L));
        assertEquals(SeasonPhase.WRAP_UP, window.phaseAt(3001L));
        assertFalse(window.isActive(3001L));
        assertEquals(SeasonPhase.WRAP_UP, window.phaseAt(4000L));
        assertEquals(SeasonPhase.INACTIVE, window.phaseAt(4001L));
    }

    @Test
    void calculatesRemainingTimeInEachPhase() {
        SeasonWindow window = new SeasonWindow(1000L, 2000L, 3000L, 4000L);

        assertEquals(500L, window.remainingMillisInPhase(500L));
        assertEquals(300L, window.remainingMillisInPhase(1700L));
        assertEquals(600L, window.remainingMillisInPhase(2400L));
        assertEquals(200L, window.remainingMillisInPhase(3800L));
        assertEquals(0L, window.remainingMillisInPhase(4500L));
    }

    @Test
    void parsesIsoAndUtcTimestamps() {
        SeasonWindow window = SeasonWindow.parse(
                "2026-11-25T00:00:00Z",
                "2026-12-01 00:00:00",
                "2026-12-31T23:59:59Z",
                "2027-01-03 23:59:59"
        );

        assertTrue(window.preSeasonStartMillis() < window.activeStartMillis());
        assertTrue(window.activeStartMillis() < window.activeEndMillis());
        assertTrue(window.activeEndMillis() < window.wrapUpEndMillis());
    }

    @Test
    void parsesDateOnlyFormatAsUtcMidnight() {
        long millis = SeasonWindow.parseUtcTimestamp("2026-12-01");
        long explicit = SeasonWindow.parseUtcTimestamp("2026-12-01T00:00:00Z");
        assertEquals(explicit, millis);
    }
}
