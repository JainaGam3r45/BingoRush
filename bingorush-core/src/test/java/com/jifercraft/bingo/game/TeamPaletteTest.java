package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TeamPaletteTest {

    @Test
    void takesNamesAndHex() {
        assertEquals(0xFF5555, TeamPalette.parse("red"));
        assertEquals(0x0000AA, TeamPalette.parse("Dark-Blue"));
        assertEquals(0xFF55FF, TeamPalette.parse("pink"));
        assertEquals(0x55FF55, TeamPalette.parse("lime"));
        assertEquals(0x12AB34, TeamPalette.parse("#12ab34"));
        assertEquals(0x12AB34, TeamPalette.parse("12AB34"));
    }

    @Test
    void rejectsEverythingElse() {
        assertNull(TeamPalette.parse("reddish"));
        assertNull(TeamPalette.parse("#12345"));
        assertNull(TeamPalette.parse(null));
    }

    @Test
    void autoTeamsStartWithColorsNobodyConfuses() {
        assertEquals("red", TeamPalette.entries().get(0).key());
        assertEquals("blue", TeamPalette.entries().get(1).key());
        assertEquals(16, TeamPalette.names().size());
    }
}
