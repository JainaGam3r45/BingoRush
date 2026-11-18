package com.jifercraft.bingo.arena;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.game.AtomicFiles;
import com.jifercraft.bingo.game.QueuedFileWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The arenas in arenas.yml and the presets in presets.yml. Kept out of
 * config.yml so the config updater never rewrites them. This jar plays in
 * the server's own worlds: an arena is a set of rules and a waiting room.
 * The file is the same one the Modern jar reads.
 */
public final class ArenaManager {

    /** Why a create or an edit did not happen; each has a message key. */
    public enum Problem {
        INVALID_ID("arena.invalid-id"),
        EXISTS("arena.exists"),
        NOT_FOUND("arena.not-found"),
        UNKNOWN_PRESET("arena.unknown-preset"),
        TOO_MANY_TEAMS("arena.too-many-teams"),
        IN_USE("arena.in-use"),
        NOT_SAVED("arena.file-unreadable");

        private final String messageKey;

        Problem(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    public static final class Result {

        private final GameMapDefinition arena;
        private final Problem problem;

        private Result(GameMapDefinition arena, Problem problem) {
            this.arena = arena;
            this.problem = problem;
        }

        static Result ok(GameMapDefinition arena) {
            return new Result(arena, null);
        }

        static Result fail(Problem problem) {
            return new Result(null, problem);
        }

        public GameMapDefinition arena() {
            return arena;
        }

        public Problem problem() {
            return problem;
        }

        public boolean ok() {
            return problem == null;
        }
    }

    private final BingoRush plugin;
    private final File file;
    private final File presetsFile;
    private final QueuedFileWriter writer;
    private final Map<String, GameMapDefinition> arenas = new LinkedHashMap<>();
    private final Map<String, ArenaPreset> presets = new LinkedHashMap<>();
    // False while arenas.yml exists but cannot be parsed: saving then would
    // replace the admin's file, typo and all, with whatever is in memory.
    private boolean writable = true;

    public ArenaManager(BingoRush plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
        this.presetsFile = new File(plugin.getDataFolder(), "presets.yml");
        this.writer = new QueuedFileWriter(file, plugin.getLogger());
    }

    public void load() {
        loadPresets();
        if (!file.exists()) {
            arenas.clear();
            writable = true;
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            writable = false;
            plugin.getLogger().severe("arenas.yml could not be read, so it is left untouched and arena changes"
                    + " are not saved until it is fixed and /bingo reload is run: " + e.getMessage());
            return;
        }
        writable = true;
        Map<String, GameMapDefinition> loaded = read(yaml, plugin.bingoConfig(), plugin.teams().all().size(),
                plugin.getLogger());
        arenas.clear();
        arenas.putAll(loaded);
    }

    static Map<String, GameMapDefinition> read(YamlConfiguration yaml, BingoConfig config, int configuredTeams,
            Logger logger) {
        Map<String, GameMapDefinition> loaded = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("arenas");
        if (section == null) {
            return loaded;
        }
        for (String rawKey : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(rawKey);
            String id = ArenaOption.toId(rawKey);
            if (entry == null || id.isEmpty() || loaded.containsKey(id)) {
                logger.warning("arenas.yml: skipping '" + rawKey + "', its id is empty, repeated or not a section");
                continue;
            }
            if (!id.equals(rawKey)) {
                logger.warning("arenas.yml: arena '" + rawKey + "' is read as '" + id
                        + "', ids use only a-z, 0-9, _ and -");
            }
            GameMapDefinition arena = GameMapDefinition.fromDefaults(id, rawKey, config, configuredTeams);
            for (ArenaOption option : ArenaOption.values()) {
                Object raw = entry.get(option.key());
                if (raw == null) {
                    continue;
                }
                try {
                    arena = arena.with(option, option.parse(String.valueOf(raw)));
                } catch (InvalidArenaValue e) {
                    logger.warning("arenas.yml: arena '" + id + "': " + e.getMessage() + ", keeping "
                            + option.format(arena.get(option)));
                }
            }
            loaded.put(id, arena.withWaitingLobby(readLocation(entry.getConfigurationSection("waiting-lobby"))));
        }
        return loaded;
    }

    private static Location readLocation(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        World world = resolveWorld(section.getString("world", ""));
        if (world == null) {
            return null;
        }
        return new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    // The Modern jar writes world keys; these versions only know names, and
    // the main world is the only one both jars name the same way.
    private static World resolveWorld(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        if (raw.equals("minecraft:overworld")) {
            return Bukkit.getWorlds().get(0);
        }
        return Bukkit.getWorld(raw.substring(raw.indexOf(':') + 1));
    }

    static YamlConfiguration write(Iterable<GameMapDefinition> arenas) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (GameMapDefinition arena : arenas) {
            String path = "arenas." + arena.id() + ".";
            for (ArenaOption option : ArenaOption.values()) {
                Object value = arena.get(option);
                yaml.set(path + option.key(), value instanceof Number || value instanceof Boolean
                        ? value : option.format(value));
            }
            Location lobby = arena.waitingLobby();
            if (lobby != null && lobby.getWorld() != null) {
                yaml.set(path + "waiting-lobby.world", lobby.getWorld().getName());
                yaml.set(path + "waiting-lobby.x", lobby.getX());
                yaml.set(path + "waiting-lobby.y", lobby.getY());
                yaml.set(path + "waiting-lobby.z", lobby.getZ());
                yaml.set(path + "waiting-lobby.yaw", (double) lobby.getYaw());
                yaml.set(path + "waiting-lobby.pitch", (double) lobby.getPitch());
            }
        }
        return yaml;
    }

    private boolean save() {
        if (!writable) {
            return false;
        }
        writer.write(write(arenas.values()).saveToString());
        return true;
    }

    /**
     * 0.2.0 previews kept arenas as "maps" in config.yml. The config updater
     * drops keys the bundled config no longer has, so this runs before it,
     * reads the file as it is on disk and writes arenas.yml first. Values the
     * maps did not have come from the round settings in the same file.
     * Returns how many arenas were moved.
     */
    public static int migrateMaps(File dataFolder, Logger logger) {
        File config = new File(dataFolder, "config.yml");
        File target = new File(dataFolder, "arenas.yml");
        if (!config.isFile() || target.exists()) {
            return 0;
        }
        YamlConfiguration raw = YamlConfiguration.loadConfiguration(config);
        ConfigurationSection maps = raw.getConfigurationSection("maps");
        if (maps == null || maps.getKeys(false).isEmpty()) {
            return 0;
        }
        YamlConfiguration arenas = new YamlConfiguration();
        int moved = 0;
        for (String key : maps.getKeys(false)) {
            ConfigurationSection map = maps.getConfigurationSection(key);
            String id = ArenaOption.toId(key);
            if (map == null || id.isEmpty() || arenas.contains("arenas." + id)) {
                logger.warning("config.yml: map '" + key + "' could not be moved to arenas.yml");
                continue;
            }
            String path = "arenas." + id + ".";
            copy(raw, "game-mode", arenas, path + "mode");
            copy(raw, "win-mode", arenas, path + "win-mode");
            copy(raw, "lockout", arenas, path + "lockout");
            copy(raw, "time-limit", arenas, path + "time-limit");
            copy(raw, "countdown", arenas, path + "countdown");
            copy(raw, "team-size", arenas, path + "team-size");
            copy(raw, "lives", arenas, path + "lives");
            for (String field : map.getKeys(true)) {
                if (!map.isConfigurationSection(field)) {
                    arenas.set(path + field, map.get(field));
                }
            }
            arenas.set(path + "name", map.getString("name", key));
            moved++;
        }
        try {
            AtomicFiles.write(target, arenas.saveToString());
        } catch (RuntimeException e) {
            logger.severe("Could not write arenas.yml, the maps stay in config.yml for now: " + e.getMessage());
            return 0;
        }
        return moved;
    }

    private static void copy(YamlConfiguration from, String fromPath, YamlConfiguration to, String toPath) {
        Object value = from.get(fromPath);
        if (value != null && !(value instanceof ConfigurationSection)) {
            to.set(toPath, value);
        }
    }

    private void loadPresets() {
        if (!presetsFile.exists()) {
            plugin.saveResource("presets.yml", false);
        }
        presets.clear();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(presetsFile);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("presets.yml could not be read, no presets this time: " + e.getMessage());
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("presets");
        if (section == null) {
            return;
        }
        List<String> problems = new ArrayList<>();
        for (String name : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(name);
            if (entry == null) {
                problems.add("preset '" + name + "' is not a section");
                continue;
            }
            String id = ArenaOption.toId(name);
            presets.put(id, ArenaPreset.parse(id, entry.getValues(false), problems));
        }
        for (String problem : problems) {
            plugin.getLogger().warning("presets.yml: " + problem);
        }
    }

    public List<String> presetNames() {
        return new ArrayList<>(presets.keySet());
    }

    public ArenaPreset preset(String name) {
        return name == null ? null : presets.get(ArenaOption.toId(name));
    }

    /**
     * Writes the arena's settings, except its name and seed, as a preset.
     * An existing preset with that name is replaced.
     */
    public boolean savePreset(String name, GameMapDefinition arena) {
        String id = ArenaOption.toId(name);
        if (!ArenaOption.isValidId(id)) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(presetsFile);
        String path = "presets." + id;
        yaml.set(path, null);
        Map<ArenaOption, Object> values = new LinkedHashMap<>();
        for (ArenaOption option : ArenaOption.values()) {
            if (option == ArenaOption.NAME || option == ArenaOption.SEED) {
                continue;
            }
            Object value = arena.get(option);
            values.put(option, value);
            yaml.set(path + "." + option.key(), value instanceof Number || value instanceof Boolean
                    ? value : option.format(value));
        }
        String text = yaml.saveToString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                AtomicFiles.write(presetsFile, text);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not save presets.yml: " + e.getMessage());
            }
        });
        presets.put(id, new ArenaPreset(id, values));
        return true;
    }

    public GameMapDefinition getArena(String id) {
        return id == null ? null : arenas.get(id.toLowerCase(Locale.ROOT));
    }

    public List<GameMapDefinition> getAllArenas() {
        return new ArrayList<>(arenas.values());
    }

    public boolean isWritable() {
        return writable;
    }

    /**
     * Makes an arena from the config.yml round settings, with the preset on
     * top. Nothing is written when the id or the preset is wrong.
     */
    public Result create(String rawId, String presetName) {
        if (!writable) {
            return Result.fail(Problem.NOT_SAVED);
        }
        String id = rawId == null ? "" : rawId.trim().toLowerCase(Locale.ROOT);
        if (!ArenaOption.isValidId(id)) {
            return Result.fail(Problem.INVALID_ID);
        }
        if (arenas.containsKey(id)) {
            return Result.fail(Problem.EXISTS);
        }
        ArenaPreset preset = null;
        if (presetName != null && !presetName.trim().isEmpty()) {
            preset = preset(presetName);
            if (preset == null) {
                return Result.fail(Problem.UNKNOWN_PRESET);
            }
        }
        GameMapDefinition arena = GameMapDefinition.fromDefaults(id, id, plugin.bingoConfig(), configuredTeams());
        if (preset != null) {
            arena = arena.apply(preset);
        }
        arenas.put(id, arena);
        save();
        updateSigns();
        return Result.ok(arena);
    }

    /** Changes one setting; the value was already parsed by {@link ArenaOption#parse}. */
    public Result set(String id, ArenaOption option, Object value) {
        GameMapDefinition arena = getArena(id);
        if (arena == null) {
            return Result.fail(Problem.NOT_FOUND);
        }
        if (option == ArenaOption.MAX_TEAMS && (Integer) value > configuredTeams()) {
            return Result.fail(Problem.TOO_MANY_TEAMS);
        }
        return replace(arena.with(option, value));
    }

    public Result applyPreset(String id, String presetName) {
        GameMapDefinition arena = getArena(id);
        if (arena == null) {
            return Result.fail(Problem.NOT_FOUND);
        }
        ArenaPreset preset = preset(presetName);
        if (preset == null) {
            return Result.fail(Problem.UNKNOWN_PRESET);
        }
        GameMapDefinition updated = arena.apply(preset);
        if (updated.maxTeams() > configuredTeams()) {
            updated = updated.with(ArenaOption.MAX_TEAMS, configuredTeams());
        }
        return replace(updated);
    }

    public Result setWaitingLobby(String id, Location location) {
        GameMapDefinition arena = getArena(id);
        if (arena == null) {
            return Result.fail(Problem.NOT_FOUND);
        }
        return replace(arena.withWaitingLobby(location));
    }

    private Result replace(GameMapDefinition arena) {
        if (!writable) {
            return Result.fail(Problem.NOT_SAVED);
        }
        arenas.put(arena.id(), arena);
        save();
        updateSigns();
        return Result.ok(arena);
    }

    /** Removes the arena. The arena of the round in progress cannot go. */
    public Result delete(String id) {
        GameMapDefinition arena = getArena(id);
        if (arena == null) {
            return Result.fail(Problem.NOT_FOUND);
        }
        if (!writable) {
            return Result.fail(Problem.NOT_SAVED);
        }
        if (plugin.game() != null && arena.id().equals(plugin.game().roundArenaId())) {
            return Result.fail(Problem.IN_USE);
        }
        arenas.remove(arena.id());
        save();
        if (plugin.game() != null) {
            plugin.game().arenaRemoved(arena.id());
        }
        updateSigns();
        return Result.ok(arena);
    }

    /**
     * How many teams an arena can ask for. With no teams in config.yml the
     * plugin makes them as players join, so only the hard cap applies.
     */
    public int configuredTeams() {
        int teams = plugin.teams().all().size();
        return teams == 0 ? 32 : Math.max(2, teams);
    }

    public void close() {
        writer.close();
    }

    private void updateSigns() {
        if (plugin.game() != null && plugin.game().signManager() != null) {
            plugin.game().signManager().updateAll();
        }
    }
}
