package com.jifercraft.bingo.config;

import com.jifercraft.bingo.card.ItemPool;
import com.jifercraft.bingo.card.ItemTier;
import com.jifercraft.bingo.card.WinMode;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemType;

/**
 * The parsed config.yml. Problems are collected while parsing and thrown all
 * at once, one line each, so an admin sees every mistake in one boot with the
 * key and value to fix. With problems around, the plugin stays enabled but a
 * round cannot start.
 */
public final class BingoConfig {

    /** The sounds an admin can change in config.yml. */
    public enum SoundId {
        CLAIM,
        WIN,
        COUNTDOWN,
        START;

        String configKey() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final WinMode winMode;
    private final long timeLimitSeconds;
    private final int countdownSeconds;
    private final int teamSize;
    private final boolean clearInventories;
    private final boolean scatterPlayers;
    private final int scatterRadius;
    private final LateJoin lateJoin;
    private final ItemPool pool;
    private final Map<String, ItemType> itemsByKey;
    private final Map<SoundId, Key> sounds;
    private final List<TeamDefinition> teamDefinitions;
    private final boolean updateNotifierEnabled;
    private final boolean updateNotifierOnJoin;

    private BingoConfig(WinMode winMode, long timeLimitSeconds, int countdownSeconds, int teamSize,
            boolean clearInventories, boolean scatterPlayers, int scatterRadius, LateJoin lateJoin,
            ItemPool pool, Map<String, ItemType> itemsByKey, Map<SoundId, Key> sounds,
            List<TeamDefinition> teamDefinitions, boolean updateNotifierEnabled, boolean updateNotifierOnJoin) {
        this.winMode = winMode;
        this.timeLimitSeconds = timeLimitSeconds;
        this.countdownSeconds = countdownSeconds;
        this.teamSize = teamSize;
        this.clearInventories = clearInventories;
        this.scatterPlayers = scatterPlayers;
        this.scatterRadius = scatterRadius;
        this.lateJoin = lateJoin;
        this.pool = pool;
        this.itemsByKey = itemsByKey;
        this.sounds = sounds;
        this.teamDefinitions = teamDefinitions;
        this.updateNotifierEnabled = updateNotifierEnabled;
        this.updateNotifierOnJoin = updateNotifierOnJoin;
    }

    public static BingoConfig load(FileConfiguration yaml) {
        List<String> problems = new ArrayList<>();

        WinMode winMode = WinMode.FULL_CARD;
        String rawMode = yaml.getString("win-mode", "full");
        if ("full".equalsIgnoreCase(rawMode)) {
            winMode = WinMode.FULL_CARD;
        } else if ("line".equalsIgnoreCase(rawMode)) {
            winMode = WinMode.LINE;
        } else {
            problems.add("win-mode is '" + rawMode + "', use 'full' or 'line'");
        }

        long timeLimit = yaml.getLong("time-limit", 1800);
        if (timeLimit < 0) {
            problems.add("time-limit is " + timeLimit + ", use 0 for no limit");
        }

        int countdown = yaml.getInt("countdown", 10);
        if (countdown < 0) {
            problems.add("countdown is " + countdown + ", use 0 to start right away");
        }

        int teamSize = yaml.getInt("team-size", 2);
        if (teamSize < 1 || teamSize > 64) {
            problems.add("team-size is " + teamSize + ", use between 1 and 64");
        }

        boolean clearInventories = yaml.getBoolean("clear-inventories", true);
        boolean scatterPlayers = yaml.getBoolean("scatter-players", true);
        int scatterRadius = yaml.getInt("scatter-radius", 24);
        if (scatterRadius < 1) {
            problems.add("scatter-radius is " + scatterRadius + ", it must be at least 1");
        }

        LateJoin lateJoin = LateJoin.JOIN;
        String rawLate = yaml.getString("late-join", "join");
        if ("join".equalsIgnoreCase(rawLate)) {
            lateJoin = LateJoin.JOIN;
        } else if ("spectate".equalsIgnoreCase(rawLate)) {
            lateJoin = LateJoin.SPECTATE;
        } else {
            problems.add("late-join is '" + rawLate + "', use 'join' or 'spectate'");
        }

        ItemPool pool = null;
        Map<String, ItemType> types = new HashMap<>();
        ConfigurationSection tiers = yaml.getConfigurationSection("card.tiers");
        if (tiers == null) {
            problems.add("There is no card.tiers section, so no card can be generated");
        } else {
            List<ItemTier> parsedTiers = new ArrayList<>();
            for (String id : tiers.getKeys(false)) {
                ConfigurationSection tier = tiers.getConfigurationSection(id);
                if (tier == null) {
                    problems.add("card.tiers." + id + " must be a section with 'cells' and 'items'");
                    continue;
                }
                int cells = tier.getInt("cells", -1);
                if (cells < 0) {
                    problems.add("card.tiers." + id + ".cells is missing or negative");
                    continue;
                }
                List<String> valid = new ArrayList<>();
                for (String raw : tier.getStringList("items")) {
                    // A plain "diamond" means "minecraft:diamond", the config
                    // should not make admins type the namespace for everything.
                    String normalized = raw.contains(":") ? raw : "minecraft:" + raw;
                    NamespacedKey key = NamespacedKey.fromString(normalized.toLowerCase(Locale.ROOT));
                    if (key == null) {
                        problems.add("card.tiers." + id + " has an item key that cannot be read: '" + raw + "'");
                        continue;
                    }
                    ItemType type = RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM).get(key);
                    if (type == null) {
                        problems.add("card.tiers." + id + " has an item that does not exist: '" + raw + "'");
                        continue;
                    }
                    valid.add(key.toString());
                    types.put(key.toString(), type);
                }
                parsedTiers.add(new ItemTier(id, cells, valid));
            }
            try {
                pool = new ItemPool(parsedTiers);
            } catch (IllegalArgumentException e) {
                problems.add("The item pool cannot fill a card: " + e.getMessage());
            }
        }

        Map<SoundId, Key> sounds = new EnumMap<>(SoundId.class);
        for (SoundId id : SoundId.values()) {
            String raw = yaml.getString("sounds." + id.configKey());
            if (raw == null) {
                continue;
            }
            try {
                Key key = Key.key(raw.toLowerCase(Locale.ROOT));
                NamespacedKey namespaced = new NamespacedKey(key.namespace(), key.value());
                if (RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT).get(namespaced) == null) {
                    problems.add("sounds." + id.configKey() + " is '" + raw + "', which is not a sound key");
                    continue;
                }
                sounds.put(id, key);
            } catch (InvalidKeyException e) {
                problems.add("sounds." + id.configKey() + " is '" + raw + "', which is not a sound key");
            }
        }

        List<TeamDefinition> teamDefinitions = new ArrayList<>();
        List<Map<?, ?>> rawTeams = yaml.getMapList("teams");
        Set<String> teamNames = new HashSet<>();
        for (int i = 0; i < rawTeams.size(); i++) {
            Map<?, ?> entry = rawTeams.get(i);
            Object name = entry.get("name");
            if (name == null || name.toString().isBlank()) {
                problems.add("teams." + i + " has no name");
                continue;
            }
            if (!teamNames.add(name.toString())) {
                problems.add("The team '" + name + "' appears twice in the teams list");
                continue;
            }
            TextColor color = TextColor.fromHexString(entry.get("color") == null ? null : entry.get("color").toString());
            if (color == null) {
                problems.add("teams." + i + " has a color that is not a hex color like #FF5555");
                continue;
            }
            teamDefinitions.add(new TeamDefinition(name.toString(), color.value()));
        }

        boolean updateNotifierEnabled = yaml.getBoolean("update-notifier.enabled", true);
        boolean updateNotifierOnJoin = yaml.getBoolean("update-notifier.on-join", true);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", problems));
        }
        return new BingoConfig(winMode, timeLimit, countdown, teamSize, clearInventories,
                scatterPlayers, scatterRadius, lateJoin, pool, types, sounds, teamDefinitions,
                updateNotifierEnabled, updateNotifierOnJoin);
    }

    public WinMode winMode() {
        return winMode;
    }

    /** 0 means the round has no clock. */
    public long timeLimitSeconds() {
        return timeLimitSeconds;
    }

    public int countdownSeconds() {
        return countdownSeconds;
    }

    public int teamSize() {
        return teamSize;
    }

    public boolean clearInventories() {
        return clearInventories;
    }

    public boolean scatterPlayers() {
        return scatterPlayers;
    }

    public int scatterRadius() {
        return scatterRadius;
    }

    public LateJoin lateJoin() {
        return lateJoin;
    }

    public ItemPool pool() {
        return pool;
    }

    public ItemType itemType(String itemKey) {
        return itemsByKey.get(itemKey);
    }

    /** The sound for one event, or null when the admin left it empty. */
    public Key sound(SoundId id) {
        return sounds.get(id);
    }

    /** The teams from config.yml, in order. Empty when the admin wants them made on the fly. */
    public List<TeamDefinition> teamDefinitions() {
        return teamDefinitions;
    }

    public boolean updateNotifierEnabled() {
        return updateNotifierEnabled;
    }

    public boolean updateNotifierOnJoin() {
        return updateNotifierOnJoin;
    }
}
