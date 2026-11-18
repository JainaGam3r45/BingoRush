package com.jifercraft.bingo.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.game.BingoGameMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArenaOptionTest {

    @Test
    void timeLimitTakesSecondsMinutesAndHours() {
        assertEquals(1800L, ArenaOption.TIME_LIMIT.parse("1800"));
        assertEquals(1800L, ArenaOption.TIME_LIMIT.parse("30m"));
        assertEquals(3600L, ArenaOption.TIME_LIMIT.parse("1h"));
        assertEquals(0L, ArenaOption.TIME_LIMIT.parse("0"));
    }

    @Test
    void outOfRangeNumbersSayWhichSettingFailed() {
        InvalidArenaValue error = assertThrows(InvalidArenaValue.class, () -> ArenaOption.TEAM_SIZE.parse("0"));
        assertSame(ArenaOption.TEAM_SIZE, error.option());
        assertEquals("0", error.value());
        assertThrows(InvalidArenaValue.class, () -> ArenaOption.TIME_LIMIT.parse("soon"));
        assertThrows(InvalidArenaValue.class, () -> ArenaOption.MAX_TEAMS.parse("1"));
    }

    @Test
    void borderIsOffOrAtLeastSixtyFourBlocks() {
        assertEquals(0, ArenaOption.BORDER.parse("0"));
        assertEquals(1000, ArenaOption.BORDER.parse("1000"));
        assertThrows(InvalidArenaValue.class, () -> ArenaOption.BORDER.parse("10"));
    }

    @Test
    void winModeAcceptsWhatEarlierBuildsWrote() {
        assertSame(WinMode.FULL_CARD, ArenaOption.WIN_MODE.parse("blackout"));
        assertSame(WinMode.FULL_CARD, ArenaOption.WIN_MODE.parse("FULL"));
        assertSame(WinMode.LINE, ArenaOption.WIN_MODE.parse("line"));
        assertEquals("full", ArenaOption.WIN_MODE.format(WinMode.FULL_CARD));
    }

    @Test
    void modeAndLockoutParseFriendlyWords() {
        assertSame(BingoGameMode.ADVANCEMENTS, ArenaOption.MODE.parse("Advancements"));
        assertEquals(Boolean.TRUE, ArenaOption.LOCKOUT.parse("on"));
        assertEquals(Boolean.FALSE, ArenaOption.LOCKOUT.parse("no"));
        assertThrows(InvalidArenaValue.class, () -> ArenaOption.LOCKOUT.parse("maybe"));
    }

    @Test
    void namesLoseControlCharactersAndKeepTheirLength() {
        assertEquals("Final round", ArenaOption.NAME.parse("  Final\u0007 round "));
        assertThrows(InvalidArenaValue.class, () -> ArenaOption.NAME.parse("   "));
        assertThrows(InvalidArenaValue.class, () -> ArenaOption.NAME.parse("x".repeat(33)));
    }

    @Test
    void blankSeedMeansRandom() {
        assertEquals("random", ArenaOption.SEED.parse(""));
        assertEquals("summer-event", ArenaOption.SEED.parse("summer-event"));
    }

    @Test
    void idsAreSafeForWorldKeysAndFolders() {
        assertEquals("final-round", ArenaOption.toId("Final Round"));
        assertEquals("a--b", ArenaOption.toId("a/../b"));
        assertEquals("", ArenaOption.toId("///"));
        assertTrue(ArenaOption.isValidId("arena_2"));
        assertFalse(ArenaOption.isValidId("Arena"));
        assertFalse(ArenaOption.isValidId("../x"));
    }

    @Test
    void worldRulesTakeWordsAnAdminWouldType() {
        assertEquals(Boolean.FALSE, ArenaOption.PVP.parse("off"));
        assertEquals(Boolean.TRUE, ArenaOption.KEEP_INVENTORY.parse("Yes"));
        assertEquals("night", ArenaOption.DAYLIGHT.parse("NIGHT"));
        assertThrows(InvalidArenaValue.class, () -> ArenaOption.DAYLIGHT.parse("dusk"));
    }

    @Test
    void keysResolveBothWays() {
        for (ArenaOption option : ArenaOption.values()) {
            assertSame(option, ArenaOption.byKey(option.key()));
        }
        assertNull(ArenaOption.byKey("colour"));
    }

    @Test
    void presetKeepsGoodSettingsAndReportsBadOnes() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("team-size", 4);
        raw.put("lockout", "yes");
        raw.put("time-limit", "forever");
        raw.put("colour", "red");
        raw.put("name", "Nope");
        List<String> problems = new ArrayList<>();

        ArenaPreset preset = ArenaPreset.parse("squads", raw, problems);

        assertEquals(4, preset.values().get(ArenaOption.TEAM_SIZE));
        assertEquals(Boolean.TRUE, preset.values().get(ArenaOption.LOCKOUT));
        assertFalse(preset.values().containsKey(ArenaOption.TIME_LIMIT));
        assertFalse(preset.values().containsKey(ArenaOption.NAME));
        assertEquals(3, problems.size());
    }
}
