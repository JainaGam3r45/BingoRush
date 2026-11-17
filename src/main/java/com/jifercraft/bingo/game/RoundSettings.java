package com.jifercraft.bingo.game;

import com.jifercraft.bingo.card.WinMode;

/**
 * The round settings the host panel edits before launching. Pure values with
 * the cycle rules and no Bukkit imports, so tests can run them without a
 * server.
 */
public final class RoundSettings {

    /** Time limit presets the panel cycles through, in seconds. */
    static final long[] TIME_PRESETS = {900, 1800, 2700, 3600, 0};

    private BingoGameMode mode;
    private WinMode winMode;
    private boolean lockout;
    private long timeLimitSeconds;

    public RoundSettings(BingoGameMode mode, WinMode winMode, boolean lockout, long timeLimitSeconds) {
        this.mode = mode;
        this.winMode = winMode;
        this.lockout = lockout;
        // A negative limit would make the clock fire on the first tick.
        this.timeLimitSeconds = Math.max(0, timeLimitSeconds);
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

    public long timeLimitSeconds() {
        return timeLimitSeconds;
    }

    public void cycleMode() {
        mode = mode == BingoGameMode.ITEMS ? BingoGameMode.ADVANCEMENTS : BingoGameMode.ITEMS;
    }

    public void cycleWinMode() {
        winMode = winMode == WinMode.LINE ? WinMode.FULL_CARD : WinMode.LINE;
    }

    public void toggleLockout() {
        lockout = !lockout;
    }

    /** Next preset after the current limit; a limit outside the list starts from the first one. */
    public void cycleTimeLimit() {
        int index = -1;
        for (int i = 0; i < TIME_PRESETS.length; i++) {
            if (TIME_PRESETS[i] == timeLimitSeconds) {
                index = i;
                break;
            }
        }
        timeLimitSeconds = TIME_PRESETS[(index + 1) % TIME_PRESETS.length];
    }
}
