package com.jifercraft.bingo.game;

import java.util.Locale;

/** Short forms of a team name for places with little room, such as the tab list. */
public final class TeamLabel {

    private TeamLabel() {
    }

    /**
     * The first letter of the name in upper case. Goes by code point, so a name
     * that starts with an emoji does not end up as half of a surrogate pair.
     */
    public static String initial(String teamName) {
        String trimmed = teamName == null ? "" : teamName.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        int first = trimmed.codePointAt(0);
        return new String(Character.toChars(first)).toUpperCase(Locale.ROOT);
    }
}
