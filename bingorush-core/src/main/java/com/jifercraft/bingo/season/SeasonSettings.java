package com.jifercraft.bingo.season;

/**
 * Seasonal event configuration. Encapsulates whether the season is enabled,
 * its scheduled time window, decorative toggles, and the lobby gifts hunt settings.
 */
public final class SeasonSettings {

    private final boolean enabled;
    private final SeasonWindow window;
    private final boolean snowfall;
    private final boolean claimFirework;
    private final boolean lobbySnow;
    private final int lobbyRadius;
    private final GiftSettings giftSettings;

    public SeasonSettings(boolean enabled, SeasonWindow window, boolean snowfall,
            boolean claimFirework, boolean lobbySnow, int lobbyRadius) {
        this(enabled, window, snowfall, claimFirework, lobbySnow, lobbyRadius, GiftSettings.disabled());
    }

    public SeasonSettings(boolean enabled, SeasonWindow window, boolean snowfall,
            boolean claimFirework, boolean lobbySnow, int lobbyRadius, GiftSettings giftSettings) {
        this.enabled = enabled;
        this.window = window;
        this.snowfall = snowfall;
        this.claimFirework = claimFirework;
        this.lobbySnow = lobbySnow;
        this.lobbyRadius = Math.max(0, lobbyRadius);
        this.giftSettings = giftSettings != null ? giftSettings : GiftSettings.disabled();
    }

    /** A season that never runs, for when config.yml could not be read. */
    public static SeasonSettings off() {
        return new SeasonSettings(false, new SeasonWindow(0, 0, 0, 0), false, false, false, 0, GiftSettings.disabled());
    }

    public boolean enabled() {
        return enabled;
    }

    public SeasonWindow window() {
        return window;
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
}
