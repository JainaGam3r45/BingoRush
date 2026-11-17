package com.jifercraft.bingo.game;

/**
 * Turns a rendered chat template into the format string Bukkit wants. That
 * string goes through String.format on every message, with the sender at
 * %1$s and the text at %2$s, so any other percent sign in it, a team called
 * "100%" for one, would make every chat message throw.
 */
public final class ChatFormat {

    /** Stand-ins rendered in place of the placeholders; private-use characters no template contains. */
    public static final String PLAYER_TOKEN = "\uE000";
    public static final String MESSAGE_TOKEN = "\uE001";

    private ChatFormat() {
    }

    /**
     * The format, or null when the template has no place for the message: using
     * it would swallow what players write, so the caller leaves chat alone.
     */
    public static String toFormat(String rendered) {
        if (rendered == null || !rendered.contains(MESSAGE_TOKEN)) {
            return null;
        }
        return rendered.replace("%", "%%")
                .replace(PLAYER_TOKEN, "%1$s")
                .replace(MESSAGE_TOKEN, "%2$s");
    }
}
