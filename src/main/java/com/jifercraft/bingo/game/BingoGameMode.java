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
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "items", "item" -> ITEMS;
            case "advancements", "advancement", "logros", "logro" -> ADVANCEMENTS;
            default -> null;
        };
    }
}
