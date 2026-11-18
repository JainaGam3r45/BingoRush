package com.jifercraft.bingo.world;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.CardGenerator;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.game.AtomicFiles;
import io.papermc.paper.math.Position;
import io.papermc.paper.world.WorldClock;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * The temporary worlds rounds are played in. Every arena gets its own
 * overworld, and a Nether and an End once a round starts. All of them use
 * keys in the plugin's namespace, so on disk they live in
 * world/dimensions/bingorush/ and nothing else on the server can be taken
 * for one of them, or deleted by mistake.
 */
public final class MatchWorldManager implements Listener {

    /** Arena ids end up in dimension keys and folder names. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private static final String OVERWORLD = "match_";
    private static final String NETHER = "nether_";
    private static final String END = "end_";
    // Big enough for a decent waiting room, small enough that capturing it
    // never stalls a tick.
    private static final int MAX_STRUCTURE_BLOCKS = 48 * 48 * 48;
    private static final int LAND_SEARCH_STEP = 96;
    private static final int LAND_SEARCH_RADIUS = 1536;

    private final BingoRush plugin;
    private final File structureDir;
    private final File trashDir;
    private final NamespacedKey keyOriginX;
    private final NamespacedKey keyOriginY;
    private final NamespacedKey keyOriginZ;
    private final NamespacedKey keyStructure;
    private final Map<String, WaitingPlatform> platforms = new HashMap<>();
    private final Map<String, BukkitTask> idleCleanupTasks = new HashMap<>();
    private final Map<String, Long> seeds = new HashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Random random = new Random();

    private record WaitingPlatform(Location origin, StructureClipboard clip, Location spawn) {
    }

    /** Corners and spawn point an admin marked for a waiting room capture. */
    public record Selection(Location pos1, Location pos2, Location center) {

        Selection withPos1(Location location) {
            return new Selection(location, pos2, center);
        }

        Selection withPos2(Location location) {
            return new Selection(pos1, location, center);
        }

        Selection withCenter(Location location) {
            return new Selection(pos1, pos2, location);
        }
    }

    public enum CaptureResult { SAVED, MISSING_CORNERS, DIFFERENT_WORLDS, TOO_BIG, BAD_NAME }

    public MatchWorldManager(BingoRush plugin) {
        this.plugin = plugin;
        this.structureDir = new File(plugin.getDataFolder(), "structures");
        this.trashDir = new File(plugin.getDataFolder(), "data/trash");
        this.keyOriginX = new NamespacedKey(plugin, "waiting_origin_x");
        this.keyOriginY = new NamespacedKey(plugin, "waiting_origin_y");
        this.keyOriginZ = new NamespacedKey(plugin, "waiting_origin_z");
        this.keyStructure = new NamespacedKey(plugin, "waiting_structure");
        structureDir.mkdirs();
    }

    public NamespacedKey overworldKey(String arenaId) {
        return new NamespacedKey(plugin, OVERWORLD + arenaId);
    }

    private NamespacedKey netherKey(String arenaId) {
        return new NamespacedKey(plugin, NETHER + arenaId);
    }

    private NamespacedKey endKey(String arenaId) {
        return new NamespacedKey(plugin, END + arenaId);
    }

    /** The arena a world belongs to, for any of its three dimensions, or null. */
    public String arenaIdOf(World world) {
        if (world == null) {
            return null;
        }
        NamespacedKey key = world.getKey();
        if (!key.getNamespace().equals(plugin.namespace())) {
            return null;
        }
        String path = key.getKey();
        for (String prefix : List.of(OVERWORLD, NETHER, END)) {
            if (path.startsWith(prefix)) {
                return path.substring(prefix.length());
            }
        }
        return null;
    }

    public boolean isMatchWorld(World world) {
        return arenaIdOf(world) != null;
    }

    /** The arena's overworld if it is loaded. */
    public World world(String arenaId) {
        return Bukkit.getWorld(overworldKey(arenaId));
    }

    private List<World> loadedDimensions(String arenaId) {
        List<World> worlds = new ArrayList<>(3);
        for (NamespacedKey key : List.of(overworldKey(arenaId), netherKey(arenaId), endKey(arenaId))) {
            World world = Bukkit.getWorld(key);
            if (world != null) {
                worlds.add(world);
            }
        }
        return worlds;
    }

    /** Everyone in any of the arena's dimensions. */
    public List<Player> playersIn(String arenaId) {
        List<Player> players = new ArrayList<>();
        for (World world : loadedDimensions(arenaId)) {
            players.addAll(world.getPlayers());
        }
        return players;
    }

    /** The seed the arena's world was made with, for the round file. */
    public Long seedOf(String arenaId) {
        return seeds.get(arenaId);
    }

    private long resolveSeed(GameMapDefinition arena) {
        String raw = arena.seed();
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("random")) {
            return random.nextLong();
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return CardGenerator.seedFrom(raw.trim());
        }
    }

    /**
     * Loads or makes the arena's overworld with its waiting room. Null when
     * the world cannot exist right now: the last one is still being deleted,
     * or the server refused to make it. Never call this while worlds tick.
     */
    public World getOrCreateArenaWorld(GameMapDefinition arena) {
        World world = loadOrCreate(arena, null);
        // The waiting room was taken down when the round started; building
        // it again mid-round would drop it on the players.
        boolean inRound = plugin.game() != null && arena.id().equals(plugin.game().roundArenaId());
        if (world != null && !inRound) {
            ensureWaitingPlatform(arena, world);
        }
        return world;
    }

    /**
     * Loads the overworld a saved round was played in, with the seed it had,
     * so a world that was deleted in the meantime comes back as the same map.
     */
    public World loadForRound(GameMapDefinition arena, Long seed) {
        return loadOrCreate(arena, seed);
    }

    private World loadOrCreate(GameMapDefinition arena, Long knownSeed) {
        cancelIdleCleanup(arena.id());
        World existing = world(arena.id());
        if (existing != null) {
            seeds.putIfAbsent(arena.id(), existing.getSeed());
            return existing;
        }
        long seed = knownSeed != null ? knownSeed : resolveSeed(arena);
        // A forced spawn skips the vanilla spawn search, which loads and
        // scans chunks before the world is even usable. The real spawn is
        // picked below from biome noise, which loads nothing.
        WorldCreator creator = WorldCreator.ofKey(overworldKey(arena.id()))
                .environment(World.Environment.NORMAL)
                .seed(seed)
                .forcedSpawnPosition(Position.block(0, 100, 0), 0f, 0f);
        World world;
        try {
            world = creator.createWorld();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not create the world for arena " + arena.id() + ": " + e.getMessage());
            return null;
        }
        if (world == null) {
            plugin.getLogger().warning("The server refused to create the world for arena " + arena.id());
            return null;
        }
        seeds.put(arena.id(), world.getSeed());
        boolean fresh = !world.getPersistentDataContainer().has(keyStructure, PersistentDataType.STRING)
                && world.getSpawnLocation().getBlockX() == 0 && world.getSpawnLocation().getBlockZ() == 0;
        if (fresh) {
            int[] land = findLand(world);
            world.setSpawnLocation(land[0], world.getHighestBlockYAt(land[0], land[1]) + 1, land[1]);
        }
        applyRules(world);
        applyBorder(world, arena, 1.0);
        return world;
    }

    // Walks outwards from 0,0 asking the biome source, not the chunks, so
    // nothing is generated until we know where the teams will stand.
    private int[] findLand(World world) {
        for (int radius = 0; radius <= LAND_SEARCH_RADIUS; radius += LAND_SEARCH_STEP) {
            for (int dx = -radius; dx <= radius; dx += LAND_SEARCH_STEP) {
                for (int dz = -radius; dz <= radius; dz += LAND_SEARCH_STEP) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    Biome biome = world.vanillaBiomeProvider().getBiome(world, dx, 64, dz);
                    String key = biome.getKey().getKey();
                    if (!key.contains("ocean") && !key.contains("river") && !key.equals("beach")) {
                        return new int[] {dx, dz};
                    }
                }
            }
        }
        return new int[] {0, 0};
    }

    private void applyRules(World world) {
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
    }

    private void applyBorder(World world, GameMapDefinition arena, double scale) {
        WorldBorder border = world.getWorldBorder();
        if (arena.border() <= 0) {
            border.reset();
            return;
        }
        Location spawn = world.getSpawnLocation();
        border.setCenter(spawn.getX() * scale, spawn.getZ() * scale);
        border.setSize(Math.max(32, arena.border() * scale));
    }

    /**
     * Makes the Nether and the End of the arena, at round start and when a
     * saved round comes back. Not from the portal event: it fires while
     * worlds tick, and Paper advises against making worlds then.
     */
    public void prepareDimensions(GameMapDefinition arena) {
        World overworld = world(arena.id());
        if (overworld == null) {
            return;
        }
        long seed = overworld.getSeed();
        World nether = createDimension(netherKey(arena.id()), World.Environment.NETHER, seed);
        if (nether != null) {
            applyRules(nether);
            applyBorder(nether, arena, 0.125);
        }
        World end = createDimension(endKey(arena.id()), World.Environment.THE_END, seed);
        if (end != null) {
            applyRules(end);
        }
    }

    private World createDimension(NamespacedKey key, World.Environment environment, long seed) {
        World existing = Bukkit.getWorld(key);
        if (existing != null) {
            return existing;
        }
        try {
            // The End has its own fixed spawn; for the Nether a forced one
            // again saves the spawn search.
            WorldCreator creator = WorldCreator.ofKey(key).environment(environment).seed(seed);
            if (environment == World.Environment.NETHER) {
                creator.forcedSpawnPosition(Position.block(0, 64, 0), 0f, 0f);
            }
            return creator.createWorld();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not create " + key + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The rules the round was voted or set up with, on all three dimensions.
     * The border goes again because the waiting room got the arena's own
     * before the vote. The clock only on the overworld: the Nether and the
     * End have none.
     */
    public void applyRoundRules(GameMapDefinition arena) {
        for (World world : loadedDimensions(arena.id())) {
            world.setGameRule(GameRules.PVP, arena.pvp());
            world.setGameRule(GameRules.KEEP_INVENTORY, arena.keepInventory());
        }
        World overworld = world(arena.id());
        World nether = Bukkit.getWorld(netherKey(arena.id()));
        if (overworld != null) {
            applyBorder(overworld, arena, 1.0);
        }
        if (nether != null) {
            applyBorder(nether, arena, 0.125);
        }
        WorldClock clock = overworld == null ? null : overworld.getDefaultClock();
        if (clock == null) {
            return;
        }
        switch (arena.daylight()) {
            case "day" -> {
                overworld.setFullTime(clock, 1000);
                overworld.setClockPaused(clock, true);
            }
            case "night" -> {
                overworld.setFullTime(clock, 18000);
                overworld.setClockPaused(clock, true);
            }
            default -> overworld.setClockPaused(clock, false);
        }
    }

    /** Where a team member stands up after dying in a round. */
    public Location respawnPoint(String arenaId) {
        World world = world(arenaId);
        return world == null ? null : world.getSpawnLocation().add(0.5, 0, 0.5);
    }

    private void setupWaitingPlatform(GameMapDefinition arena, World world) {
        clearWaitingPlatform(arena.id());

        Location spawnLoc = world.getSpawnLocation();
        int spawnX = spawnLoc.getBlockX();
        int spawnZ = spawnLoc.getBlockZ();

        String structureFileName = arena.id() + ".json";
        File customStructure = new File(structureDir, structureFileName);
        if (!customStructure.exists()) {
            structureFileName = "default.json";
            customStructure = new File(structureDir, structureFileName);
        }
        StructureClipboard clip = customStructure.exists()
                ? StructureClipboard.load(customStructure, plugin.getLogger())
                : null;
        if (clip == null) {
            clip = StructureClipboard.defaultPlatform();
            structureFileName = "builtin";
        }

        // Float the room well above the highest terrain under it, and keep
        // it under the build limit.
        int marginX = (int) Math.ceil(clip.width / 2.0) + 6;
        int marginZ = (int) Math.ceil(clip.length / 2.0) + 6;
        int highestTerrain = world.getMinHeight();
        for (int dx = -marginX; dx <= marginX; dx += 2) {
            for (int dz = -marginZ; dz <= marginZ; dz += 2) {
                highestTerrain = Math.max(highestTerrain, world.getHighestBlockYAt(spawnX + dx, spawnZ + dz));
            }
        }
        int targetY = Math.min(highestTerrain + 14, world.getMaxHeight() - clip.height - 5);
        Location origin = new Location(world, spawnX - (int) clip.centerOffsetX, targetY,
                spawnZ - (int) clip.centerOffsetZ);
        Location centerSpawn = clip.paste(origin);
        platforms.put(arena.id(), new WaitingPlatform(origin, clip, centerSpawn));

        // Kept in the world itself, so a restart can still take it down.
        PersistentDataContainer pdc = world.getPersistentDataContainer();
        pdc.set(keyOriginX, PersistentDataType.INTEGER, origin.getBlockX());
        pdc.set(keyOriginY, PersistentDataType.INTEGER, origin.getBlockY());
        pdc.set(keyOriginZ, PersistentDataType.INTEGER, origin.getBlockZ());
        pdc.set(keyStructure, PersistentDataType.STRING, structureFileName);
    }

    /**
     * Builds the waiting room again after a round cleared it. Nothing
     * happens while one is already standing, so entering the same waiting
     * room twice does not paste the blocks over each other.
     */
    public void ensureWaitingPlatform(GameMapDefinition arena, World world) {
        if (world == null || !arena.id().equals(arenaIdOf(world)) || platforms.containsKey(arena.id())) {
            return;
        }
        setupWaitingPlatform(arena, world);
    }

    /**
     * Takes the waiting room down before a round starts, block by block from
     * the clipboard, so the terrain under it is never touched. The world
     * spawn moves down to the ground for respawns and scattering.
     */
    public void clearWaitingPlatform(String arenaId) {
        WaitingPlatform platform = platforms.remove(arenaId);
        World world = world(arenaId);
        if (world == null) {
            return;
        }
        StructureClipboard clip = platform != null ? platform.clip() : null;
        Location origin = platform != null ? platform.origin() : null;
        PersistentDataContainer pdc = world.getPersistentDataContainer();
        if (origin == null) {
            Integer ox = pdc.get(keyOriginX, PersistentDataType.INTEGER);
            Integer oy = pdc.get(keyOriginY, PersistentDataType.INTEGER);
            Integer oz = pdc.get(keyOriginZ, PersistentDataType.INTEGER);
            String struct = pdc.get(keyStructure, PersistentDataType.STRING);
            if (ox != null && oy != null && oz != null) {
                origin = new Location(world, ox, oy, oz);
                if (struct != null && !struct.equals("builtin")) {
                    File file = new File(structureDir, struct);
                    if (file.exists()) {
                        clip = StructureClipboard.load(file, plugin.getLogger());
                    }
                }
                if (clip == null) {
                    clip = StructureClipboard.defaultPlatform();
                }
            }
        }
        if (clip != null && origin != null) {
            clip.clear(origin);
        }
        pdc.remove(keyOriginX);
        pdc.remove(keyOriginY);
        pdc.remove(keyOriginZ);
        pdc.remove(keyStructure);
        Location spawn = world.getSpawnLocation();
        world.setSpawnLocation(spawn.getBlockX(),
                world.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ()) + 1, spawn.getBlockZ());
    }

    public Location getWaitingSpawn(String arenaId) {
        WaitingPlatform platform = platforms.get(arenaId);
        return platform == null ? null : platform.spawn();
    }

    public Selection selection(UUID playerId) {
        return selections.get(playerId);
    }

    public void setPos1(UUID playerId, Location location) {
        selections.merge(playerId, new Selection(location, null, null), (old, fresh) -> old.withPos1(location));
    }

    public void setPos2(UUID playerId, Location location) {
        selections.merge(playerId, new Selection(null, location, null), (old, fresh) -> old.withPos2(location));
    }

    public void setCenter(UUID playerId, Location location) {
        selections.merge(playerId, new Selection(null, null, location), (old, fresh) -> old.withCenter(location));
    }

    public static boolean isValidName(String name) {
        return name != null && ID.matcher(name).matches();
    }

    /**
     * Copies the marked blocks on this tick and writes the file off the main
     * thread. The size cap keeps a typo in a corner from reading millions of
     * blocks in one go.
     */
    public CaptureResult saveStructure(UUID playerId, String name) {
        if (!isValidName(name)) {
            return CaptureResult.BAD_NAME;
        }
        Selection selection = selections.get(playerId);
        if (selection == null || selection.pos1() == null || selection.pos2() == null) {
            return CaptureResult.MISSING_CORNERS;
        }
        Location p1 = selection.pos1();
        Location p2 = selection.pos2();
        if (p1.getWorld() == null || !p1.getWorld().equals(p2.getWorld())) {
            return CaptureResult.DIFFERENT_WORLDS;
        }
        long volume = (long) (Math.abs(p1.getBlockX() - p2.getBlockX()) + 1)
                * (Math.abs(p1.getBlockY() - p2.getBlockY()) + 1)
                * (Math.abs(p1.getBlockZ() - p2.getBlockZ()) + 1);
        if (volume > MAX_STRUCTURE_BLOCKS) {
            return CaptureResult.TOO_BIG;
        }
        String json = StructureClipboard.capture(p1, p2, selection.center()).toJson();
        File target = new File(structureDir, name + ".json");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                AtomicFiles.write(target, json);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not save structure " + target.getName() + ": " + e.getMessage());
            }
        });
        return CaptureResult.SAVED;
    }

    public static int maxStructureBlocks() {
        return MAX_STRUCTURE_BLOCKS;
    }

    public boolean deleteStructure(String name) {
        if (!isValidName(name)) {
            return false;
        }
        return AtomicFiles.delete(new File(structureDir, name + ".json"));
    }

    public String getStructureStatus(String arenaId) {
        if (new File(structureDir, arenaId + ".json").exists()) {
            return "custom";
        }
        if (new File(structureDir, "default.json").exists()) {
            return "default";
        }
        return "builtin";
    }

    public List<String> listStructures() {
        File[] files = structureDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            names.add(file.getName().substring(0, file.getName().length() - ".json".length()));
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Sends a player to the arena's waiting room in adventure mode. False
     * when the world is not available, and the player stays where they are.
     */
    public boolean sendToWaitingLobby(Player player, GameMapDefinition arena, boolean enrollInGame) {
        World world = getOrCreateArenaWorld(arena);
        if (world == null) {
            return false;
        }
        Location spawn = getWaitingSpawn(arena.id());
        if (spawn == null) {
            spawn = world.getSpawnLocation().add(0.5, 15, 0.5);
        }
        player.teleport(spawn);
        if (enrollInGame && plugin.game() != null) {
            plugin.game().enterWaitingLobby(player, arena);
        }
        return true;
    }

    private Location exitLocation() {
        Location lobby = plugin.game() != null ? plugin.game().lobbyLocation() : null;
        return lobby != null ? lobby : Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    /**
     * Sends everyone in the arena's dimensions to the lobby, unloads them
     * and deletes them. A dimension that refuses to unload keeps its folder;
     * the sweep at the next start takes it.
     */
    public void unloadAndDeleteWorld(String arenaId) {
        cancelIdleCleanup(arenaId);
        Location exit = exitLocation();
        for (Player player : playersIn(arenaId)) {
            player.teleport(exit);
        }
        for (World world : loadedDimensions(arenaId)) {
            Path folder = world.getWorldPath();
            String key = world.getKey().toString();
            if (!Bukkit.unloadWorld(world, false)) {
                plugin.getLogger().warning("Could not unload " + key + ", its folder stays until the next restart");
                continue;
            }
            discard(folder);
        }
        platforms.remove(arenaId);
        seeds.remove(arenaId);
        if (plugin.modeVoteManager() != null) {
            plugin.modeVoteManager().clearVotes(arenaId);
        }
    }

    // The folder is renamed into the trash right away, so a new world with
    // the same key can be made on the next tick, and the slow recursive
    // delete happens off the main thread.
    private void discard(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        Path target = trashDir.toPath().resolve(folder.getFileName() + "-" + System.currentTimeMillis());
        try {
            Files.createDirectories(trashDir.toPath());
            try {
                Files.move(folder, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(folder, target);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not move " + folder + " to the trash, deleting it in place: " + e.getMessage());
            target = folder;
        }
        Path doomed = target;
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteTree(doomed));
        } else {
            deleteTree(doomed);
        }
    }

    private void deleteTree(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            plugin.getLogger().warning("Could not delete " + root + ": " + e.getMessage());
        }
    }

    /**
     * Where this server keeps the plugin's dimensions. Paper 26 stores every
     * dimension of the main level under its dimensions folder, keyed by
     * namespace, so the folder is found from the main overworld's own path.
     */
    private Path namespaceFolder() {
        Path overworld = Bukkit.getWorlds().getFirst().getWorldPath();
        Path dimensions = overworld.getParent() != null && overworld.getParent().getParent() != null
                && overworld.getParent().getParent().getFileName().toString().equals("dimensions")
                ? overworld.getParent().getParent()
                : overworld.resolve("dimensions");
        return dimensions.resolve(plugin.namespace());
    }

    /**
     * Startup sweep. Deletes match dimensions nobody needs: a crash left
     * them behind, or an unload failed last time. Only folders inside the
     * plugin's own namespace with one of our prefixes are touched, and the
     * arena a saved round is still using is kept.
     */
    public void sweepOrphans(String keepArenaId) {
        // Listed first: what discard() moves in below already has its own delete.
        File[] trash = trashDir.listFiles();
        if (trash != null) {
            for (File leftover : trash) {
                Path path = leftover.toPath();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteTree(path));
            }
        }
        Path folder = namespaceFolder();
        Set<String> keep = new HashSet<>();
        if (keepArenaId != null) {
            keep.add(OVERWORLD + keepArenaId);
            keep.add(NETHER + keepArenaId);
            keep.add(END + keepArenaId);
        }
        Set<String> loaded = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            if (isMatchWorld(world)) {
                loaded.add(world.getKey().getKey());
            }
        }
        int removed = 0;
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
                for (Path dimension : stream) {
                    String name = dimension.getFileName().toString();
                    boolean ours = name.startsWith(OVERWORLD) || name.startsWith(NETHER) || name.startsWith(END);
                    if (!ours || !Files.isDirectory(dimension) || keep.contains(name) || loaded.contains(name)) {
                        continue;
                    }
                    discard(dimension);
                    removed++;
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Could not look for leftover match worlds in " + folder + ": " + e.getMessage());
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " match world folder(s) left over from the last session");
        }
    }

    /**
     * Folders that test builds before 0.2.0 made under the minecraft
     * namespace. They are only reported: that namespace is not ours to
     * clean, so the admin decides.
     */
    public List<String> unmanagedFolders() {
        Path minecraft = namespaceFolder().resolveSibling("minecraft");
        List<String> found = new ArrayList<>();
        if (Files.isDirectory(minecraft)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(minecraft, "bingo_match_*")) {
                for (Path path : stream) {
                    found.add(path.toString());
                }
            } catch (IOException e) {
                plugin.getLogger().fine("Could not list " + minecraft + ": " + e.getMessage());
            }
        }
        return found;
    }

    /** Re-checks an arena once someone left it, and schedules its cleanup when empty. */
    public void notifyPlayerLeft(String arenaId) {
        if (arenaId == null || world(arenaId) == null) {
            return;
        }
        if (playersIn(arenaId).isEmpty()) {
            scheduleIdleCleanup(arenaId);
        }
    }

    private void scheduleIdleCleanup(String arenaId) {
        cancelIdleCleanup(arenaId);
        int timeoutSeconds = plugin.getConfig().getInt("empty-world-cleanup-seconds", 300);
        if (timeoutSeconds <= 0) {
            return;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            idleCleanupTasks.remove(arenaId);
            // A paused or running round keeps its world even with everyone
            // offline: they are coming back to it.
            boolean inUse = plugin.game() != null && arenaId.equals(plugin.game().roundArenaId());
            if (!inUse && playersIn(arenaId).isEmpty()) {
                unloadAndDeleteWorld(arenaId);
            }
        }, timeoutSeconds * 20L);
        idleCleanupTasks.put(arenaId, task);
    }

    public void cancelIdleCleanup(String arenaId) {
        BukkitTask task = idleCleanupTasks.remove(arenaId);
        if (task != null) {
            task.cancel();
        }
    }

    /** Plugin shutdown: timers go, worlds stay for the sweep or the saved round. */
    public void shutdown() {
        for (BukkitTask task : idleCleanupTasks.values()) {
            task.cancel();
        }
        idleCleanupTasks.clear();
        selections.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
        String arenaId = arenaIdOf(event.getPlayer().getWorld());
        if (arenaId != null) {
            // The player still counts as in the world during the event.
            Bukkit.getScheduler().runTask(plugin, () -> notifyPlayerLeft(arenaId));
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        String from = arenaIdOf(event.getFrom());
        if (from != null && !from.equals(arenaIdOf(event.getPlayer().getWorld()))) {
            notifyPlayerLeft(from);
        }
    }

    /**
     * Keeps portals inside the arena: Nether portals link its overworld and
     * Nether, the End portal leads to its End and the exit portal back home.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        World from = event.getFrom().getWorld();
        String arenaId = arenaIdOf(from);
        if (arenaId == null) {
            return;
        }
        Location origin = event.getFrom();
        World target = null;
        Location to = null;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            if (from.getEnvironment() == World.Environment.NETHER) {
                target = world(arenaId);
                if (target != null) {
                    to = new Location(target, origin.getX() * 8.0, clampY(target, origin.getY()), origin.getZ() * 8.0);
                }
            } else {
                target = Bukkit.getWorld(netherKey(arenaId));
                if (target != null) {
                    to = new Location(target, origin.getX() / 8.0, Math.min(120, Math.max(32, origin.getY())), origin.getZ() / 8.0);
                }
            }
        } else if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (from.getEnvironment() == World.Environment.THE_END) {
                to = respawnPoint(arenaId);
            } else {
                target = Bukkit.getWorld(endKey(arenaId));
                if (target != null) {
                    // Where vanilla builds the obsidian platform.
                    to = new Location(target, 100.5, 49, 0.5, 90f, 0f);
                }
            }
        } else {
            return;
        }
        if (to == null) {
            // Leaving the arena through a portal would put the player in the
            // server's own dimensions, in the middle of a round.
            event.setCancelled(true);
            return;
        }
        event.setTo(to);
    }

    private static double clampY(World world, double y) {
        return Math.min(world.getMaxHeight() - 2, Math.max(world.getMinHeight() + 2, y));
    }

    // The waiting room is for waiting: no damage, no building, and falling
    // off puts the player back on it.
    @EventHandler(priority = EventPriority.LOW)
    public void onPlatformDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.getGameMode() != GameMode.ADVENTURE) {
            return;
        }
        String arenaId = arenaIdOf(player.getWorld());
        if (arenaId == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            Location spawn = getWaitingSpawn(arenaId);
            if (spawn != null) {
                player.teleport(spawn);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlatformBreak(BlockBreakEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.ADVENTURE && isMatchWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlatformPlace(BlockPlaceEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.ADVENTURE && isMatchWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }
}
