package com.jifercraft.bingo.config;

import com.jifercraft.bingo.arena.ArenaOption;
import com.jifercraft.bingo.arena.ArenaPreset;
import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.game.BingoGameMode;
import java.util.Map;
import org.bukkit.Location;

/**
 * An arena: how a round is played and where players wait for it. This jar
 * plays in the server's own worlds, so the border, PvP, keep-inventory and
 * daylight settings are only kept so that one arenas.yml works with both jars. Changes go through
 * {@link #with(ArenaOption, Object)} with values from {@link ArenaOption#parse}.
 */
public final class GameMapDefinition {

    private final String id;
    private final String name;
    private final String seed;
    private final BingoGameMode mode;
    private final WinMode winMode;
    private final boolean lockout;
    private final long timeLimit;
    private final int teamSize;
    private final int maxTeams;
    private final int countdown;
    private final int lives;
    private final int border;
    private final boolean pvp;
    private final boolean keepInventory;
    private final String daylight;
    private final Location waitingLobby;

    public GameMapDefinition(String id, String name, String seed, BingoGameMode mode, WinMode winMode,
            boolean lockout, long timeLimit, int teamSize, int maxTeams, int countdown, int lives, int border,
            boolean pvp, boolean keepInventory, String daylight, Location waitingLobby) {
        this.id = id;
        this.name = name;
        this.seed = seed;
        this.mode = mode;
        this.winMode = winMode;
        this.lockout = lockout;
        this.timeLimit = timeLimit;
        this.teamSize = teamSize;
        this.maxTeams = maxTeams;
        this.countdown = countdown;
        this.lives = lives;
        this.border = border;
        this.pvp = pvp;
        this.keepInventory = keepInventory;
        this.daylight = daylight;
        this.waitingLobby = waitingLobby;
    }

    /** A new arena with the round settings from config.yml. */
    public static GameMapDefinition fromDefaults(String id, String name, BingoConfig config, int configuredTeams) {
        int maxTeams = Math.max(2, Math.min(32, configuredTeams));
        if (config == null) {
            return new GameMapDefinition(id, name, "random", BingoGameMode.ITEMS, WinMode.LINE, false,
                    1800, 2, maxTeams, 10, 0, 0, true, false, "cycle", null);
        }
        return new GameMapDefinition(id, name, "random", config.gameMode(), config.winMode(), config.lockout(),
                config.timeLimitSeconds(), Math.max(1, Math.min(16, config.teamSize())), maxTeams,
                Math.max(0, Math.min(300, config.countdownSeconds())), Math.max(0, config.lives()), 0, true, false, "cycle", null);
    }

    public GameMapDefinition with(ArenaOption option, Object value) {
        String n = name;
        String s = seed;
        BingoGameMode m = mode;
        WinMode w = winMode;
        boolean l = lockout;
        long t = timeLimit;
        int size = teamSize;
        int teams = maxTeams;
        int c = countdown;
        int lv = lives;
        int b = border;
        boolean p = pvp;
        boolean k = keepInventory;
        String d = daylight;
        switch (option) {
            case NAME:
                n = (String) value;
                break;
            case SEED:
                s = (String) value;
                break;
            case MODE:
                m = (BingoGameMode) value;
                break;
            case WIN_MODE:
                w = (WinMode) value;
                break;
            case LOCKOUT:
                l = (Boolean) value;
                break;
            case TIME_LIMIT:
                t = (Long) value;
                break;
            case TEAM_SIZE:
                size = (Integer) value;
                break;
            case MAX_TEAMS:
                teams = (Integer) value;
                break;
            case COUNTDOWN:
                c = (Integer) value;
                break;
            case LIVES:
                lv = (Integer) value;
                break;
            case BORDER:
                b = (Integer) value;
                break;
            case PVP:
                p = (Boolean) value;
                break;
            case KEEP_INVENTORY:
                k = (Boolean) value;
                break;
            case DAYLIGHT:
                d = (String) value;
                break;
            default:
                throw new IllegalArgumentException("Unknown setting " + option);
        }
        return new GameMapDefinition(id, n, s, m, w, l, t, size, teams, c, lv, b, p, k, d, waitingLobby);
    }

    /** The current value of a setting, in the form {@link ArenaOption#format} writes. */
    public Object get(ArenaOption option) {
        switch (option) {
            case NAME:
                return name;
            case SEED:
                return seed;
            case MODE:
                return mode;
            case WIN_MODE:
                return winMode;
            case LOCKOUT:
                return lockout;
            case TIME_LIMIT:
                return timeLimit;
            case TEAM_SIZE:
                return teamSize;
            case MAX_TEAMS:
                return maxTeams;
            case COUNTDOWN:
                return countdown;
            case LIVES:
                return lives;
            case BORDER:
                return border;
            case PVP:
                return pvp;
            case KEEP_INVENTORY:
                return keepInventory;
            case DAYLIGHT:
                return daylight;
            default:
                throw new IllegalArgumentException("Unknown setting " + option);
        }
    }

    public GameMapDefinition apply(ArenaPreset preset) {
        GameMapDefinition result = this;
        for (Map.Entry<ArenaOption, Object> entry : preset.values().entrySet()) {
            result = result.with(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public GameMapDefinition withWaitingLobby(Location newWaitingLobby) {
        return new GameMapDefinition(id, name, seed, mode, winMode, lockout, timeLimit, teamSize, maxTeams,
                countdown, lives, border, pvp, keepInventory, daylight, newWaitingLobby);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String seed() {
        return seed;
    }

    public BingoGameMode mode() {
        return mode;
    }

    public WinMode winMode() {
        return winMode;
    }

    public boolean lockout() {
        return lockout;
    }

    public long timeLimit() {
        return timeLimit;
    }

    public int teamSize() {
        return teamSize;
    }

    public int maxTeams() {
        return maxTeams;
    }

    public int countdown() {
        return countdown;
    }

    public int lives() {
        return lives;
    }

    public int border() {
        return border;
    }

    public Location waitingLobby() {
        return waitingLobby;
    }

    public int maxPlayers() {
        return Math.max(1, teamSize) * Math.max(1, maxTeams);
    }
}
