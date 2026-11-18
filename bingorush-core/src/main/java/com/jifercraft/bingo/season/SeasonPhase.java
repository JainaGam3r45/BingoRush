package com.jifercraft.bingo.season;

/** Where the season stands: not yet, announced, running, or winding down. */
public enum SeasonPhase {
    INACTIVE,
    PRE_SEASON,
    ACTIVE,
    WRAP_UP;

    public boolean isVisualsActive() {
        return this == ACTIVE || this == WRAP_UP;
    }
}
