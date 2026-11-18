package com.jifercraft.bingo.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jifercraft.bingo.game.AtomicFiles;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Every block the season changed and what stood there before. Only the first
 * state of a spot is kept, so putting the entries back newest first leaves
 * the world as it was. Lives on disk, so a crash does not lose track of the
 * snow in the lobby.
 */
public final class BlockJournal {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Storage {
        private List<BlockJournalEntry> entries = new ArrayList<>();
    }

    private final List<BlockJournalEntry> entries = new ArrayList<>();
    private final Set<String> trackedCoordinates = new HashSet<>();

    public BlockJournal() {
    }

    public synchronized boolean record(BlockJournalEntry entry) {
        if (entry == null) {
            return false;
        }
        String key = entry.coordinateKey();
        if (trackedCoordinates.contains(key)) {
            // Already recorded: what counts is the block from before the season.
            return false;
        }
        trackedCoordinates.add(key);
        entries.add(entry);
        return true;
    }

    public synchronized boolean isTracked(String world, int x, int y, int z) {
        return trackedCoordinates.contains(world + ":" + x + "," + y + "," + z);
    }

    public synchronized List<BlockJournalEntry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Newest first, so a block placed on top of another comes off before it. */
    public synchronized List<BlockJournalEntry> rollbackEntries() {
        List<BlockJournalEntry> reversed = new ArrayList<>(entries);
        Collections.reverse(reversed);
        return Collections.unmodifiableList(reversed);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized void clear() {
        entries.clear();
        trackedCoordinates.clear();
    }

    /**
     * Drops one entry after its block was put back. Entries whose chunk was
     * not loaded stay, so the next restore still knows about them.
     */
    public synchronized void forget(BlockJournalEntry entry) {
        if (entries.remove(entry)) {
            trackedCoordinates.remove(entry.coordinateKey());
        }
    }

    public synchronized void save(File file) {
        Storage storage = new Storage();
        storage.entries = new ArrayList<>(entries);
        String json = GSON.toJson(storage);
        AtomicFiles.write(file, json);
    }

    public static BlockJournal load(File file, Logger logger) {
        BlockJournal journal = new BlockJournal();
        if (file == null || !file.exists()) {
            return journal;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            Storage storage = GSON.fromJson(reader, Storage.class);
            if (storage != null && storage.entries != null) {
                for (BlockJournalEntry entry : storage.entries) {
                    journal.record(entry);
                }
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("Could not read block journal " + file.getName() + ": " + e.getMessage());
            }
        }
        return journal;
    }

    public static boolean delete(File file) {
        if (file == null) {
            return false;
        }
        return AtomicFiles.delete(file);
    }
}
