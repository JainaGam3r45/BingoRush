package com.jifercraft.bingo.season;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The season dates from config.yml. Full timestamps ("2026-12-01T00:00:00Z")
 * make a one-off season. Month and day ("12-01", "12-31 23:59") make one
 * that comes back every year, read in the configured zone, so a Christmas
 * season set up once keeps working next December without touching the file.
 */
public final class SeasonCalendar {

    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})-(\\d{1,2})(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?");

    private final String[] dates;
    private final ZoneId zone;
    private final SeasonWindow fixed;

    private SeasonCalendar(String[] dates, ZoneId zone, SeasonWindow fixed) {
        this.dates = dates;
        this.zone = zone;
        this.fixed = fixed;
    }

    /**
     * Reads the four boundaries. All four must be month-day dates, or all
     * four full timestamps; mixing them is an error, since a one-off end for
     * a yearly start has no sensible meaning.
     */
    public static SeasonCalendar parse(String preSeasonStart, String activeStart, String activeEnd, String wrapUpEnd,
            String zoneId) {
        ZoneId zone;
        try {
            zone = zoneId == null || zoneId.trim().isEmpty() ? ZoneId.systemDefault() : ZoneId.of(zoneId.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("season.timezone '" + zoneId + "' is not a zone like Europe/Madrid or UTC");
        }
        String[] dates = {trim(preSeasonStart), trim(activeStart), trim(activeEnd), trim(wrapUpEnd)};
        int monthDays = 0;
        for (String date : dates) {
            if (MONTH_DAY.matcher(date).matches()) {
                monthDays++;
            }
        }
        if (monthDays == 4) {
            SeasonCalendar calendar = new SeasonCalendar(dates, zone, null);
            // Fails here, on load, when a date like 02-30 does not exist.
            calendar.build(2027);
            return calendar;
        }
        if (monthDays > 0) {
            throw new IllegalArgumentException("season dates mix month-day dates and full timestamps; use one kind for all four");
        }
        return new SeasonCalendar(dates, zone, SeasonWindow.parse(dates[0], dates[1], dates[2], dates[3]));
    }

    /** A calendar whose season never comes. */
    public static SeasonCalendar never() {
        return new SeasonCalendar(new String[0], ZoneId.of("UTC"), new SeasonWindow(0, 0, 0, 0));
    }

    public boolean recurring() {
        return fixed == null;
    }

    public ZoneId zone() {
        return zone;
    }

    /**
     * The window that matters at this moment: the one in progress, or the
     * next one. A season that crosses New Year is still the same season on
     * January 2nd.
     */
    public SeasonWindow windowAt(long millis) {
        if (fixed != null) {
            return fixed;
        }
        int year = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).getYear();
        for (int y = year - 1; y <= year; y++) {
            SeasonWindow window = build(y);
            if (millis <= window.wrapUpEndMillis()) {
                return window;
            }
        }
        return build(year + 1);
    }

    // Each boundary falls on or after the one before it, so an end in
    // January lands in the year after a start in December.
    private SeasonWindow build(int startYear) {
        long[] millis = new long[4];
        int year = startYear;
        for (int i = 0; i < 4; i++) {
            Matcher m = MONTH_DAY.matcher(dates[i]);
            if (!m.matches()) {
                throw new IllegalArgumentException("'" + dates[i] + "' is not a date like 12-01 or 12-31 23:59");
            }
            MonthDay day;
            try {
                day = MonthDay.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("'" + dates[i] + "' is not a day of the year");
            }
            LocalTime time;
            if (m.group(3) != null) {
                time = LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                        m.group(5) == null ? 0 : Integer.parseInt(m.group(5)));
            } else {
                // A bare date means the whole day: starts at its first second,
                // ends at its last one.
                time = i < 2 ? LocalTime.MIDNIGHT : LocalTime.of(23, 59, 59);
            }
            long value = LocalDateTime.of(day.atYear(year), time).atZone(zone).toInstant().toEpochMilli();
            if (i > 0 && value < millis[i - 1]) {
                year++;
                value = LocalDateTime.of(day.atYear(year), time).atZone(zone).toInstant().toEpochMilli();
            }
            millis[i] = value;
        }
        return new SeasonWindow(millis[0], millis[1], millis[2], millis[3]);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
