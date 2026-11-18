package com.jifercraft.bingo.season;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** The season section of config.yml, read once per load. */
public final class SeasonSettings {

    /**
     * Lobby touches that run whenever the season is on, whether or not a
     * round is being played.
     */
    public static final class Lobby {

        private final boolean snowTrail;
        private final boolean tabHeader;
        private final boolean joinGreeting;
        private final boolean countdown;
        private final MonthDay highlight;
        private final boolean newYear;

        public Lobby(boolean snowTrail, boolean tabHeader, boolean joinGreeting, boolean countdown,
                MonthDay highlight, boolean newYear) {
            this.snowTrail = snowTrail;
            this.tabHeader = tabHeader;
            this.joinGreeting = joinGreeting;
            this.countdown = countdown;
            this.highlight = highlight;
            this.newYear = newYear;
        }

        public static Lobby none() {
            return new Lobby(false, false, false, false, null, false);
        }

        /** Footprints of snow under players walking around the lobby. */
        public boolean snowTrail() {
            return snowTrail;
        }

        /** The season header and footer in the player list, for players in the lobby world. */
        public boolean tabHeader() {
            return tabHeader;
        }

        public boolean joinGreeting() {
            return joinGreeting;
        }

        /** Days left to the highlight day, in the action bar of lobby players. */
        public boolean countdown() {
            return countdown;
        }

        /** The big day of the season, such as 12-25; null when there is none. */
        public MonthDay highlight() {
            return highlight;
        }

        /** Fireworks and a title over the lobby when the year changes. */
        public boolean newYear() {
            return newYear;
        }
    }

    private final boolean enabled;
    private final SeasonCalendar calendar;
    private final boolean snowfall;
    private final boolean claimFirework;
    private final boolean lobbySnow;
    private final int lobbyRadius;
    private final GiftSettings giftSettings;
    private final Lobby lobby;

    public SeasonSettings(boolean enabled, SeasonCalendar calendar, boolean snowfall, boolean claimFirework,
            boolean lobbySnow, int lobbyRadius, GiftSettings giftSettings, Lobby lobby) {
        this.enabled = enabled;
        this.calendar = calendar;
        this.snowfall = snowfall;
        this.claimFirework = claimFirework;
        this.lobbySnow = lobbySnow;
        this.lobbyRadius = Math.max(0, lobbyRadius);
        this.giftSettings = giftSettings != null ? giftSettings : GiftSettings.disabled();
        this.lobby = lobby != null ? lobby : Lobby.none();
    }

    /** A season that never runs, for when config.yml could not be read. */
    public static SeasonSettings off() {
        return new SeasonSettings(false, SeasonCalendar.never(), false, false, false, 0, GiftSettings.disabled(),
                Lobby.none());
    }

    public boolean enabled() {
        return enabled;
    }

    public SeasonCalendar calendar() {
        return calendar;
    }

    /** The window in progress at this moment, or the next one. */
    public SeasonWindow windowAt(long millis) {
        return calendar.windowAt(millis);
    }

    /**
     * Whole days from this moment to the highlight day, 0 on the day itself,
     * or -1 when the season has none ahead: none is set, or it already went
     * by and the next one belongs to next year's season.
     */
    public long daysToHighlight(long millis) {
        MonthDay highlight = lobby.highlight();
        if (highlight == null) {
            return -1;
        }
        ZoneId zone = calendar.zone();
        LocalDate today = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        LocalDate next = highlight.atYear(today.getYear());
        if (next.isBefore(today)) {
            next = highlight.atYear(today.getYear() + 1);
        }
        long nextStart = next.atStartOfDay(zone).toInstant().toEpochMilli();
        return nextStart > windowAt(millis).wrapUpEndMillis() ? -1 : ChronoUnit.DAYS.between(today, next);
    }

    public boolean snowfall() {
        return snowfall;
    }

    public boolean claimFirework() {
        return claimFirework;
    }

    public boolean lobbySnow() {
        return lobbySnow;
    }

    public int lobbyRadius() {
        return lobbyRadius;
    }

    public GiftSettings giftSettings() {
        return giftSettings;
    }

    public Lobby lobby() {
        return lobby;
    }
}
