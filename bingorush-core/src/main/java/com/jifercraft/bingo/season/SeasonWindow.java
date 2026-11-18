package com.jifercraft.bingo.season;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The four boundaries of one season, in epoch milliseconds, and the phase at
 * any moment. Boundaries out of order are refused when it is made.
 */
public final class SeasonWindow {

    private final long preSeasonStartMillis;
    private final long activeStartMillis;
    private final long activeEndMillis;
    private final long wrapUpEndMillis;

    public SeasonWindow(long preSeasonStartMillis, long activeStartMillis, long activeEndMillis, long wrapUpEndMillis) {
        if (preSeasonStartMillis > activeStartMillis) {
            throw new IllegalArgumentException("pre-season-start cannot be after active-start");
        }
        if (activeStartMillis > activeEndMillis) {
            throw new IllegalArgumentException("active-start cannot be after active-end");
        }
        if (activeEndMillis > wrapUpEndMillis) {
            throw new IllegalArgumentException("active-end cannot be after wrap-up-end");
        }
        this.preSeasonStartMillis = preSeasonStartMillis;
        this.activeStartMillis = activeStartMillis;
        this.activeEndMillis = activeEndMillis;
        this.wrapUpEndMillis = wrapUpEndMillis;
    }

    public static SeasonWindow parse(String preSeasonStart, String activeStart, String activeEnd, String wrapUpEnd) {
        return new SeasonWindow(
                parseUtcTimestamp(preSeasonStart),
                parseUtcTimestamp(activeStart),
                parseUtcTimestamp(activeEnd),
                parseUtcTimestamp(wrapUpEnd)
        );
    }

    public static long parseUtcTimestamp(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Timestamp cannot be blank");
        }
        String s = text.trim();
        if (s.length() == 10 && s.indexOf('T') == -1) {
            s = s + "T00:00:00Z";
        } else if (s.contains(" ") && s.indexOf('T') == -1) {
            s = s.replace(' ', 'T');
        }
        if (!s.endsWith("Z") && !s.contains("+") && s.lastIndexOf('-') <= 7) {
            s = s + "Z";
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 timestamp: '" + text + "'. Expected format like 2026-12-01T00:00:00Z", e);
        }
    }

    public static String formatUtc(long epochMillis) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
    }

    public SeasonPhase phaseAt(long millis) {
        if (millis < preSeasonStartMillis) {
            return SeasonPhase.INACTIVE;
        }
        if (millis < activeStartMillis) {
            return SeasonPhase.PRE_SEASON;
        }
        if (millis <= activeEndMillis) {
            return SeasonPhase.ACTIVE;
        }
        if (millis <= wrapUpEndMillis) {
            return SeasonPhase.WRAP_UP;
        }
        return SeasonPhase.INACTIVE;
    }

    public boolean isActive(long millis) {
        return phaseAt(millis) == SeasonPhase.ACTIVE;
    }

    public long remainingMillisInPhase(long millis) {
        SeasonPhase phase = phaseAt(millis);
        switch (phase) {
            case PRE_SEASON:
                return activeStartMillis - millis;
            case ACTIVE:
                return activeEndMillis - millis;
            case WRAP_UP:
                return wrapUpEndMillis - millis;
            case INACTIVE:
            default:
                if (millis < preSeasonStartMillis) {
                    return preSeasonStartMillis - millis;
                }
                return 0L;
        }
    }

    public long preSeasonStartMillis() {
        return preSeasonStartMillis;
    }

    public long activeStartMillis() {
        return activeStartMillis;
    }

    public long activeEndMillis() {
        return activeEndMillis;
    }

    public long wrapUpEndMillis() {
        return wrapUpEndMillis;
    }
}
