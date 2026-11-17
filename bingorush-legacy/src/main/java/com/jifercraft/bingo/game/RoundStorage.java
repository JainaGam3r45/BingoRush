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
import java.util.Collections;
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

    public static final class FirstClaimRecord {
        private int cell;
        private int teamId;
        private long elapsedMs;

        public FirstClaimRecord(int cell, int teamId, long elapsedMs) {
            this.cell = cell;
            this.teamId = teamId;
            this.elapsedMs = elapsedMs;
        }

        public int cell() {
            return cell;
        }

        public int teamId() {
            return teamId;
        }

        public long elapsedMs() {
            return elapsedMs;
        }
    }

    // Not final: Gson fills these in by reflection. Files from before 0.2.0 lack the
    // last four fields, and Gson leaves them at 0 and null.
    public static final class SavedRoundData {
        private long seed;
        private String mode;
        private String winMode;
        private boolean lockout;
        private String state;
        private long elapsedMs;
        private long timeLimitSeconds;
        private List<String> items;
        private List<ClaimBoard.ClaimRecord> claims;
        private FirstClaimRecord firstClaim;
        private Map<String, String> savedInventories;
        private int lives;
        private Map<String, Integer> deaths;
        private Map<String, String> savedGameModes;
        private Map<String, Map<String, List<String>>> revokedAdvancements;

        public SavedRoundData(long seed, String mode, String winMode, boolean lockout, String state, long elapsedMs, long timeLimitSeconds, List<String> items, List<ClaimBoard.ClaimRecord> claims, FirstClaimRecord firstClaim, Map<String, String> savedInventories, int lives, Map<String, Integer> deaths, Map<String, String> savedGameModes, Map<String, Map<String, List<String>>> revokedAdvancements) {
            this.seed = seed;
            this.mode = mode;
            this.winMode = winMode;
            this.lockout = lockout;
            this.state = state;
            this.elapsedMs = elapsedMs;
            this.timeLimitSeconds = timeLimitSeconds;
            this.items = items;
            this.claims = claims;
            this.firstClaim = firstClaim;
            this.savedInventories = savedInventories;
            this.lives = lives;
            this.deaths = deaths;
            this.savedGameModes = savedGameModes;
            this.revokedAdvancements = revokedAdvancements;
        }

        public long seed() {
            return seed;
        }

        public String mode() {
            return mode;
        }

        public String winMode() {
            return winMode;
        }

        public boolean lockout() {
            return lockout;
        }

        public String state() {
            return state;
        }

        public long elapsedMs() {
            return elapsedMs;
        }

        public long timeLimitSeconds() {
            return timeLimitSeconds;
        }

        public List<String> items() {
            return items;
        }

        public List<ClaimBoard.ClaimRecord> claims() {
            return claims;
        }

        public FirstClaimRecord firstClaim() {
            return firstClaim;
        }

        public Map<String, String> savedInventories() {
            return savedInventories;
        }

        public int lives() {
            return lives;
        }

        public Map<String, Integer> deaths() {
            return deaths;
        }

        public Map<String, String> savedGameModes() {
            return savedGameModes;
        }

        public Map<String, Map<String, List<String>>> revokedAdvancements() {
            return revokedAdvancements;
        }
    }

    public static final class RestoredRound {
        private final Round round;
        private final long timeLimitSeconds;
        private final Map<UUID, ItemStack[]> savedInventories;
        private final Round.State originalState;
        private final Map<UUID, GameMode> savedGameModes;
        private final Map<UUID, Map<String, List<String>>> revokedAdvancements;

        public RestoredRound(Round round, long timeLimitSeconds, Map<UUID, ItemStack[]> savedInventories, Round.State originalState, Map<UUID, GameMode> savedGameModes, Map<UUID, Map<String, List<String>>> revokedAdvancements) {
            this.round = round;
            this.timeLimitSeconds = timeLimitSeconds;
            this.savedInventories = savedInventories;
            this.originalState = originalState;
            this.savedGameModes = savedGameModes;
            this.revokedAdvancements = revokedAdvancements;
        }

        public Round round() {
            return round;
        }

        public long timeLimitSeconds() {
            return timeLimitSeconds;
        }

        public Map<UUID, ItemStack[]> savedInventories() {
            return savedInventories;
        }

        public Round.State originalState() {
            return originalState;
        }

        public Map<UUID, GameMode> savedGameModes() {
            return savedGameModes;
        }

        public Map<UUID, Map<String, List<String>>> revokedAdvancements() {
            return revokedAdvancements;
        }
    }

    private RoundStorage() {
    }

    public static boolean exists(File file) {
        return file != null && file.exists() && file.length() > 0;
    }

    public static void save(File file, Round round, long timeLimitSeconds, Map<UUID, ItemStack[]> savedInventories) {
        save(file, round, timeLimitSeconds, savedInventories, Collections.<UUID, GameMode>emptyMap());
    }

    public static void save(File file, Round round, long timeLimitSeconds, Map<UUID, ItemStack[]> savedInventories,
            Map<UUID, GameMode> savedGameModes) {
        write(file, toJson(round, timeLimitSeconds, savedInventories, savedGameModes, Collections.<String, Map<String, List<String>>>emptyMap()));
    }

    public static void save(File file, Round round, long timeLimitSeconds, Map<UUID, ItemStack[]> savedInventories,
            Map<UUID, GameMode> savedGameModes, Map<String, Map<String, List<String>>> revokedAdvancements) {
        write(file, toJson(round, timeLimitSeconds, savedInventories, savedGameModes, revokedAdvancements));
    }

    /**
     * Snapshots the round as JSON. It reads live round state and serializes
     * item stacks, so call it on the main thread; {@link #write} can then run
     * anywhere.
     */
    public static String toJson(Round round, long timeLimitSeconds, Map<UUID, ItemStack[]> savedInventories,
            Map<UUID, GameMode> savedGameModes, Map<String, Map<String, List<String>>> revokedAdvancements) {
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
                gameModes,
                revokedAdvancements
        );

        return GSON.toJson(data);
    }

    public static void write(File file, String json) {
        AtomicFiles.write(file, json);
    }

    public static RestoredRound load(File file) {
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
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
            Map<UUID, Map<String, List<String>>> revoked = new HashMap<>();
            if (data.revokedAdvancements() != null) {
                data.revokedAdvancements().forEach((id, owed) -> {
                    try {
                        revoked.put(UUID.fromString(id), owed);
                    } catch (IllegalArgumentException ignored) {
                    }
                });
            }
            return new RestoredRound(round, data.timeLimitSeconds(), inventories, originalState, gameModes, revoked);
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
        return AtomicFiles.delete(file);
    }

    public static String encodeInventory(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return "";
        }
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

    public static ItemStack[] decodeInventory(String base64) {
        if (base64 == null || base64.trim().isEmpty()) {
            return new ItemStack[0];
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
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
