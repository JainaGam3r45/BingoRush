package com.jifercraft.bingo.config;

/**
 * What goes in front of a player's name in the player list, above their head
 * and in vanilla chat, for each moment: waiting in the lobby, playing, and
 * watching as a spectator. MiniMessage, rendered by each platform with the
 * team's name, tag and color.
 */
public final class DisplayFormats {

    public enum State { LOBBY, PLAYING, SPECTATING }

    private final boolean enabled;
    private final String lobby;
    private final String playing;
    private final String spectating;

    public DisplayFormats(boolean enabled, String lobby, String playing, String spectating) {
        this.enabled = enabled;
        this.lobby = lobby == null ? "" : lobby;
        this.playing = playing == null ? "" : playing;
        this.spectating = spectating == null ? "" : spectating;
    }

    public boolean enabled() {
        return enabled;
    }

    public String prefix(State state) {
        switch (state) {
            case PLAYING:
                return playing;
            case SPECTATING:
                return spectating;
            default:
                return lobby;
        }
    }
}
