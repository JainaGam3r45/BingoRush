package com.jifercraft.bingo.game;

import java.util.Locale;

/**
 * The card objective: collecting items or completing advancements.
 */
public enum BingoGameMode {
    ITEMS,
    ADVANCEMENTS;

    public static BingoGameMode parse(String raw) {
        if (raw == null) {
            return null;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "items":
            case "item":
                return ITEMS;
            case "advancements":
            case "advancement":
            case "logros":
            case "logro":
                return ADVANCEMENTS;
            default:
                return null;
        }
    }
}
