package com.jifercraft.bingo.config;

import java.util.Locale;

/** How chat shows a death or an advancement of a player in a round. */
public enum ChatStyle {
    /** The template from messages.yml. */
    CUSTOM,
    /** Minecraft's own line, untouched. */
    VANILLA,
    /** No line at all. */
    HIDDEN;

    static ChatStyle parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
