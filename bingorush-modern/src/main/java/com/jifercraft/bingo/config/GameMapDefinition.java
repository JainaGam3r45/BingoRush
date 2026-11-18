package com.jifercraft.bingo.config;

import com.jifercraft.bingo.arena.ArenaOption;
import com.jifercraft.bingo.arena.ArenaPreset;
import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.game.BingoGameMode;
import java.util.Map;
import org.bukkit.Location;

/**
 * An arena: where and how a round is played. Changes go through
 * {@link #with(ArenaOption, Object)} with values from {@link ArenaOption#parse},
 * so every way of editing an arena accepts the same values.
 */
public record GameMapDefinition(
        String id,
        String name,
        String seed,
        BingoGameMode mode,
        WinMode winMode,
        boolean lockout,
        long timeLimit,
        int teamSize,
        int maxTeams,
        int countdown,
        int lives,
        int border,
        boolean pvp,
        boolean keepInventory,
        String daylight,
        Location waitingLobby) {

    /** A new arena with the round settings from config.yml. */
    public static GameMapDefinition fromDefaults(String id, String name, BingoConfig config, int configuredTeams) {
        int maxTeams = Math.max(2, Math.min(32, configuredTeams));
        if (config == null) {
            return new GameMapDefinition(id, name, "random", BingoGameMode.ITEMS, WinMode.LINE, false,
                    1800, 2, maxTeams, 10, 0, 0, true, false, "cycle", null);
        }
        return new GameMapDefinition(id, name, "random", config.gameMode(), config.winMode(), config.lockout(),
                config.timeLimitSeconds(), Math.max(1, Math.min(16, config.teamSize())), maxTeams,
                Math.max(0, Math.min(300, config.countdownSeconds())), Math.max(0, config.lives()), 0,
                true, false, "cycle", null);
    }

    public GameMapDefinition with(ArenaOption option, Object value) {
        return switch (option) {
            case NAME -> new GameMapDefinition(id, (String) value, seed, mode, winMode, lockout, timeLimit,
                    teamSize, maxTeams, countdown, lives, border, pvp, keepInventory, daylight, waitingLobby);
            case SEED -> new GameMapDefinition(id, name, (String) value, mode, winMode, lockout, timeLimit,
                    teamSize, maxTeams, countdown, lives, border, pvp, keepInventory, daylight, waitingLobby);
            case MODE -> new GameMapDefinition(id, name, seed, (BingoGameMode) value, winMode, lockout, timeLimit,
                    teamSize, maxTeams, countdown, lives, border, pvp, keepInventory, daylight, waitingLobby);
            case WIN_MODE -> new GameMapDefinition(id, name, seed, mode, (WinMode) value, lockout, timeLimit,
                    teamSize, maxTeams, countdown, lives, border, pvp, keepInventory, daylight, waitingLobby);
            case LOCKOUT -> new GameMapDefinition(id, name, seed, mode, winMode, (Boolean) value, timeLimit,
                    teamSize, maxTeams, countdown, lives, border, pvp, keepInventory, daylight, waitingLobby);
            case TIME_LIMIT -> new GameMapDefinition(id, name, seed, mode, winMode, lockout, (Long) value,
                    teamSize, maxTeams, countdown, lives, border, pvp, keepInventory, daylight, waitingLobby);
            case TEAM_SIZE -> new GameMapDefinition(id, name, seed, mode, winMode, lockout, timeLimit,
                    (Integer) value, maxTeams, countdown, lives, border, pvp, keepInventory, daylight, waitingLobby);
            case MAX_TEAMS -> new GameMapDefinition(id, name, seed, mode, winMode, lockout, timeLimit,
                    teamSize, (Integer) value, countdown, lives, border, pvp, keepInventory, daylight, waitingLobby);
            case COUNTDOWN -> new GameMapDefinition(id, name, seed, mode, winMode, lockout, timeLimit,
                    teamSize, maxTeams, (Integer) value, lives, border, pvp, keepInventory, daylight, waitingLobby);
            case LIVES -> new GameMapDefinition(id, name, seed, mode, winMode, lockout, timeLimit,
                    teamSize, maxTeams, countdown, (Integer) value, border, pvp, keepInventory, daylight, waitingLobby);
            case BORDER -> new GameMapDefinition(id, name, seed, mode, winMode, lockout, timeLimit,
                    teamSize, maxTeams, countdown, lives, (Integer) value, pvp, keepInventory, daylight, waitingLobby);
            case PVP -> new GameMapDefinition(id, name, seed, mode, winMode, lockout, timeLimit,
                    teamSize, maxTeams, countdown, lives, border, (Boolean) value, keepInventory, daylight, waitingLobby);
            case KEEP_INVENTORY -> new GameMapDefinition(id, name, seed, mode, winMode, lockout, timeLimit,
                    teamSize, maxTeams, countdown, lives, border, pvp, (Boolean) value, daylight, waitingLobby);
            case DAYLIGHT -> new GameMapDefinition(id, name, seed, mode, winMode, lockout, timeLimit,
                    teamSize, maxTeams, countdown, lives, border, pvp, keepInventory, (String) value, waitingLobby);
        };
    }

    /** The current value of a setting, in the form {@link ArenaOption#format} writes. */
    public Object get(ArenaOption option) {
        return switch (option) {
            case NAME -> name;
            case SEED -> seed;
            case MODE -> mode;
            case WIN_MODE -> winMode;
            case LOCKOUT -> lockout;
            case TIME_LIMIT -> timeLimit;
            case TEAM_SIZE -> teamSize;
            case MAX_TEAMS -> maxTeams;
            case COUNTDOWN -> countdown;
            case LIVES -> lives;
            case BORDER -> border;
            case PVP -> pvp;
            case KEEP_INVENTORY -> keepInventory;
            case DAYLIGHT -> daylight;
        };
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

    public int maxPlayers() {
        return Math.max(1, teamSize) * Math.max(1, maxTeams);
    }
}
