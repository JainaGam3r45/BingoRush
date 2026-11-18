package com.jifercraft.bingo.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockJournalTest {

    private static final Logger LOGGER = Logger.getAnonymousLogger();

    @Test
    void recordsOriginalStateOnlyOnce() {
        BlockJournal journal = new BlockJournal();

        BlockJournalEntry original = new BlockJournalEntry("world", 10, 64, 20, "minecraft:stone", "decor", 1000L);
        BlockJournalEntry secondChange = new BlockJournalEntry("world", 10, 64, 20, "minecraft:snow_block", "decor", 2000L);

        assertTrue(journal.record(original));
        assertFalse(journal.record(secondChange));

        assertEquals(1, journal.size());
        BlockJournalEntry stored = journal.entries().get(0);
        assertEquals("minecraft:stone", stored.blockData());
        assertTrue(journal.isTracked("world", 10, 64, 20));
        assertFalse(journal.isTracked("world", 10, 65, 20));
    }

    @Test
    void rollbackReturnsEntriesInReverseLifoOrder() {
        BlockJournal journal = new BlockJournal();

        journal.record(new BlockJournalEntry("world", 1, 60, 1, "minecraft:dirt", "ground", 100L));
        journal.record(new BlockJournalEntry("world", 1, 61, 1, "minecraft:oak_log", "tree", 200L));
        journal.record(new BlockJournalEntry("world", 1, 62, 1, "minecraft:oak_leaves", "leaves", 300L));

        List<BlockJournalEntry> rollback = journal.rollbackEntries();
        assertEquals(3, rollback.size());
        assertEquals(62, rollback.get(0).y());
        assertEquals(61, rollback.get(1).y());
        assertEquals(60, rollback.get(2).y());
    }

    @Test
    void savesAndRestoresAtomicallyFromDisk(@TempDir Path tempDir) {
        File file = tempDir.resolve("block_journal.json").toFile();

        BlockJournal first = new BlockJournal();
        first.record(new BlockJournalEntry("world", 5, 70, 5, "minecraft:grass_block", "snow", 500L));
        first.record(new BlockJournalEntry("world", 6, 70, 5, "minecraft:stone", "snow", 600L));
        first.save(file);

        assertTrue(file.exists());

        BlockJournal loaded = BlockJournal.load(file, LOGGER);
        assertEquals(2, loaded.size());
        assertEquals("minecraft:grass_block", loaded.entries().get(0).blockData());
        assertEquals("minecraft:stone", loaded.entries().get(1).blockData());

        BlockJournal.delete(file);
        assertFalse(file.exists());
    }

    @Test
    void forgettingOneEntryKeepsTheOnesNotRestoredYet() {
        BlockJournal journal = new BlockJournal();
        BlockJournalEntry restored = new BlockJournalEntry("world", 1, 64, 1, "minecraft:air", "season_snow", 1L);
        BlockJournalEntry unloaded = new BlockJournalEntry("world", 900, 64, 900, "minecraft:air", "season_snow", 2L);
        journal.record(restored);
        journal.record(unloaded);

        journal.forget(restored);

        assertEquals(1, journal.size());
        assertFalse(journal.isTracked("world", 1, 64, 1));
        assertTrue(journal.isTracked("world", 900, 64, 900));
        // The coordinate is free again, so the next season can record it.
        assertTrue(journal.record(restored));
    }

    @Test
    void brokenFileLoadsEmptyWithoutCrashing(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("block_journal.json").toFile();
        Files.write(file.toPath(), "{ broken json".getBytes());

        BlockJournal journal = BlockJournal.load(file, LOGGER);
        assertEquals(0, journal.size());
        assertTrue(journal.isEmpty());
    }
}
