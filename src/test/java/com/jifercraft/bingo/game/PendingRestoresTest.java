package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingRestoresTest {

    private static final Logger LOGGER = Logger.getAnonymousLogger();

    @Test
    void survivesARestart(@TempDir Path tempDir) {
        File file = tempDir.resolve("data/pending-restores.json").toFile();
        UUID player = UUID.randomUUID();

        PendingRestores first = new PendingRestores(file, LOGGER);
        first.addInventory(player, new ItemStack[0]);
        first.addGameMode(player, GameMode.SURVIVAL);
        first.close();

        PendingRestores second = new PendingRestores(file, LOGGER);
        PendingRestores.Entry entry = second.get(player);
        assertNotNull(entry);
        assertEquals("", entry.inventory());
        assertEquals("SURVIVAL", entry.gameMode());
    }

    @Test
    void firstStoredValueWins(@TempDir Path tempDir) {
        File file = tempDir.resolve("pending-restores.json").toFile();
        UUID player = UUID.randomUUID();

        PendingRestores restores = new PendingRestores(file, LOGGER);
        restores.addGameMode(player, GameMode.ADVENTURE);
        restores.addGameMode(player, GameMode.SPECTATOR);
        restores.close();

        assertEquals("ADVENTURE", restores.get(player).gameMode());
        assertNull(restores.get(UUID.randomUUID()));
    }

    @Test
    void brokenFileStartsEmpty(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("pending-restores.json").toFile();
        Files.writeString(file.toPath(), "{ not json");

        PendingRestores restores = new PendingRestores(file, LOGGER);

        assertNull(restores.get(UUID.randomUUID()));
        assertTrue(file.exists());
        assertFalse(file.length() == 0);
    }

    @Test
    void keepsAdvancementsAndReadsOldFiles(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("pending-restores.json").toFile();
        UUID old = UUID.randomUUID();
        // Shape written by 0.2.0-pre builds, before advancements were tracked.
        Files.writeString(file.toPath(), "{\"" + old + "\": {\"gameMode\": \"SURVIVAL\"}}");

        PendingRestores restores = new PendingRestores(file, LOGGER);
        assertEquals("SURVIVAL", restores.get(old).gameMode());
        assertNull(restores.get(old).advancements());

        UUID player = UUID.randomUUID();
        restores.addAdvancements(player, Map.of("minecraft:story/mine_stone", List.of("get_stone")));
        restores.close();

        PendingRestores reloaded = new PendingRestores(file, LOGGER);
        assertEquals(List.of("get_stone"), reloaded.get(player).advancements().get("minecraft:story/mine_stone"));
        assertEquals("SURVIVAL", reloaded.get(old).gameMode());
    }
}
