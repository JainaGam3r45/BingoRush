package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cryptomorin.xseries.XMaterial;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class LegacyItemsTest {

    @Test
    void matchesPrimaryColorsToExpectedGlassPanes() {
        assertEquals(XMaterial.RED_STAINED_GLASS_PANE, LegacyItems.paneFor(TextColor.color(0xB02E26)));
        assertEquals(XMaterial.BLUE_STAINED_GLASS_PANE, LegacyItems.paneFor(TextColor.color(0x3C44AA)));
        assertEquals(XMaterial.LIME_STAINED_GLASS_PANE, LegacyItems.paneFor(TextColor.color(0x80C71F)));
        assertEquals(XMaterial.YELLOW_STAINED_GLASS_PANE, LegacyItems.paneFor(TextColor.color(0xFED83D)));
        assertEquals(XMaterial.BLACK_STAINED_GLASS_PANE, LegacyItems.paneFor(TextColor.color(0x1D1D21)));
        assertEquals(XMaterial.WHITE_STAINED_GLASS_PANE, LegacyItems.paneFor(TextColor.color(0xF9FFFE)));
    }

    @Test
    void matchesPrimaryColorsToExpectedWool() {
        assertEquals(XMaterial.RED_WOOL, LegacyItems.woolFor(TextColor.color(0xB02E26)));
        assertEquals(XMaterial.BLUE_WOOL, LegacyItems.woolFor(TextColor.color(0x3C44AA)));
        assertEquals(XMaterial.ORANGE_WOOL, LegacyItems.woolFor(TextColor.color(0xF9801D)));
        assertEquals(XMaterial.PURPLE_WOOL, LegacyItems.woolFor(TextColor.color(0x8932B8)));
        assertEquals(XMaterial.WHITE_WOOL, LegacyItems.woolFor(TextColor.color(0xF9FFFE)));
    }

    @Test
    void nullColorFallsBackToSafeDefaults() {
        assertEquals(XMaterial.RED_STAINED_GLASS_PANE, LegacyItems.paneFor(null));
        assertEquals(XMaterial.WHITE_WOOL, LegacyItems.woolFor(null));
    }

    @Test
    void prettyKeyFormatsAdvancementAndItemPaths() {
        assertEquals("Mine Stone", LegacyItems.prettyKey("minecraft:story/mine_stone"));
        assertEquals("Iron Ingot", LegacyItems.prettyKey("minecraft:iron_ingot"));
        assertEquals("Golden Apple", LegacyItems.prettyKey("minecraft:golden_apple"));
        assertEquals("Stone", LegacyItems.prettyKey("minecraft:stone"));
    }

    @Test
    void prettyKeyHandlesPathsWithoutNamespace() {
        assertEquals("Diamond Sword", LegacyItems.prettyKey("diamond_sword"));
        assertEquals("Netherite Scrap", LegacyItems.prettyKey("netherite_scrap"));
    }
}
