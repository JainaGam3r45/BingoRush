package com.jifercraft.bingo.config;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XSound;
import com.jifercraft.bingo.card.ItemPool;
import com.jifercraft.bingo.card.ItemTier;
import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.compat.ServerVersion;
import com.jifercraft.bingo.game.BingoGameMode;
import com.jifercraft.bingo.season.SeasonSettings;
import com.jifercraft.bingo.season.SeasonWindow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The parsed config.yml. Problems are collected while parsing and thrown all
 * at once, one line each, so an admin sees every mistake in one boot with the
 * key and value to fix. With problems around, the plugin stays enabled but a
 * round cannot start.
 */
public final class BingoConfig {

    private static final Pattern ADVANCEMENT_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * Set by the 1.12 support code when the server has advancements, so the
     * pool can be checked against the real list; null on older servers.
     */
    public static Predicate<String> advancementExists;

    /** The sounds an admin can change in config.yml. */
    public enum SoundId {
        CLAIM,
        WIN,
        COUNTDOWN,
        START,
        LOCKOUT_LOST,
        MENU_CLICK,
        TEAM_CHIME;

        String configKey() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    private final BingoGameMode gameMode;
    private final WinMode winMode;
    private final boolean lockout;
    private final int lives;
    private final ChatStyle deathChat;
    private final ChatStyle advancementChat;
    private final boolean advancementTab;
    private final boolean tabEnabled;
    private final boolean chatEnabled;
    private final long timeLimitSeconds;
    private final int countdownSeconds;
    private final int teamSize;
    private final boolean clearInventories;
    private final boolean scatterPlayers;
    private final int scatterRadius;
    private final LateJoin lateJoin;
    private final ItemPool pool;
    private final ItemPool advancementPool;
    private final Map<String, XMaterial> itemsByKey;
    private final Map<SoundId, XSound> sounds;
    private final List<String> notes;
    private final List<TeamDefinition> teamDefinitions;
    private final boolean updateNotifierEnabled;
    private final boolean updateNotifierOnJoin;
    private final int updateCheckIntervalHours;
    private final boolean historyEnabled;
    private final int historyMaxSavedMatches;
    private final String lobbyWorld;
    private final double lobbyX;
    private final double lobbyY;
    private final double lobbyZ;
    private final float lobbyYaw;
    private final float lobbyPitch;
    private final Map<String, GameMapDefinition> maps;
    private final SeasonSettings seasonSettings;

    private BingoConfig(BingoGameMode gameMode, WinMode winMode, boolean lockout, int lives,
            ChatStyle deathChat, ChatStyle advancementChat, boolean advancementTab, boolean tabEnabled, boolean chatEnabled,
            long timeLimitSeconds,
            int countdownSeconds, int teamSize,
            boolean clearInventories, boolean scatterPlayers, int scatterRadius, LateJoin lateJoin,
            ItemPool pool, ItemPool advancementPool, Map<String, XMaterial> itemsByKey,
            Map<SoundId, XSound> sounds, List<String> notes,
            List<TeamDefinition> teamDefinitions, boolean updateNotifierEnabled, boolean updateNotifierOnJoin,
            int updateCheckIntervalHours, boolean historyEnabled, int historyMaxSavedMatches,
            String lobbyWorld, double lobbyX, double lobbyY, double lobbyZ, float lobbyYaw, float lobbyPitch,
            Map<String, GameMapDefinition> maps, SeasonSettings seasonSettings) {
        this.gameMode = gameMode;
        this.winMode = winMode;
        this.lockout = lockout;
        this.lives = lives;
        this.deathChat = deathChat;
        this.advancementChat = advancementChat;
        this.advancementTab = advancementTab;
        this.tabEnabled = tabEnabled;
        this.chatEnabled = chatEnabled;
        this.timeLimitSeconds = timeLimitSeconds;
        this.countdownSeconds = countdownSeconds;
        this.teamSize = teamSize;
        this.clearInventories = clearInventories;
        this.scatterPlayers = scatterPlayers;
        this.scatterRadius = scatterRadius;
        this.lateJoin = lateJoin;
        this.pool = pool;
        this.advancementPool = advancementPool;
        this.itemsByKey = itemsByKey;
        this.notes = notes;
        this.sounds = sounds;
        this.teamDefinitions = teamDefinitions;
        this.updateNotifierEnabled = updateNotifierEnabled;
        this.updateNotifierOnJoin = updateNotifierOnJoin;
        this.updateCheckIntervalHours = updateCheckIntervalHours;
        this.historyEnabled = historyEnabled;
        this.historyMaxSavedMatches = historyMaxSavedMatches;
        this.lobbyWorld = lobbyWorld;
        this.lobbyX = lobbyX;
        this.lobbyY = lobbyY;
        this.lobbyZ = lobbyZ;
        this.lobbyYaw = lobbyYaw;
        this.lobbyPitch = lobbyPitch;
        this.maps = maps;
        this.seasonSettings = seasonSettings;
    }

    public static BingoConfig load(FileConfiguration yaml) {
        List<String> problems = new ArrayList<>();

        BingoGameMode gameMode = BingoGameMode.ITEMS;
        String rawGameMode = yaml.getString("game-mode", "items");
        BingoGameMode parsedMode = BingoGameMode.parse(rawGameMode);
        if (parsedMode != null) {
            gameMode = parsedMode;
        } else {
            problems.add("game-mode is '" + rawGameMode + "', use 'items' or 'advancements'");
        }

        WinMode winMode = WinMode.FULL_CARD;
        String rawMode = yaml.getString("win-mode", "full");
        if ("full".equalsIgnoreCase(rawMode)) {
            winMode = WinMode.FULL_CARD;
        } else if ("line".equalsIgnoreCase(rawMode)) {
            winMode = WinMode.LINE;
        } else {
            problems.add("win-mode is '" + rawMode + "', use 'full' or 'line'");
        }

        boolean lockout = yaml.getBoolean("lockout", false);

        int lives = yaml.getInt("lives", 0);
        if (lives < 0) {
            problems.add("lives is " + lives + ", use 0 for unlimited or 1 for hardcore");
        }

        ChatStyle deathChat = chatStyle(yaml, "round-chat.deaths", problems);
        ChatStyle advancementChat = chatStyle(yaml, "round-chat.advancements", problems);
        boolean advancementTab = yaml.getBoolean("advancement-tab", true);
        boolean tabEnabled = yaml.getBoolean("tab.enabled", false);
        boolean chatEnabled = yaml.getBoolean("chat.enabled", false);

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
        Map<String, XMaterial> types = new HashMap<>();
        List<String> notes = new ArrayList<>();
        ConfigurationSection tiers = yaml.getConfigurationSection("card.tiers");
        if (tiers == null) {
            problems.add("There is no card.tiers section, so no card can be generated");
        } else {
            List<ItemTier> parsedTiers = new ArrayList<>();
            List<String> missing = new ArrayList<>();
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
                    String normalized = (raw.contains(":") ? raw : "minecraft:" + raw).toLowerCase(Locale.ROOT);
                    String name = normalized.substring(normalized.indexOf(':') + 1);
                    Optional<XMaterial> material = XMaterial.matchXMaterial(name.toUpperCase(Locale.ROOT));
                    if (!material.isPresent()) {
                        problems.add("card.tiers." + id + " has an item that does not exist: '" + raw + "'");
                        continue;
                    }
                    // Items newer than the server (copper on 1.8, say) are
                    // skipped, so one config works on every version.
                    if (!material.get().isSupported()) {
                        missing.add(name);
                        continue;
                    }
                    valid.add(normalized);
                    types.put(normalized, material.get());
                }
                parsedTiers.add(new ItemTier(id, cells, valid));
            }
            if (!missing.isEmpty()) {
                notes.add("Skipping items this server version does not have: " + String.join(", ", missing));
            }
            try {
                pool = new ItemPool(parsedTiers);
            } catch (IllegalArgumentException e) {
                problems.add("The item pool cannot fill a card: " + e.getMessage());
            }
        }

        ItemPool advancementPool = null;
        ConfigurationSection advTiers = yaml.getConfigurationSection("advancement-pool.tiers");
        if (advTiers == null) {
            advTiers = yaml.getConfigurationSection("advancement-card.tiers");
        }
        if (!ServerVersion.atLeast(12)) {
            // Advancements arrived in 1.12; the pool is ignored, not an error.
            advTiers = null;
        } else if (advTiers == null) {
            problems.add("There is no advancement-pool.tiers section, so no advancement card can be generated");
        }
        if (advTiers != null) {
            List<ItemTier> parsedAdvTiers = new ArrayList<>();
            List<String> missingAdvancements = new ArrayList<>();
            for (String id : advTiers.getKeys(false)) {
                ConfigurationSection tier = advTiers.getConfigurationSection(id);
                if (tier == null) {
                    problems.add("advancement-pool.tiers." + id + " must be a section with 'cells' and 'advancements'");
                    continue;
                }
                int cells = tier.getInt("cells", -1);
                if (cells < 0) {
                    problems.add("advancement-pool.tiers." + id + ".cells is missing or negative");
                    continue;
                }
                List<String> rawAdvancements = tier.getStringList("advancements");
                if (rawAdvancements.isEmpty()) {
                    rawAdvancements = tier.getStringList("items");
                }
                List<String> valid = new ArrayList<>();
                for (String raw : rawAdvancements) {
                    String normalized = raw.contains(":") ? raw : "minecraft:" + raw;
                    String key = normalized.toLowerCase(Locale.ROOT);
                    if (!ADVANCEMENT_KEY.matcher(key).matches()) {
                        problems.add("advancement-pool.tiers." + id + " has an advancement key that cannot be read: '" + raw + "'");
                        continue;
                    }
                    if (key.startsWith("minecraft:recipes/")) {
                        problems.add("advancement-pool.tiers." + id + " lists a recipe unlock, only real advancements are allowed: '" + raw + "'");
                        continue;
                    }
                    // Like items, advancements newer than the server are left
                    // out, so one config works across versions.
                    if (advancementExists != null && !advancementExists.test(key)) {
                        missingAdvancements.add(key);
                        continue;
                    }
                    valid.add(key);
                }
                parsedAdvTiers.add(new ItemTier(id, cells, valid));
            }
            if (!missingAdvancements.isEmpty()) {
                notes.add("Skipping advancements this server version does not have: " + String.join(", ", missingAdvancements));
            }
            try {
                advancementPool = new ItemPool(parsedAdvTiers);
            } catch (IllegalArgumentException e) {
                problems.add("The advancement pool cannot fill a card: " + e.getMessage());
            }
        }

        Map<SoundId, XSound> sounds = new EnumMap<>(SoundId.class);
        List<String> silent = new ArrayList<>();
        for (SoundId id : SoundId.values()) {
            String raw = yaml.getString("sounds." + id.configKey());
            if (raw == null) {
                continue;
            }
            // Sound keys as Minecraft writes them now ("entity.player.levelup");
            // XSound maps them to whatever this version calls them.
            String name = raw.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace('.', '_').toUpperCase(Locale.ROOT);
            Optional<XSound> sound = XSound.of(name);
            if (!sound.isPresent()) {
                problems.add("sounds." + id.configKey() + " is '" + raw + "', which is not a sound key");
                continue;
            }
            if (!sound.get().isSupported()) {
                silent.add(id.configKey());
                continue;
            }
            sounds.put(id, sound.get());
        }
        if (!silent.isEmpty()) {
            notes.add("These sounds do not exist on this server version and stay silent: " + String.join(", ", silent));
        }

        List<TeamDefinition> teamDefinitions = new ArrayList<>();
        List<Map<?, ?>> rawTeams = yaml.getMapList("teams");
        Set<String> teamNames = new HashSet<>();
        for (int i = 0; i < rawTeams.size(); i++) {
            Map<?, ?> entry = rawTeams.get(i);
            Object name = entry.get("name");
            if (name == null || name.toString().trim().isEmpty()) {
                problems.add("teams." + i + " has no name");
                continue;
            }
            if (!teamNames.add(name.toString())) {
                problems.add("The team '" + name + "' appears twice in the teams list");
                continue;
            }
            // fromHexString throws on null in current Adventure, so a team with
            // no color has to be caught here or the whole plugin fails to enable.
            Object rawColor = entry.get("color");
            if (rawColor == null) {
                problems.add("teams." + i + " (" + name + ") has no color, add one like color: \"#FF5555\"");
                continue;
            }
            TextColor color = TextColor.fromHexString(rawColor.toString());
            if (color == null) {
                problems.add("teams." + i + " (" + name + ") has a color that is not a hex color like #FF5555");
                continue;
            }
            teamDefinitions.add(new TeamDefinition(name.toString(), color.value()));
        }

        boolean updateNotifierEnabled = yaml.getBoolean("update-notifier.enabled", true);
        boolean updateNotifierOnJoin = yaml.getBoolean("update-notifier.on-join", true);
        int updateCheckIntervalHours = yaml.getInt("update-notifier.check-interval-hours", 24);

        boolean historyEnabled = yaml.getBoolean("history.enabled", true);
        int historyMaxSavedMatches = yaml.getInt("history.max-saved-matches", 50);
        if (historyMaxSavedMatches < 0) {
            problems.add("history.max-saved-matches must be 0 or greater");
        }

        String lobbyWorld = yaml.getString("lobby.world", "");
        double lobbyX = yaml.getDouble("lobby.x", 0.0);
        double lobbyY = yaml.getDouble("lobby.y", 0.0);
        double lobbyZ = yaml.getDouble("lobby.z", 0.0);
        float lobbyYaw = (float) yaml.getDouble("lobby.yaw", 0.0);
        float lobbyPitch = (float) yaml.getDouble("lobby.pitch", 0.0);

        Map<String, GameMapDefinition> maps = new HashMap<>();
        ConfigurationSection mapsSec = yaml.getConfigurationSection("maps");
        if (mapsSec != null) {
            for (String key : mapsSec.getKeys(false)) {
                ConfigurationSection mapSec = mapsSec.getConfigurationSection(key);
                if (mapSec != null) {
                    String name = mapSec.getString("name", key);
                    String seed = mapSec.getString("seed", "random");
                    String modeStr = mapSec.getString("mode", "items");
                    BingoGameMode mapMode = "advancements".equalsIgnoreCase(modeStr) ? BingoGameMode.ADVANCEMENTS : BingoGameMode.ITEMS;
                    long limit = mapSec.getLong("time-limit", timeLimit);
                    maps.put(key.toLowerCase(Locale.ROOT), new GameMapDefinition(key, name, seed, mapMode, limit));
                }
            }
        }

        SeasonSettings seasonSettings;
        boolean seasonEnabled = yaml.getBoolean("season.enabled", false);
        String preStart = yaml.getString("season.pre-season-start", "2026-11-25T00:00:00Z");
        String actStart = yaml.getString("season.active-start", "2026-12-01T00:00:00Z");
        String actEnd = yaml.getString("season.active-end", "2026-12-31T23:59:59Z");
        String wrapEnd = yaml.getString("season.wrap-up-end", "2027-01-03T23:59:59Z");
        boolean snowfall = yaml.getBoolean("season.snowfall", true);
        boolean claimFirework = yaml.getBoolean("season.claim-firework", true);
        boolean lobbySnow = yaml.getBoolean("season.lobby-snow", false);
        int lobbyRadius = yaml.getInt("season.lobby-radius", 25);
        if (lobbyRadius < 0) {
            problems.add("season.lobby-radius is " + lobbyRadius + ", use 0 or greater");
        }
        try {
            SeasonWindow window = SeasonWindow.parse(preStart, actStart, actEnd, wrapEnd);
            seasonSettings = new SeasonSettings(seasonEnabled, window, snowfall, claimFirework, lobbySnow, lobbyRadius);
        } catch (IllegalArgumentException e) {
            problems.add("season configuration error: " + e.getMessage());
            seasonSettings = new SeasonSettings(false, new SeasonWindow(0, 0, 0, 0), false, false, false, 0);
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", problems));
        }
        return new BingoConfig(gameMode, winMode, lockout, lives, deathChat, advancementChat, advancementTab, tabEnabled, chatEnabled, timeLimit, countdown, teamSize, clearInventories,
                scatterPlayers, scatterRadius, lateJoin, pool, advancementPool, types, sounds, notes,
                teamDefinitions, updateNotifierEnabled, updateNotifierOnJoin, updateCheckIntervalHours,
                historyEnabled, historyMaxSavedMatches,
                lobbyWorld, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyPitch, maps, seasonSettings);
    }

    public SeasonSettings seasonSettings() {
        return seasonSettings;
    }

    public Location lobbyLocation() {
        if (lobbyWorld != null && !lobbyWorld.isEmpty()) {
            World w = Bukkit.getWorld(lobbyWorld);
            if (w != null) {
                return new Location(w, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyPitch);
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public boolean hasConfiguredLobby() {
        return lobbyWorld != null && !lobbyWorld.isEmpty() && Bukkit.getWorld(lobbyWorld) != null;
    }

    public Map<String, GameMapDefinition> maps() {
        return Collections.unmodifiableMap(maps);
    }

    public GameMapDefinition getMap(String id) {
        if (id == null) {
            return null;
        }
        return maps.get(id.toLowerCase(Locale.ROOT));
    }

    public BingoGameMode gameMode() {
        return gameMode;
    }

    public WinMode winMode() {
        return winMode;
    }

    private static ChatStyle chatStyle(FileConfiguration yaml, String key, List<String> problems) {
        String raw = yaml.getString(key, "custom");
        ChatStyle style = ChatStyle.parse(raw);
        if (style == null) {
            problems.add(key + " is '" + raw + "', use 'custom', 'vanilla' or 'hidden'");
            return ChatStyle.CUSTOM;
        }
        return style;
    }

    /** Whether the round card shows up as a tab in the advancements screen. */
    public boolean advancementTab() {
        return advancementTab;
    }

    /** Team colors in the player list while a round runs. */
    public boolean tabEnabled() {
        return tabEnabled;
    }

    /** The team format for chat messages while a round runs. */
    public boolean chatEnabled() {
        return chatEnabled;
    }

    public ChatStyle deathChat() {
        return deathChat;
    }

    public ChatStyle advancementChat() {
        return advancementChat;
    }

    /** Lives per player in a round; 0 for unlimited. */
    public int lives() {
        return lives;
    }

    public boolean lockout() {
        return lockout;
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

    public ItemPool itemPool() {
        return pool;
    }

    public ItemPool advancementPool() {
        return advancementPool;
    }

    /** The material behind a card key, as this server version knows it. */
    public XMaterial material(String itemKey) {
        return itemsByKey.get(itemKey);
    }

    /** Warnings that do not stop a round, such as items this version lacks. */
    public List<String> notes() {
        return notes;
    }

    public XSound sound(SoundId id) {
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

    public int updateCheckIntervalHours() {
        return updateCheckIntervalHours;
    }

    public boolean historyEnabled() {
        return historyEnabled;
    }

    public int historyMaxSavedMatches() {
        return historyMaxSavedMatches;
    }
}
