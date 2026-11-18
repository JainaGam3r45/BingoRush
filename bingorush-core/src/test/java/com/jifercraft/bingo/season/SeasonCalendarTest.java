package com.jifercraft.bingo.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class SeasonCalendarTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private final SeasonCalendar christmas = SeasonCalendar.parse("11-25", "12-01", "12-31", "01-03", "Europe/Madrid");

    private static long at(int year, int month, int day, int hour) {
        return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, MADRID).toInstant().toEpochMilli();
    }

    @Test
    void comesBackEveryYear() {
        assertTrue(christmas.recurring());
        assertEquals(SeasonPhase.ACTIVE, christmas.windowAt(at(2026, 12, 20, 12)).phaseAt(at(2026, 12, 20, 12)));
        assertEquals(SeasonPhase.ACTIVE, christmas.windowAt(at(2031, 12, 20, 12)).phaseAt(at(2031, 12, 20, 12)));
    }

    @Test
    void theWrapUpCrossesNewYear() {
        long jan2 = at(2027, 1, 2, 12);
        assertEquals(SeasonPhase.WRAP_UP, christmas.windowAt(jan2).phaseAt(jan2));
        long jan5 = at(2027, 1, 5, 12);
        SeasonWindow next = christmas.windowAt(jan5);
        assertEquals(SeasonPhase.INACTIVE, next.phaseAt(jan5));
        assertEquals(at(2027, 11, 25, 0), next.preSeasonStartMillis());
    }

    @Test
    void bareEndDatesLastTheWholeDay() {
        long lateOnTheThirtyFirst = ZonedDateTime.of(2026, 12, 31, 23, 30, 0, 0, MADRID).toInstant().toEpochMilli();
        assertEquals(SeasonPhase.ACTIVE, christmas.windowAt(lateOnTheThirtyFirst).phaseAt(lateOnTheThirtyFirst));
    }

    @Test
    void fullTimestampsStayAOneOffSeason() {
        SeasonCalendar once = SeasonCalendar.parse("2026-11-25T00:00:00Z", "2026-12-01T00:00:00Z",
                "2026-12-31T23:59:59Z", "2027-01-03T23:59:59Z", "UTC");
        assertFalse(once.recurring());
        assertEquals(SeasonPhase.INACTIVE, once.windowAt(at(2027, 12, 20, 12)).phaseAt(at(2027, 12, 20, 12)));
    }

    @Test
    void mistakesSayWhatIsWrong() {
        assertThrows(IllegalArgumentException.class,
                () -> SeasonCalendar.parse("11-25", "2026-12-01", "12-31", "01-03", "UTC"));
        assertThrows(IllegalArgumentException.class,
                () -> SeasonCalendar.parse("11-25", "13-01", "12-31", "01-03", "UTC"));
        assertThrows(IllegalArgumentException.class,
                () -> SeasonCalendar.parse("11-25", "12-01", "12-31", "01-03", "Mars/Olympus"));
    }
}
