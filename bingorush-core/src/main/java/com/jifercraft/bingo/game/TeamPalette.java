package com.jifercraft.bingo.game;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Team colors by name. The sixteen chat color names plus hex, so an admin
 * can write "red" instead of looking up #FF5555, and the order /bingo teams
 * auto hands colors out in.
 */
public final class TeamPalette {

    /** A color with the key its default name has in the language files. */
    public static final class Entry {
        private final String key;
        private final int rgb;

        Entry(String key, int rgb) {
            this.key = key;
            this.rgb = rgb;
        }

        public String key() {
            return key;
        }

        public int rgb() {
            return rgb;
        }
    }

    // Most distinct first: with four teams nobody should be guessing
    // which green is theirs.
    private static final List<Entry> AUTO_ORDER = Collections.unmodifiableList(Arrays.asList(
            new Entry("red", 0xFF5555), new Entry("blue", 0x5555FF), new Entry("green", 0x55FF55),
            new Entry("yellow", 0xFFFF55), new Entry("aqua", 0x55FFFF), new Entry("light_purple", 0xFF55FF),
            new Entry("gold", 0xFFAA00), new Entry("white", 0xFFFFFF), new Entry("dark_red", 0xAA0000),
            new Entry("dark_blue", 0x0000AA), new Entry("dark_green", 0x00AA00), new Entry("dark_aqua", 0x00AAAA),
            new Entry("dark_purple", 0xAA00AA), new Entry("gray", 0xAAAAAA), new Entry("dark_gray", 0x555555),
            new Entry("black", 0x000000)));

    private TeamPalette() {
    }

    /** All sixteen, in the order new teams get them. */
    public static List<Entry> entries() {
        return AUTO_ORDER;
    }

    /** "#FF5555", "FF5555" or a color name such as "red" or "dark-blue"; null when it is neither. */
    public static Integer parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (Entry entry : AUTO_ORDER) {
            if (entry.key.equals(value)) {
                return entry.rgb;
            }
        }
        if (value.equals("purple") || value.equals("pink")) {
            return 0xFF55FF;
        }
        if (value.equals("orange")) {
            return 0xFFAA00;
        }
        if (value.equals("lime")) {
            return 0x55FF55;
        }
        if (value.equals("cyan")) {
            return 0x55FFFF;
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Names worth offering in tab completion. */
    public static List<String> names() {
        String[] names = new String[AUTO_ORDER.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = AUTO_ORDER.get(i).key;
        }
        return Arrays.asList(names);
    }
}
