package com.jifercraft.bingo.arena;

import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.game.BingoGameMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Every arena setting an admin can change, with the one parser that the
 * arenas file, the presets, /bingo arena set and the chat prompts share.
 * A bad value throws {@link InvalidArenaValue}, which says what the setting
 * takes, so the admin reads how to fix it instead of a stack trace.
 */
public enum ArenaOption {

    NAME("name"),
    SEED("seed", "random"),
    MODE("mode", "items", "advancements"),
    WIN_MODE("win-mode", "line", "full"),
    LOCKOUT("lockout", "true", "false"),
    TIME_LIMIT("time-limit", "0", "10m", "15m", "20m", "30m", "45m", "60m"),
    TEAM_SIZE("team-size", "1", "2", "3", "4"),
    MAX_TEAMS("max-teams", "2", "4", "8"),
    COUNTDOWN("countdown", "0", "5", "10", "15", "30"),
    LIVES("lives", "0", "1", "3", "5"),
    BORDER("border", "0", "500", "1000", "2000", "5000"),
    PVP("pvp", "true", "false"),
    KEEP_INVENTORY("keep-inventory", "true", "false"),
    DAYLIGHT("daylight", "cycle", "day", "night");

    /** Ids end up in world keys, folder names and YAML paths. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_SEED_LENGTH = 64;

    private final String key;
    private final List<String> suggestions;

    ArenaOption(String key, String... suggestions) {
        this.key = key;
        this.suggestions = Collections.unmodifiableList(Arrays.asList(suggestions));
    }

    /** The name used in arenas.yml, presets.yml and commands. */
    public String key() {
        return key;
    }

    /** Values worth offering in tab completion; any valid value is accepted. */
    public List<String> suggestions() {
        return suggestions;
    }

    public static ArenaOption byKey(String key) {
        if (key == null) {
            return null;
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        for (ArenaOption option : values()) {
            if (option.key.equals(wanted)) {
                return option;
            }
        }
        return null;
    }

    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * Turns what an admin typed into the id form: lower case, spaces and
     * dots to dashes, anything else outside a-z, 0-9, _ and - dropped.
     * Empty when nothing usable is left.
     */
    public static String toId(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('.', '-');
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < lower.length() && id.length() < 32; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                id.append(c);
            }
        }
        return id.toString();
    }

    /**
     * Parses a raw value. Returns a String for name and seed, a
     * {@link BingoGameMode}, a {@link WinMode}, a Boolean, a String for
     * daylight, a Long for the time limit or an Integer for the rest.
     */
    public Object parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        switch (this) {
            case NAME:
                String name = stripControl(value);
                if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
                    throw invalid(value);
                }
                return name;
            case SEED:
                if (lower.isEmpty() || lower.equals("random")) {
                    return "random";
                }
                String seed = stripControl(value);
                if (seed.length() > MAX_SEED_LENGTH) {
                    throw invalid(value);
                }
                return seed;
            case MODE:
                if (lower.equals("items") || lower.equals("item")) {
                    return BingoGameMode.ITEMS;
                }
                if (lower.equals("advancements") || lower.equals("advancement")) {
                    return BingoGameMode.ADVANCEMENTS;
                }
                throw invalid(value);
            case WIN_MODE:
                if (lower.equals("line")) {
                    return WinMode.LINE;
                }
                // "blackout" and "full_card" are what earlier builds wrote.
                if (lower.equals("full") || lower.equals("full_card") || lower.equals("blackout")) {
                    return WinMode.FULL_CARD;
                }
                throw invalid(value);
            case LOCKOUT:
            case PVP:
            case KEEP_INVENTORY:
                if (lower.equals("true") || lower.equals("on") || lower.equals("yes")) {
                    return Boolean.TRUE;
                }
                if (lower.equals("false") || lower.equals("off") || lower.equals("no")) {
                    return Boolean.FALSE;
                }
                throw invalid(value);
            case DAYLIGHT:
                if (lower.equals("cycle") || lower.equals("day") || lower.equals("night")) {
                    return lower;
                }
                throw invalid(value);
            case TIME_LIMIT:
                return (long) parseDuration(value, 0, 24 * 3600);
            case TEAM_SIZE:
                return parseInt(value, 1, 16);
            case MAX_TEAMS:
                return parseInt(value, 2, 32);
            case COUNTDOWN:
                return parseInt(value, 0, 300);
            case LIVES:
                return parseInt(value, 0, 100);
            case BORDER:
                int border = parseInt(value, 0, 100000);
                if (border != 0 && border < 64) {
                    throw invalid(value);
                }
                return border;
            default:
                throw new IllegalStateException("No parser for " + key);
        }
    }

    /** The plain text form of a parsed value, as it is written back to YAML. */
    public String format(Object value) {
        if (value instanceof BingoGameMode) {
            return ((BingoGameMode) value).name().toLowerCase(Locale.ROOT);
        }
        if (value instanceof WinMode) {
            return value == WinMode.FULL_CARD ? "full" : "line";
        }
        return String.valueOf(value);
    }

    // Seconds by default; "30m" and "1h" are friendlier to type in chat.
    private int parseDuration(String value, int min, int max) {
        String lower = value.toLowerCase(Locale.ROOT);
        int factor = 1;
        if (lower.endsWith("m")) {
            factor = 60;
            lower = lower.substring(0, lower.length() - 1);
        } else if (lower.endsWith("h")) {
            factor = 3600;
            lower = lower.substring(0, lower.length() - 1);
        } else if (lower.endsWith("s")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        long seconds;
        try {
            seconds = Long.parseLong(lower.trim()) * factor;
        } catch (NumberFormatException e) {
            throw invalid(value);
        }
        if (seconds < min || seconds > max) {
            throw invalid(value);
        }
        return (int) seconds;
    }

    private int parseInt(String value, int min, int max) {
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalid(value);
        }
        if (number < min || number > max) {
            throw invalid(value);
        }
        return number;
    }

    private static String stripControl(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    private InvalidArenaValue invalid(String value) {
        return new InvalidArenaValue(this, value);
    }
}
