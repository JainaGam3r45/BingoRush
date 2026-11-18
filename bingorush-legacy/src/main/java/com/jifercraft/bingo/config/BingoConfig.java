package com.jifercraft.bingo.config;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XSound;
import com.jifercraft.bingo.card.ItemPool;
import com.jifercraft.bingo.card.ItemTier;
import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.compat.ServerVersion;
import com.jifercraft.bingo.game.BingoGameMode;
import com.jifercraft.bingo.game.TeamPalette;
import com.jifercraft.bingo.season.GiftSettings;
import com.jifercraft.bingo.season.GiftType;
import com.jifercraft.bingo.season.SeasonSettings;
import com.jifercraft.bingo.season.SeasonCalendar;
import java.time.DateTimeException;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final DisplayFormats display;
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
    private final ScoreboardDefinition lobbyScoreboard;
    private final ScoreboardDefinition waitingScoreboard;
    private final ScoreboardDefinition playingScoreboard;
    private final SeasonSettings seasonSettings;
    private final List<String> warnings;

    private BingoConfig(BingoGameMode gameMode, WinMode winMode, boolean lockout, int lives,
            ChatStyle deathChat, ChatStyle advancementChat, boolean advancementTab, DisplayFormats display,
            long timeLimitSeconds,
            int countdownSeconds, int teamSize,
            boolean clearInventories, boolean scatterPlayers, int scatterRadius, LateJoin lateJoin,
            ItemPool pool, ItemPool advancementPool, Map<String, XMaterial> itemsByKey,
            Map<SoundId, XSound> sounds, List<String> notes,
            List<TeamDefinition> teamDefinitions, boolean updateNotifierEnabled, boolean updateNotifierOnJoin,
            int updateCheckIntervalHours, boolean historyEnabled, int historyMaxSavedMatches,
            String lobbyWorld, double lobbyX, double lobbyY, double lobbyZ, float lobbyYaw, float lobbyPitch,
            ScoreboardDefinition lobbyScoreboard, ScoreboardDefinition waitingScoreboard, ScoreboardDefinition playingScoreboard,
            SeasonSettings seasonSettings, List<String> warnings) {
        this.gameMode = gameMode;
        this.winMode = winMode;
        this.lockout = lockout;
        this.lives = lives;
        this.deathChat = deathChat;
        this.advancementChat = advancementChat;
        this.advancementTab = advancementTab;
        this.display = display;
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
        this.lobbyScoreboard = lobbyScoreboard;
        this.waitingScoreboard = waitingScoreboard;
        this.playingScoreboard = playingScoreboard;
        this.seasonSettings = seasonSettings;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
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
        DisplayFormats display = new DisplayFormats(
                yaml.getBoolean("team-display.enabled", true),
                yaml.getString("team-display.lobby", "<team_color><b><tag></b> "),
                yaml.getString("team-display.playing", "<team_color><b><tag></b> "),
                yaml.getString("team-display.spectating", "<gray>SPEC</gray> "));

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
                problems.add("teams." + i + " (" + name + ") has no color, add one like color: red or color: \"#FF5555\"");
                continue;
            }
            Integer rgb = TeamPalette.parse(rawColor.toString());
            if (rgb == null) {
                problems.add("teams." + i + " (" + name + ") has the color '" + rawColor
                        + "'; use a name like red or dark_blue, or a hex color like #FF5555");
                continue;
            }
            Object tag = entry.get("tag");
            teamDefinitions.add(new TeamDefinition(name.toString(), rgb, tag == null ? null : tag.toString()));
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

        ScoreboardDefinition lobbyScoreboard = parseScoreboardSection(yaml, "lobby", null);
        ScoreboardDefinition waitingScoreboard = parseScoreboardSection(yaml, "waiting", null);
        ScoreboardDefinition playingScoreboard = parseScoreboardSection(yaml, "playing",
                "<color>❙</color> <team>: <white><count>/<total></white>");

        // The season is an extra. Mistakes in it switch it off with a warning
        // instead of keeping the whole plugin idle.
        List<String> warnings = new ArrayList<>();
        SeasonSettings seasonSettings;
        boolean seasonEnabled = yaml.getBoolean("season.enabled", false);
        String preStart = yaml.getString("season.pre-season-start", "11-25");
        String actStart = yaml.getString("season.active-start", "12-01");
        String actEnd = yaml.getString("season.active-end", "12-31");
        String wrapEnd = yaml.getString("season.wrap-up-end", "01-03");
        boolean snowfall = yaml.getBoolean("season.snowfall", true);
        boolean claimFirework = yaml.getBoolean("season.claim-firework", true);
        boolean lobbySnow = yaml.getBoolean("season.lobby-snow", false);
        int lobbyRadius = yaml.getInt("season.lobby-radius", 25);
        if (lobbyRadius < 0) {
            warnings.add("season.lobby-radius is " + lobbyRadius + ", using 0");
            lobbyRadius = 0;
        }

        boolean giftsEnabled = yaml.getBoolean("season.gifts.enabled", true);
        boolean giftsAutoSpawn = yaml.getBoolean("season.gifts.auto-spawn", true);
        int giftsCount = yaml.getInt("season.gifts.auto-spawn-count", 5);
        double proximityRadius = yaml.getDouble("season.gifts.proximity-radius", 8.0);
        boolean actionbarClue = yaml.getBoolean("season.gifts.actionbar-clue", true);
        boolean titleClue = yaml.getBoolean("season.gifts.title-clue", true);
        List<String> completionRewards = yaml.getStringList("season.gifts.completion-rewards");
        Map<String, GiftType> giftTypes = new LinkedHashMap<>();
        ConfigurationSection typesSec = yaml.getConfigurationSection("season.gifts.types");
        if (typesSec != null) {
            for (String key : typesSec.getKeys(false)) {
                ConfigurationSection sec = typesSec.getConfigurationSection(key);
                if (sec != null) {
                    String name = sec.getString("name", key);
                    String texture = sec.getString("texture", "");
                    String particle = sec.getString("particle", "");
                    String proxSound = sec.getString("proximity-sound", "");
                    String clickSound = sec.getString("click-sound", "");
                    String clickParticle = sec.getString("click-particle", "");
                    List<String> rewards = sec.getStringList("rewards");
                    giftTypes.put(key, new GiftType(key, name, texture, particle, proxSound, clickSound, clickParticle, rewards));
                }
            }
        }
        GiftSettings giftSettings = new GiftSettings(giftsEnabled, giftsAutoSpawn, giftsCount, proximityRadius, actionbarClue, titleClue, giftTypes, Collections.emptyList(), completionRewards);

        try {
            SeasonCalendar calendar = SeasonCalendar.parse(preStart, actStart, actEnd, wrapEnd,
                    yaml.getString("season.timezone", ""));
            seasonSettings = new SeasonSettings(seasonEnabled, calendar, snowfall, claimFirework, lobbySnow, lobbyRadius,
                    giftSettings, lobbyExtras(yaml, warnings));
        } catch (IllegalArgumentException e) {
            if (seasonEnabled) {
                warnings.add("The season stays off until its dates are fixed: " + e.getMessage());
            }
            seasonSettings = SeasonSettings.off();
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", problems));
        }
        return new BingoConfig(gameMode, winMode, lockout, lives, deathChat, advancementChat, advancementTab, display, timeLimit, countdown, teamSize, clearInventories,
                scatterPlayers, scatterRadius, lateJoin, pool, advancementPool, types, sounds, notes,
                teamDefinitions, updateNotifierEnabled, updateNotifierOnJoin, updateCheckIntervalHours,
                historyEnabled, historyMaxSavedMatches,
                lobbyWorld, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyPitch,
                lobbyScoreboard, waitingScoreboard, playingScoreboard, seasonSettings, warnings);
    }

    private static SeasonSettings.Lobby lobbyExtras(ConfigurationSection yaml, List<String> warnings) {
        MonthDay highlight = null;
        String rawHighlight = yaml.getString("season.lobby.highlight-day", "12-25");
        if (rawHighlight != null && !rawHighlight.trim().isEmpty()) {
            try {
                String[] parts = rawHighlight.trim().split("-");
                highlight = MonthDay.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (DateTimeException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
                warnings.add("season.lobby.highlight-day '" + rawHighlight + "' is not a day like 12-25, so there is none");
            }
        }
        return new SeasonSettings.Lobby(
                yaml.getBoolean("season.lobby.snow-trail", true),
                yaml.getBoolean("season.lobby.tab-header", true),
                yaml.getBoolean("season.lobby.join-greeting", true),
                yaml.getBoolean("season.lobby.countdown", true),
                highlight,
                yaml.getBoolean("season.lobby.new-year", true));
    }

    public SeasonSettings seasonSettings() {
        return seasonSettings;
    }

    /** Things that load fine but not as written; logged, never fatal. */
    public List<String> warnings() {
        return warnings;
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

    /** Names, tags and colors in the player list, above heads and in vanilla chat. */
    public DisplayFormats display() {
        return display;
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

    public boolean lobbyScoreboard() {
        return lobbyScoreboard.enabled();
    }

    public boolean waitingScoreboard() {
        return waitingScoreboard.enabled();
    }

    public boolean playingScoreboard() {
        return playingScoreboard.enabled();
    }

    public ScoreboardDefinition lobbyScoreboardConfig() {
        return lobbyScoreboard;
    }

    public ScoreboardDefinition waitingScoreboardConfig() {
        return waitingScoreboard;
    }

    public ScoreboardDefinition playingScoreboardConfig() {
        return playingScoreboard;
    }

    private static ScoreboardDefinition parseScoreboardSection(FileConfiguration yaml, String path, String defaultTeamFormat) {
        if (yaml.isBoolean("scoreboards." + path)) {
            boolean enabled = yaml.getBoolean("scoreboards." + path, true);
            return new ScoreboardDefinition(enabled, null, Collections.emptyList(), defaultTeamFormat);
        }
        ConfigurationSection sec = yaml.getConfigurationSection("scoreboards." + path);
        if (sec == null) {
            return ScoreboardDefinition.defaultEnabled();
        }
        boolean enabled = sec.getBoolean("enabled", true);
        String title = sec.getString("title", null);
        List<String> lines = sec.getStringList("lines");
        String teamFormat = sec.getString("team-format", defaultTeamFormat);
        return new ScoreboardDefinition(enabled, title, lines, teamFormat);
    }
}
