package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jifercraft.bingo.card.WinMode;
import org.junit.jupiter.api.Test;

class RoundSettingsTest {

    @Test
    void cyclesGameModeBothWays() {
        RoundSettings settings = new RoundSettings(BingoGameMode.ITEMS, WinMode.LINE, false, 0);
        settings.cycleMode();
        assertEquals(BingoGameMode.ADVANCEMENTS, settings.mode());
        settings.cycleMode();
        assertEquals(BingoGameMode.ITEMS, settings.mode());
    }

    @Test
    void cyclesWinModeBothWays() {
        RoundSettings settings = new RoundSettings(BingoGameMode.ITEMS, WinMode.FULL_CARD, false, 0);
        settings.cycleWinMode();
        assertEquals(WinMode.LINE, settings.winMode());
        settings.cycleWinMode();
        assertEquals(WinMode.FULL_CARD, settings.winMode());
    }

    @Test
    void togglesLockout() {
        RoundSettings settings = new RoundSettings(BingoGameMode.ITEMS, WinMode.LINE, false, 0);
        assertFalse(settings.lockout());
        settings.toggleLockout();
        assertTrue(settings.lockout());
        settings.toggleLockout();
        assertFalse(settings.lockout());
    }

    @Test
    void cyclesTimePresetsInOrder() {
        RoundSettings settings = new RoundSettings(BingoGameMode.ITEMS, WinMode.FULL_CARD, false, 1800);
        settings.cycleTimeLimit();
        assertEquals(2700, settings.timeLimitSeconds());
        settings.cycleTimeLimit();
        assertEquals(3600, settings.timeLimitSeconds());
        settings.cycleTimeLimit();
        assertEquals(0, settings.timeLimitSeconds());
        settings.cycleTimeLimit();
        assertEquals(900, settings.timeLimitSeconds());
        settings.cycleTimeLimit();
        assertEquals(1800, settings.timeLimitSeconds());
    }

    @Test
    void noLimitCyclesBackToTheFirstPreset() {
        RoundSettings settings = new RoundSettings(BingoGameMode.ITEMS, WinMode.FULL_CARD, false, 0);
        settings.cycleTimeLimit();
        assertEquals(900, settings.timeLimitSeconds());
    }

    @Test
    void unknownLimitStartsFromTheFirstPreset() {
        RoundSettings settings = new RoundSettings(BingoGameMode.ITEMS, WinMode.FULL_CARD, false, 1200);
        settings.cycleTimeLimit();
        assertEquals(900, settings.timeLimitSeconds());
    }

    @Test
    void negativeLimitClampsToZero() {
        RoundSettings settings = new RoundSettings(BingoGameMode.ITEMS, WinMode.FULL_CARD, true, -30);
        assertEquals(0, settings.timeLimitSeconds());
    }
}
