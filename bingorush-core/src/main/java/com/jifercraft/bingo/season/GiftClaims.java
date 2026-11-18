package com.jifercraft.bingo.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jifercraft.bingo.game.AtomicFiles;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Which gifts each player found, kept on disk so nobody finds one twice. */
public final class GiftClaims {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Storage {
        private Map<String, Set<String>> claims = new HashMap<>();
    }

    private final Map<UUID, Set<String>> playerClaims = new HashMap<>();

    public GiftClaims() {
    }

    public synchronized boolean recordClaim(UUID player, String giftId) {
        if (player == null || giftId == null) {
            return false;
        }
        Set<String> set = playerClaims.computeIfAbsent(player, k -> new HashSet<>());
        return set.add(giftId);
    }

    public synchronized boolean hasClaimed(UUID player, String giftId) {
        if (player == null || giftId == null) {
            return false;
        }
        Set<String> set = playerClaims.get(player);
        return set != null && set.contains(giftId);
    }

    public synchronized Set<String> claimedGifts(UUID player) {
        if (player == null) {
            return Collections.emptySet();
        }
        Set<String> set = playerClaims.get(player);
        return set != null ? Collections.unmodifiableSet(new HashSet<>(set)) : Collections.emptySet();
    }

    public synchronized int claimedCount(UUID player) {
        if (player == null) {
            return 0;
        }
        Set<String> set = playerClaims.get(player);
        return set != null ? set.size() : 0;
    }

    public synchronized void reset(UUID player) {
        if (player != null) {
            playerClaims.remove(player);
        }
    }

    public synchronized void resetAll() {
        playerClaims.clear();
    }

    public synchronized void save(File destination) {
        if (destination == null) {
            return;
        }
        Storage storage = new Storage();
        for (Map.Entry<UUID, Set<String>> entry : playerClaims.entrySet()) {
            storage.claims.put(entry.getKey().toString(), new HashSet<>(entry.getValue()));
        }
        String json = GSON.toJson(storage);
        AtomicFiles.write(destination, json);
    }

    public static GiftClaims load(File file, Logger logger) {
        GiftClaims result = new GiftClaims();
        if (file == null || !file.exists()) {
            return result;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            Storage storage = GSON.fromJson(reader, Storage.class);
            if (storage != null && storage.claims != null) {
                for (Map.Entry<String, Set<String>> entry : storage.claims.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        if (entry.getValue() != null) {
                            result.playerClaims.put(uuid, new HashSet<>(entry.getValue()));
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("Could not read seasonal gift claims from " + file.getPath() + ": " + e.getMessage());
            }
        }
        return result;
    }
}
