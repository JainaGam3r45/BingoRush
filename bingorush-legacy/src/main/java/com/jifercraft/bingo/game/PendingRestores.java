package com.jifercraft.bingo.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.jifercraft.bingo.compat.AdvancementSupport;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * What an offline player is owed when they log back in: the inventory a round
 * took from them, the game mode they had before being knocked out and the
 * advancements an advancement round set aside.
 * Players who are online when the round ends get theirs back on the spot and
 * never land here. Kept on disk, so a restart before the next login loses
 * nothing.
 */
final class PendingRestores {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>() { }.getType();

    // advancements is absent from files written before 0.2.0, so Gson leaves it null.
    // Not final: Gson fills these in when it reads the file.
    static final class Entry {
        private String inventory;
        private String gameMode;
        private Map<String, List<String>> advancements;

        public Entry(String inventory, String gameMode, Map<String, List<String>> advancements) {
            this.inventory = inventory;
            this.gameMode = gameMode;
            this.advancements = advancements;
        }

        public String inventory() {
            return inventory;
        }

        public String gameMode() {
            return gameMode;
        }

        public Map<String, List<String>> advancements() {
            return advancements;
        }
    }

    private final File file;
    private final Logger logger;
    private final QueuedFileWriter writer;
    private final AdvancementSupport advancements;
    private final Map<UUID, Entry> pending = new HashMap<>();

    PendingRestores(File file, Logger logger, AdvancementSupport advancements) {
        this.file = file;
        this.logger = logger;
        this.advancements = advancements;
        this.writer = new QueuedFileWriter(file, logger);
        load();
    }

    // Read once at startup, like the active round file.
    private void load() {
        if (!file.exists()) {
            return;
        }
        try {
            Map<String, Entry> raw = GSON.fromJson(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), MAP_TYPE);
            if (raw != null) {
                raw.forEach((id, entry) -> pending.put(UUID.fromString(id), entry));
            }
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not read " + file.getName() + ", pending restores are lost: " + e.getMessage());
        }
    }

    void addInventory(UUID playerId, ItemStack[] contents) {
        merge(playerId, RoundStorage.encodeInventory(contents), null, null);
    }

    void addGameMode(UUID playerId, GameMode mode) {
        merge(playerId, null, mode.name(), null);
    }

    void addAdvancements(UUID playerId, Map<String, List<String>> advancements) {
        if (!advancements.isEmpty()) {
            merge(playerId, null, null, advancements);
        }
    }

    // What was stored first wins: a second round the player also missed must
    // not replace the inventory they had before the first one.
    private void merge(UUID playerId, String inventory, String gameMode, Map<String, List<String>> advancements) {
        Entry old = pending.get(playerId);
        Map<String, List<String>> mergedAdvancements = new HashMap<>();
        if (advancements != null) {
            mergedAdvancements.putAll(advancements);
        }
        if (old != null && old.advancements() != null) {
            mergedAdvancements.putAll(old.advancements());
        }
        pending.put(playerId, new Entry(
                old != null && old.inventory() != null ? old.inventory() : inventory,
                old != null && old.gameMode() != null ? old.gameMode() : gameMode,
                mergedAdvancements.isEmpty() ? null : mergedAdvancements));
        save();
    }

    Entry get(UUID playerId) {
        return pending.get(playerId);
    }

    Set<UUID> players() {
        return new HashSet<>(pending.keySet());
    }

    /** Takes over an entry from another file, keeping what is already owed. */
    void adopt(UUID playerId, Entry entry) {
        if (entry != null) {
            merge(playerId, entry.inventory(), entry.gameMode(), entry.advancements());
        }
    }

    void forget(UUID playerId) {
        if (pending.remove(playerId) != null) {
            save();
        }
    }

    void clear() {
        if (!pending.isEmpty()) {
            pending.clear();
            save();
        }
    }

    /** Gives back whatever the player is owed. */
    void applyTo(Player player) {
        Entry entry = pending.remove(player.getUniqueId());
        if (entry == null) {
            return;
        }
        if (entry.inventory() != null) {
            player.getInventory().setContents(RoundStorage.decodeInventory(entry.inventory()));
        }
        if (entry.gameMode() != null) {
            try {
                player.setGameMode(GameMode.valueOf(entry.gameMode()));
            } catch (IllegalArgumentException e) {
                logger.warning("Unknown game mode " + entry.gameMode() + " for " + player.getName());
            }
        }
        if (entry.advancements() != null) {
            advancements.award(player, entry.advancements());
        }
        save();
    }

    /**
     * Gives back only the game mode, for a player who joins a new round: the
     * inventory waits, because the round is using theirs right now.
     */
    void applyGameMode(Player player) {
        Entry entry = pending.get(player.getUniqueId());
        if (entry == null || entry.gameMode() == null) {
            return;
        }
        if (entry.inventory() == null && entry.advancements() == null) {
            pending.remove(player.getUniqueId());
        } else {
            pending.put(player.getUniqueId(), new Entry(entry.inventory(), null, entry.advancements()));
        }
        try {
            player.setGameMode(GameMode.valueOf(entry.gameMode()));
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown game mode " + entry.gameMode() + " for " + player.getName());
        }
        save();
    }

    private void save() {
        if (pending.isEmpty()) {
            writer.delete();
            return;
        }
        Map<String, Entry> raw = new HashMap<>();
        pending.forEach((id, entry) -> raw.put(id.toString(), entry));
        writer.write(GSON.toJson(raw));
    }

    void close() {
        writer.close();
    }
}
