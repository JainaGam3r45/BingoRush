package com.jifercraft.bingo.season;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class SeasonSettingsTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final SeasonCalendar CHRISTMAS =
            SeasonCalendar.parse("11-25", "12-01", "12-31", "01-03", "Europe/Madrid");

    private static SeasonSettings withHighlight(MonthDay highlight) {
        return new SeasonSettings(true, CHRISTMAS, false, false, false, 0, null,
                new SeasonSettings.Lobby(false, false, false, true, highlight, false));
    }

    private static long at(int year, int month, int day, int hour) {
        return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, MADRID).toInstant().toEpochMilli();
    }

    @Test
    void countsDownToChristmas() {
        SeasonSettings settings = withHighlight(MonthDay.of(12, 25));
        assertEquals(30, settings.daysToHighlight(at(2026, 11, 25, 9)));
        assertEquals(1, settings.daysToHighlight(at(2026, 12, 24, 23)));
        assertEquals(0, settings.daysToHighlight(at(2026, 12, 25, 0)));
    }

    @Test
    void stopsOnceTheDayIsBehind() {
        SeasonSettings settings = withHighlight(MonthDay.of(12, 25));
        assertEquals(-1, settings.daysToHighlight(at(2026, 12, 26, 12)));
        assertEquals(-1, settings.daysToHighlight(at(2027, 1, 2, 12)));
    }

    @Test
    void aHighlightAfterNewYearIsStillAhead() {
        SeasonSettings settings = withHighlight(MonthDay.of(1, 1));
        assertEquals(6, settings.daysToHighlight(at(2026, 12, 26, 12)));
    }

    @Test
    void noHighlightMeansNoCountdown() {
        assertEquals(-1, withHighlight(null).daysToHighlight(at(2026, 12, 20, 12)));
    }
}
