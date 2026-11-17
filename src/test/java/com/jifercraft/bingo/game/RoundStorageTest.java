package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.ClaimBoard;
import com.jifercraft.bingo.card.WinMode;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoundStorageTest {

    @SerializableAs("MockTestItemStack")
    public static class MockTestItemStack extends ItemStack implements ConfigurationSerializable {
        private final Material material;
        private final int amount;
        private final String displayName;
        private final List<String> lore;

        public MockTestItemStack(Material material, int amount, String displayName, List<String> lore) {
            super();
            this.material = material;
            this.amount = amount;
            this.displayName = displayName;
            this.lore = lore != null ? List.copyOf(lore) : List.of();
        }

        @SuppressWarnings("unchecked")
        public MockTestItemStack(Map<String, Object> map) {
            super();
            this.material = Material.valueOf((String) map.get("type"));
            this.amount = ((Number) map.get("amount")).intValue();
            this.displayName = (String) map.get("displayName");
            Object loreObj = map.get("lore");
            if (loreObj instanceof List<?> list) {
                this.lore = list.stream().map(Object::toString).toList();
            } else {
                this.lore = List.of();
            }
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        public String displayNameText() {
            return displayName;
        }

        public List<String> loreLines() {
            return lore;
        }

        @Override
        public Map<String, Object> serialize() {
            Map<String, Object> map = new HashMap<>();
            map.put("type", material.name());
            map.put("amount", amount);
            map.put("displayName", displayName);
            map.put("lore", new ArrayList<>(lore));
            return map;
        }

        public static MockTestItemStack deserialize(Map<String, Object> map) {
            return new MockTestItemStack(map);
        }
    }

    @BeforeAll
    static void setup() {
        ConfigurationSerialization.registerClass(MockTestItemStack.class, "MockTestItemStack");
    }

    @Test
    void testCompleteRoundWithLockoutAndClaims(@TempDir Path tempDir) {
        List<String> items = IntStream.range(0, 25).mapToObj(i -> "minecraft:item_" + i).toList();
        BingoCard card = new BingoCard(42L, items);
        ClaimBoard board = new ClaimBoard(WinMode.LINE, true);
        Round round = new Round(42L, BingoGameMode.ITEMS, card, board);

        round.beginRun();
        board.claim(0, 1, 1000L);
        board.claim(1, 2, 2000L);
        board.claim(6, 1, 3000L);
        // Lockout rejects cell 0 for team 2
        assertFalse(board.claim(0, 2, 3500L));
        board.claim(12, 1, 4000L);

        round.recordFirstClaim(0, 1, 1000L);
        round.pause();

        File saveFile = tempDir.resolve("active-round.json").toFile();
        RoundStorage.save(saveFile, round, 3600L, Map.of());

        assertTrue(RoundStorage.exists(saveFile));
        RoundStorage.RestoredRound restored = RoundStorage.load(saveFile);

        assertNotNull(restored);
        assertEquals(Round.State.PAUSED, restored.round().state());
        assertEquals(Round.State.PAUSED, restored.originalState());
        assertEquals(42L, restored.round().seed());
        assertEquals(BingoGameMode.ITEMS, restored.round().mode());
        assertEquals(WinMode.LINE, restored.round().board().winMode());
        assertTrue(restored.round().board().isLockout());
        assertEquals(3600L, restored.timeLimitSeconds());
        assertEquals(round.elapsedMs(), restored.round().elapsedMs());

        assertNotNull(restored.round().firstClaim());
        assertEquals(0, restored.round().firstClaim().cell());
        assertEquals(1, restored.round().firstClaim().teamId());
        assertEquals(1000L, restored.round().firstClaim().elapsedMs());

        assertEquals(3, restored.round().board().count(1));
        assertEquals(1, restored.round().board().count(2));
        assertEquals(1, restored.round().board().owner(0).orElse(-1));
        assertEquals(2, restored.round().board().owner(1).orElse(-1));
        assertEquals(1, restored.round().board().owner(6).orElse(-1));
        assertEquals(1, restored.round().board().owner(12).orElse(-1));
        assertEquals(1000L, restored.round().board().claimTime(0, 1));
        assertEquals(2000L, restored.round().board().claimTime(1, 2));

        assertEquals(items, restored.round().card().items());
    }

    @Test
    void testAdvancementRoundSerialization(@TempDir Path tempDir) {
        List<String> items = IntStream.range(0, 25).mapToObj(i -> "minecraft:story/adv_" + i).toList();
        BingoCard card = new BingoCard(99L, items);
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD, false);
        Round round = new Round(99L, BingoGameMode.ADVANCEMENTS, card, board);

        round.beginRun();
        board.claim(5, 10, 500L);
        // Non-lockout allows multiple teams on the same cell
        board.claim(5, 20, 800L);
        round.recordFirstClaim(5, 10, 500L);
        round.pause();

        File saveFile = tempDir.resolve("active-adv-round.json").toFile();
        RoundStorage.save(saveFile, round, 1200L, Map.of());

        RoundStorage.RestoredRound restored = RoundStorage.load(saveFile);
        assertEquals(BingoGameMode.ADVANCEMENTS, restored.round().mode());
        assertEquals(WinMode.FULL_CARD, restored.round().board().winMode());
        assertFalse(restored.round().board().isLockout());
        assertTrue(restored.round().board().isClaimed(5, 10));
        assertTrue(restored.round().board().isClaimed(5, 20));
        assertEquals(500L, restored.round().board().claimTime(5, 10));
        assertEquals(800L, restored.round().board().claimTime(5, 20));
    }

    @Test
    void testSavedInventoriesPreserveMetadata(@TempDir Path tempDir) {
        List<String> items = IntStream.range(0, 25).mapToObj(i -> "minecraft:item_" + i).toList();
        BingoCard card = new BingoCard(100L, items);
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD, false);
        Round round = new Round(100L, card, board);
        round.beginRun();
        round.pause();

        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        ItemStack[] invA = new ItemStack[]{
                new MockTestItemStack(Material.DIAMOND, 64, "Shiny Diamond", List.of("Ancient treasure", "Pristine condition")),
                null,
                new MockTestItemStack(Material.NETHERITE_SWORD, 1, "Excalibur", List.of("Sharpness V", "Unbreaking III"))
        };

        ItemStack[] invB = new ItemStack[]{
                new MockTestItemStack(Material.GOLDEN_APPLE, 8, "Enchanted Snack", List.of("Regeneration II"))
        };

        Map<UUID, ItemStack[]> saved = Map.of(playerA, invA, playerB, invB);

        File saveFile = tempDir.resolve("active-inventory-round.json").toFile();
        RoundStorage.save(saveFile, round, 1800L, saved);

        RoundStorage.RestoredRound restored = RoundStorage.load(saveFile);
        assertNotNull(restored.savedInventories());
        assertEquals(2, restored.savedInventories().size());

        ItemStack[] loadedA = restored.savedInventories().get(playerA);
        assertNotNull(loadedA);
        assertEquals(3, loadedA.length);

        MockTestItemStack item0 = (MockTestItemStack) loadedA[0];
        assertEquals(Material.DIAMOND, item0.getType());
        assertEquals(64, item0.getAmount());
        assertEquals("Shiny Diamond", item0.displayNameText());
        assertEquals(List.of("Ancient treasure", "Pristine condition"), item0.loreLines());

        assertNull(loadedA[1]);

        MockTestItemStack item2 = (MockTestItemStack) loadedA[2];
        assertEquals(Material.NETHERITE_SWORD, item2.getType());
        assertEquals(1, item2.getAmount());
        assertEquals("Excalibur", item2.displayNameText());
        assertEquals(List.of("Sharpness V", "Unbreaking III"), item2.loreLines());

        ItemStack[] loadedB = restored.savedInventories().get(playerB);
        assertNotNull(loadedB);
        assertEquals(1, loadedB.length);
        MockTestItemStack itemB0 = (MockTestItemStack) loadedB[0];
        assertEquals(Material.GOLDEN_APPLE, itemB0.getType());
        assertEquals(8, itemB0.getAmount());
        assertEquals("Enchanted Snack", itemB0.displayNameText());
        assertEquals(List.of("Regeneration II"), itemB0.loreLines());
    }

    @Test
    void testAtomicWriteAndCleanup(@TempDir Path tempDir) {
        List<String> items = IntStream.range(0, 25).mapToObj(i -> "minecraft:item_" + i).toList();
        BingoCard card = new BingoCard(101L, items);
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD);
        Round round = new Round(101L, card, board);

        File saveFile = tempDir.resolve("active-round.json").toFile();
        File tmpFile = tempDir.resolve("active-round.json.tmp").toFile();

        assertFalse(RoundStorage.exists(saveFile));
        RoundStorage.save(saveFile, round, 0L, Map.of());

        assertTrue(RoundStorage.exists(saveFile));
        assertFalse(tmpFile.exists());

        assertTrue(RoundStorage.delete(saveFile));
        assertFalse(RoundStorage.exists(saveFile));
        assertFalse(Files.exists(saveFile.toPath()));
    }

    @Test
    void livesAndGameModesSurviveARestart(@TempDir Path tempDir) {
        List<String> items = IntStream.range(0, 25).mapToObj(i -> "minecraft:item_" + i).toList();
        Round round = new Round(7L, BingoGameMode.ITEMS, new BingoCard(7L, items), new ClaimBoard(WinMode.FULL_CARD), 2);
        UUID out = UUID.randomUUID();
        UUID hurt = UUID.randomUUID();
        round.beginRun();
        round.recordDeath(out);
        round.recordDeath(out);
        round.recordDeath(hurt);

        File saveFile = tempDir.resolve("active-round.json").toFile();
        RoundStorage.save(saveFile, round, 0L, Map.of(), Map.of(out, GameMode.SURVIVAL));
        RoundStorage.RestoredRound restored = RoundStorage.load(saveFile);

        assertEquals(2, restored.round().lives());
        assertTrue(restored.round().isEliminated(out));
        assertEquals(1, restored.round().livesLeft(hurt));
        assertEquals(GameMode.SURVIVAL, restored.savedGameModes().get(out));
    }

    @Test
    void loadsAFileFromBeforeLives(@TempDir Path tempDir) throws Exception {
        // Shape of active-round.json as 0.2.0-pre.2 wrote it.
        List<String> items = IntStream.range(0, 25).mapToObj(i -> "\"minecraft:item_" + i + "\"").toList();
        String json = """
                {
                  "seed": 9,
                  "mode": "ITEMS",
                  "winMode": "LINE",
                  "lockout": false,
                  "state": "RUNNING",
                  "elapsedMs": 5000,
                  "timeLimitSeconds": 600,
                  "items": [%s],
                  "claims": [],
                  "savedInventories": {}
                }
                """.formatted(String.join(",", items));
        File saveFile = tempDir.resolve("active-round.json").toFile();
        Files.writeString(saveFile.toPath(), json);

        RoundStorage.RestoredRound restored = RoundStorage.load(saveFile);

        assertEquals(0, restored.round().lives());
        assertFalse(restored.round().isEliminated(UUID.randomUUID()));
        assertTrue(restored.savedGameModes().isEmpty());
        assertEquals(Round.State.RUNNING, restored.originalState());
    }
}
