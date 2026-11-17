package com.jifercraft.bingo.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.ClaimBoard;
import com.jifercraft.bingo.card.WinMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Persists an active round to disk and restores it after a restart or crash.
 * Writes go through a temporary file and an atomic move so a hard kill during
 * save never leaves a half-written file behind.
 */
public final class RoundStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record FirstClaimRecord(int cell, int teamId, long elapsedMs) {
    }

    public record SavedRoundData(
            long seed,
            String mode,
            String winMode,
            boolean lockout,
            String state,
            long elapsedMs,
            long timeLimitSeconds,
            List<String> items,
            List<ClaimBoard.ClaimRecord> claims,
            FirstClaimRecord firstClaim,
            Map<String, String> savedInventories,
            // Added in 0.2.0. Files written before that have none of these,
            // and Gson leaves them at 0 and null.
            int lives,
            Map<String, Integer> deaths,
            Map<String, String> savedGameModes
    ) {
    }

    public record RestoredRound(
            Round round,
            long timeLimitSeconds,
            Map<UUID, ItemStack[]> savedInventories,
            Round.State originalState,
            Map<UUID, GameMode> savedGameModes
    ) {
    }

    private RoundStorage() {
    }

    public static boolean exists(File file) {
        return file != null && file.exists() && file.length() > 0;
    }

    public static void save(File file, Round round, long timeLimitSeconds, Map<UUID, ItemStack[]> savedInventories) {
        save(file, round, timeLimitSeconds, savedInventories, Map.of());
    }

    public static void save(File file, Round round, long timeLimitSeconds, Map<UUID, ItemStack[]> savedInventories,
            Map<UUID, GameMode> savedGameModes) {
        write(file, toJson(round, timeLimitSeconds, savedInventories, savedGameModes));
    }

    /**
     * Snapshots the round as JSON. It reads live round state and serializes
     * item stacks, so call it on the main thread; {@link #write} can then run
     * anywhere.
     */
    public static String toJson(Round round, long timeLimitSeconds, Map<UUID, ItemStack[]> savedInventories,
            Map<UUID, GameMode> savedGameModes) {
        Map<String, Integer> deaths = new HashMap<>();
        round.deaths().forEach((id, count) -> deaths.put(id.toString(), count));
        Map<String, String> gameModes = new HashMap<>();
        savedGameModes.forEach((id, mode) -> gameModes.put(id.toString(), mode.name()));

        Map<String, String> encodedInventories = new HashMap<>();
        if (savedInventories != null) {
            for (Map.Entry<UUID, ItemStack[]> entry : savedInventories.entrySet()) {
                encodedInventories.put(entry.getKey().toString(), encodeInventory(entry.getValue()));
            }
        }

        FirstClaimRecord firstRecord = null;
        if (round.firstClaim() != null) {
            firstRecord = new FirstClaimRecord(
                    round.firstClaim().cell(),
                    round.firstClaim().teamId(),
                    round.firstClaim().elapsedMs()
            );
        }

        SavedRoundData data = new SavedRoundData(
                round.seed(),
                round.mode().name(),
                round.board().winMode().name(),
                round.board().isLockout(),
                round.state().name(),
                round.elapsedMs(),
                timeLimitSeconds,
                round.card().items(),
                round.board().allClaims(),
                firstRecord,
                encodedInventories,
                round.lives(),
                deaths,
                gameModes
        );

        return GSON.toJson(data);
    }

    public static void write(File file, String json) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmpFile = new File(parent, file.getName() + ".tmp");
        try {
            Files.writeString(tmpFile.toPath(), json, StandardCharsets.UTF_8);
            try {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some Windows filesystems do not support atomic moves across directories or existing targets.
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmpFile.toPath());
            } catch (IOException ignored) {
            }
            throw new UncheckedIOException("Failed to save round state to " + file.getPath(), e);
        }
    }

    public static RestoredRound load(File file) {
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            SavedRoundData data = GSON.fromJson(json, SavedRoundData.class);

            BingoGameMode mode = BingoGameMode.valueOf(data.mode());
            WinMode winMode = WinMode.valueOf(data.winMode());
            BingoCard card = new BingoCard(data.seed(), data.items());
            ClaimBoard board = new ClaimBoard(winMode, data.lockout());

            if (data.claims() != null) {
                for (ClaimBoard.ClaimRecord claim : data.claims()) {
                    board.claim(claim.cell(), claim.teamId(), claim.timeMs());
                }
            }

            Round.FirstClaim firstClaim = null;
            if (data.firstClaim() != null) {
                firstClaim = new Round.FirstClaim(
                        data.firstClaim().cell(),
                        data.firstClaim().teamId(),
                        data.firstClaim().elapsedMs()
                );
            }

            // Always restore in PAUSED state so players have time to reconnect.
            Round round = new Round(
                    data.seed(),
                    mode,
                    card,
                    board,
                    Round.State.PAUSED,
                    data.elapsedMs(),
                    firstClaim,
                    data.lives(),
                    parseIds(data.deaths(), Integer::valueOf)
            );

            Map<UUID, ItemStack[]> inventories = new HashMap<>();
            if (data.savedInventories() != null) {
                for (Map.Entry<String, String> entry : data.savedInventories().entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        ItemStack[] items = decodeInventory(entry.getValue());
                        inventories.put(uuid, items);
                    } catch (Exception e) {
                        // Skip corrupted player inventory without dropping the entire round.
                    }
                }
            }

            Round.State originalState = Round.State.valueOf(data.state());
            Map<UUID, GameMode> gameModes = parseIds(data.savedGameModes(), GameMode::valueOf);
            return new RestoredRound(round, data.timeLimitSeconds(), inventories, originalState, gameModes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load round state from " + file.getPath(), e);
        }
    }

    // Entries with a bad id or value are skipped; one broken player must not
    // cost the whole round.
    private static <V, R> Map<UUID, R> parseIds(Map<String, V> raw, Function<String, R> parse) {
        Map<UUID, R> parsed = new HashMap<>();
        if (raw == null) {
            return parsed;
        }
        raw.forEach((id, value) -> {
            try {
                parsed.put(UUID.fromString(id), parse.apply(String.valueOf(value)));
            } catch (IllegalArgumentException ignored) {
            }
        });
        return parsed;
    }

    public static boolean delete(File file) {
        boolean deleted = false;
        try {
            deleted = Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
        File tmpFile = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            Files.deleteIfExists(tmpFile.toPath());
        } catch (IOException ignored) {
        }
        return deleted;
    }

    @SuppressWarnings("deprecation")
    public static String encodeInventory(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return "";
        }
        try {
            byte[] bytes = ItemStack.serializeItemsAsBytes(items);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable t) {
            // Unit tests without Paper's server bridge fall back to Bukkit object streams.
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
                    oos.writeInt(items.length);
                    for (ItemStack item : items) {
                        oos.writeObject(item);
                    }
                }
                return Base64.getEncoder().encodeToString(baos.toByteArray());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to encode inventory", e);
            }
        }
    }

    @SuppressWarnings("deprecation")
    public static ItemStack[] decodeInventory(String base64) {
        if (base64 == null || base64.isBlank()) {
            return new ItemStack[0];
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        try {
            return ItemStack.deserializeItemsFromBytes(bytes);
        } catch (Throwable t) {
            // Unit tests without Paper's server bridge fall back to Bukkit object streams.
            try (BukkitObjectInputStream ois = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                int length = ois.readInt();
                ItemStack[] items = new ItemStack[length];
                for (int i = 0; i < length; i++) {
                    items[i] = (ItemStack) ois.readObject();
                }
                return items;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to decode inventory", e);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Class not found while decoding inventory", e);
            }
        }
    }
}
