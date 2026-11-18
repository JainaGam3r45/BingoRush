package com.jifercraft.bingo.season;

/**
 * Seasonal event configuration. Encapsulates whether the season is enabled,
 * its scheduled time window, and the visual/decorative toggles.
 */
public final class SeasonSettings {

    private final boolean enabled;
    private final SeasonWindow window;
    private final boolean snowfall;
    private final boolean claimFirework;
    private final boolean lobbySnow;
    private final int lobbyRadius;

    public SeasonSettings(boolean enabled, SeasonWindow window, boolean snowfall,
            boolean claimFirework, boolean lobbySnow, int lobbyRadius) {
        this.enabled = enabled;
        this.window = window;
        this.snowfall = snowfall;
        this.claimFirework = claimFirework;
        this.lobbySnow = lobbySnow;
        this.lobbyRadius = Math.max(0, lobbyRadius);
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
}
