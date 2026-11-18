package com.jifercraft.bingo.season;

import com.jifercraft.bingo.BingoRush;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

/**
 * Manages the active seasonal event cycle, phase evaluations, festive effects,
 * lobby snow decoration, hidden gift hunt ("The Search" style), and atomic block restoration.
 */
public final class Season implements Listener {

    private final BingoRush plugin;
    private final SeasonSettings settings;
    private final File journalFile;
    private final BlockJournal journal;
    private final File claimsFile;
    private final GiftClaims claims;
    private final Map<String, GiftLocation> activeGifts = new ConcurrentHashMap<>();
    private final Random random = new Random();

    private SeasonClock clock;
    private SeasonPhase currentPhase;
    private BukkitTask tickerTask;
    private BukkitTask proximityTask;

    public Season(BingoRush plugin, SeasonSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.journalFile = new File(plugin.getDataFolder(), "data/season/blocks.json");
        this.journal = BlockJournal.load(journalFile, plugin.getLogger());
        this.claimsFile = new File(plugin.getDataFolder(), "data/season/gift_claims.json");
        this.claims = GiftClaims.load(claimsFile, plugin.getLogger());
        this.clock = SeasonClock.system();
        this.currentPhase = settings.enabled()
                ? settings.window().phaseAt(clock.currentTimeMillis())
                : SeasonPhase.INACTIVE;
    }

    public void start() {
        if (!settings.enabled()) {
            return;
        }
        // Evaluate phase every 60 seconds (1200 ticks)
        tickerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1200L);

        // Proximity cue ticker for seasonal gifts every second (20 ticks)
        proximityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::proximityTick, 40L, 20L);

        // Spawn visuals if already in active or pre-season phase
        if (currentPhase.isVisualsActive()) {
            Location lobby = plugin.bingoConfig() != null ? plugin.bingoConfig().lobbyLocation() : null;
            if (lobby != null) {
                if (settings.lobbySnow()) {
                    applyLobbySnow(lobby);
                }
                if (currentPhase == SeasonPhase.ACTIVE && settings.giftSettings().enabled()) {
                    spawnGifts(lobby);
                }
            }
        }
    }

    public void stop() {
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
        if (proximityTask != null) {
            proximityTask.cancel();
            proximityTask = null;
        }
        if (!journal.isEmpty()) {
            journal.save(journalFile);
        }
        claims.save(claimsFile);
    }

    public void tick() {
        if (!settings.enabled()) {
            return;
        }
        long now = clock.currentTimeMillis();
        SeasonPhase newPhase = settings.window().phaseAt(now);
        if (newPhase != currentPhase) {
            transitionTo(newPhase);
        }
    }

    private void transitionTo(SeasonPhase newPhase) {
        SeasonPhase oldPhase = this.currentPhase;
        this.currentPhase = newPhase;

        Location lobby = plugin.bingoConfig() != null ? plugin.bingoConfig().lobbyLocation() : null;

        switch (newPhase) {
            case PRE_SEASON:
                if (settings.lobbySnow() && lobby != null) {
                    applyLobbySnow(lobby);
                }
                plugin.messages().broadcast("season.pre-season-chat",
                        Placeholder.unparsed("date", SeasonWindow.formatUtc(settings.window().activeStartMillis())));
                break;
            case ACTIVE:
                if (settings.lobbySnow() && lobby != null) {
                    applyLobbySnow(lobby);
                }
                if (settings.giftSettings().enabled() && lobby != null) {
                    spawnGifts(lobby);
                }
                plugin.messages().broadcast("season.active-chat");
                break;
            case WRAP_UP:
                clearGifts();
                plugin.messages().broadcast("season.wrap-up-chat");
                break;
            case INACTIVE:
                clearGifts();
                if (oldPhase.isVisualsActive()) {
                    restoreBlocks();
                    plugin.messages().broadcast("season.ended-chat");
                }
                break;
        }
    }

    /**
     * Gently layers snow on top of non-container solid floor blocks around the lobby.
     */
    public int applyLobbySnow(Location lobby) {
        if (lobby == null || lobby.getWorld() == null || settings.lobbyRadius() <= 0) {
            return 0;
        }
        World world = lobby.getWorld();
        int cx = lobby.getBlockX();
        int cz = lobby.getBlockZ();
        int r = settings.lobbyRadius();
        int placed = 0;

        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                int y = world.getHighestBlockYAt(x, z);
                Block floor = world.getBlockAt(x, y - 1, z);
                Block target = world.getBlockAt(x, y, z);

                if (target.getType() == Material.AIR && floor.getType().isSolid()
                        && !(floor.getState() instanceof InventoryHolder)) {
                    String coordKey = world.getName() + ":" + x + "," + y + "," + z;
                    if (!journal.isTracked(world.getName(), x, y, z)) {
                        journal.record(new BlockJournalEntry(world.getName(), x, y, z,
                                "minecraft:air", "season_snow", clock.currentTimeMillis()));
                    }
                    target.setType(Material.SNOW, false);
                    placed++;
                }
            }
        }
        if (placed > 0) {
            journal.save(journalFile);
        }
        return placed;
    }

    /**
     * Spawns seasonal gifts ("The Search" style) around the lobby.
     */
    public int spawnGifts(Location lobby) {
        if (!settings.giftSettings().enabled() || lobby == null || lobby.getWorld() == null) {
            return 0;
        }
        World world = lobby.getWorld();
        GiftSettings giftSettings = settings.giftSettings();
        Map<String, GiftType> types = giftSettings.types();
        if (types.isEmpty()) {
            return 0;
        }
        List<GiftType> typeList = new ArrayList<>(types.values());

        // Clear any previous gifts first
        clearGifts();

        int targetCount = giftSettings.autoSpawnCount();
        int cx = lobby.getBlockX();
        int cz = lobby.getBlockZ();
        int r = Math.max(10, settings.lobbyRadius());
        int spawned = 0;

        for (int attempts = 0; attempts < 100 && spawned < targetCount; attempts++) {
            int dx = random.nextInt(r * 2 + 1) - r;
            int dz = random.nextInt(r * 2 + 1) - r;
            if (dx * dx + dz * dz > r * r || (Math.abs(dx) < 3 && Math.abs(dz) < 3)) {
                continue;
            }
            int x = cx + dx;
            int z = cz + dz;
            int y = world.getHighestBlockYAt(x, z);
            Block floor = world.getBlockAt(x, y - 1, z);
            Block target = world.getBlockAt(x, y, z);
            Block ceiling = world.getBlockAt(x, y + 1, z);

            if (target.getType() == Material.AIR && ceiling.getType() == Material.AIR
                    && floor.getType().isSolid() && !(floor.getState() instanceof InventoryHolder)) {
                String coordKey = world.getName() + ":" + x + "," + y + "," + z;
                if (activeGifts.containsKey(coordKey)) {
                    continue;
                }
                GiftType type = typeList.get(random.nextInt(typeList.size()));
                GiftLocation giftLoc = new GiftLocation("gift_" + (spawned + 1), type.id(), world.getName(), x, y, z);

                if (!journal.isTracked(world.getName(), x, y, z)) {
                    journal.record(new BlockJournalEntry(world.getName(), x, y, z,
                            "minecraft:air", "season_gift:" + type.id(), clock.currentTimeMillis()));
                }
                CustomSkull.setSkull(target, type.texture());
                activeGifts.put(coordKey, giftLoc);
                spawned++;
            }
        }
        if (spawned > 0) {
            journal.save(journalFile);
        }
        return spawned;
    }

    /**
     * Removes active gifts and cleans up their blocks.
     */
    public void clearGifts() {
        for (GiftLocation loc : activeGifts.values()) {
            World world = Bukkit.getWorld(loc.world());
            if (world != null && world.isChunkLoaded(loc.x() >> 4, loc.z() >> 4)) {
                Block block = world.getBlockAt(loc.x(), loc.y(), loc.z());
                if (block.getType() == Material.PLAYER_HEAD) {
                    block.setType(Material.AIR, false);
                }
            }
        }
        activeGifts.clear();
    }

    /**
     * Adds an explicit gift at a specific location.
     */
    public boolean addGift(Location loc, String typeId) {
        if (loc == null || loc.getWorld() == null || typeId == null) {
            return false;
        }
        GiftType type = settings.giftSettings().types().get(typeId);
        if (type == null) {
            return false;
        }
        Block block = loc.getBlock();
        if (block.getState() instanceof InventoryHolder) {
            return false;
        }
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        String coordKey = worldName + ":" + x + "," + y + "," + z;

        if (!journal.isTracked(worldName, x, y, z)) {
            journal.record(new BlockJournalEntry(worldName, x, y, z,
                    block.getBlockData().getAsString(), "season_gift:" + type.id(), clock.currentTimeMillis()));
            journal.save(journalFile);
        }
        CustomSkull.setSkull(block, type.texture());
        activeGifts.put(coordKey, new GiftLocation("custom_" + activeGifts.size(), type.id(), worldName, x, y, z));
        return true;
    }

    /**
     * Periodic proximity cues for nearby players: emits particles and plays
     * chime sound that increases in pitch the closer a player gets, and optional
     * action bar distance clues.
     */
    public void proximityTick() {
        if (!currentPhase.isVisualsActive() || !settings.giftSettings().enabled() || activeGifts.isEmpty()) {
            return;
        }
        double radius = settings.giftSettings().proximityRadius();
        Map<Player, Double> nearestDistances = new HashMap<>();

        for (GiftLocation loc : activeGifts.values()) {
            World world = Bukkit.getWorld(loc.world());
            if (world == null || !world.isChunkLoaded(loc.x() >> 4, loc.z() >> 4)) {
                continue;
            }
            Location center = new Location(world, loc.x() + 0.5, loc.y() + 0.5, loc.z() + 0.5);
            GiftType type = settings.giftSettings().types().get(loc.typeId());
            if (type == null) {
                continue;
            }

            // Subtle ambient particle at the gift
            world.spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(0, 0.2, 0), 2, 0.15, 0.15, 0.15, 0.0);

            // Proximity sound modulated by player distance
            for (Player player : world.getPlayers()) {
                if (claims.hasClaimed(player.getUniqueId(), loc.id())) {
                    continue; // Skip players who already found this gift
                }
                double dist = player.getLocation().distance(center);
                if (dist <= radius) {
                    float factor = (float) (1.0 - (dist / radius)); // 0.0 far to 1.0 close
                    float pitch = 0.6f + factor * 0.9f; // 0.6f up to 1.5f pitch
                    float volume = 0.3f + factor * 0.5f;
                    player.playSound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, volume, pitch);

                    Double prev = nearestDistances.get(player);
                    if (prev == null || dist < prev) {
                        nearestDistances.put(player, dist);
                    }
                }
            }
        }

        // Action bar distance clues for closest unfound gift
        if (settings.giftSettings().actionbarClue()) {
            for (Map.Entry<Player, Double> entry : nearestDistances.entrySet()) {
                Player player = entry.getKey();
                Component bar = plugin.messages().render("season.gift-proximity-actionbar",
                        Placeholder.unparsed("distance", String.format(Locale.ROOT, "%.1f", entry.getValue())));
                player.sendActionBar(bar);
            }
        }
    }

    /**
     * Handles discovery and claiming of hidden seasonal gifts on click.
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block clicked = event.getClickedBlock();
        String coordKey = clicked.getWorld().getName() + ":" + clicked.getX() + "," + clicked.getY() + "," + clicked.getZ();
        GiftLocation giftLoc = activeGifts.get(coordKey);
        if (giftLoc == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        GiftType type = settings.giftSettings().types().get(giftLoc.typeId());
        String giftName = type != null ? type.displayName() : "Gift";
        int total = activeGifts.size();

        if (claims.hasClaimed(uuid, giftLoc.id())) {
            plugin.messages().send(player, "season.gift-already-claimed",
                    Placeholder.unparsed("found", String.valueOf(claims.claimedCount(uuid))),
                    Placeholder.unparsed("total", String.valueOf(total)));
            player.playSound(clicked.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }

        // Record new claim
        claims.recordClaim(uuid, giftLoc.id());
        claims.save(claimsFile);
        int found = claims.claimedCount(uuid);

        // Visual and auditory celebration
        Location center = clicked.getLocation().add(0.5, 0.6, 0.5);
        World world = clicked.getWorld();
        world.spawnParticle(Particle.FIREWORK, center, 30, 0.3, 0.3, 0.3, 0.08);
        world.spawnParticle(Particle.HAPPY_VILLAGER, center, 20, 0.4, 0.4, 0.4, 0.05);
        player.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);

        // Execute reward commands
        if (type != null) {
            for (String cmd : type.rewardCommands()) {
                String formatted = cmd.replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
            }
        }

        plugin.messages().send(player, "season.gift-found",
                Placeholder.parsed("gift_name", giftName),
                Placeholder.unparsed("found", String.valueOf(found)),
                Placeholder.unparsed("total", String.valueOf(total)));

        // Title clue for individual discovery
        if (settings.giftSettings().titleClue()) {
            Component titleComp = plugin.messages().render("season.gift-title");
            Component subComp = plugin.messages().render("season.gift-subtitle",
                    Placeholder.parsed("gift_name", giftName),
                    Placeholder.unparsed("found", String.valueOf(found)),
                    Placeholder.unparsed("total", String.valueOf(total)));
            player.showTitle(Title.title(titleComp, subComp));
        }

        // Check if all gifts found
        if (found >= total && total > 0) {
            plugin.messages().send(player, "season.gift-all-found",
                    Placeholder.unparsed("total", String.valueOf(total)));
            for (String cmd : settings.giftSettings().completionCommands()) {
                String formatted = cmd.replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
            }
            if (settings.giftSettings().titleClue()) {
                Component allTitle = plugin.messages().render("season.gift-all-title");
                Component allSub = plugin.messages().render("season.gift-all-subtitle",
                        Placeholder.unparsed("total", String.valueOf(total)));
                player.showTitle(Title.title(allTitle, allSub));
            }
        }
    }

    /**
     * Plays safe festive firework particles around a player after claiming a cell.
     */
    @EventHandler
    public void onBingoClaim(com.jifercraft.bingo.api.events.BingoClaimEvent event) {
        if (event.getPlayer() != null) {
            onClaim(event.getPlayer());
        }
    }

    public void onClaim(Player player) {
        if (!currentPhase.isVisualsActive() || !settings.claimFirework() || player == null) {
            return;
        }
        Location loc = player.getLocation().clone().add(0, 3.0, 0);
        World world = loc.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.FIREWORK, loc, 16, 0.3, 0.3, 0.3, 0.05);
            world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.7f, 1.2f);
        }
    }

    /**
     * Spawns snowfall ambient particles above a location during countdown.
     */
    public void onCountdownSnow(Location center) {
        if (!currentPhase.isVisualsActive() || !settings.snowfall() || center == null) {
            return;
        }
        World world = center.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.SNOWFLAKE, center.clone().add(0, 4.0, 0), 30, 8.0, 2.0, 8.0, 0.02);
            world.playSound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.4f);
        }
    }

    /**
     * Restores all recorded blocks in reverse (LIFO) order with physics disabled.
     */
    public int restoreBlocks() {
        if (journal.isEmpty()) {
            return 0;
        }
        List<BlockJournalEntry> rollback = journal.rollbackEntries();
        int count = 0;
        for (BlockJournalEntry entry : rollback) {
            World world = Bukkit.getWorld(entry.world());
            if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (block.getState() instanceof InventoryHolder) {
                continue;
            }
            try {
                BlockData data = Bukkit.createBlockData(entry.blockData());
                block.setBlockData(data, false);
                count++;
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Failed to restore block at " + entry.coordinateKey() + ": " + e.getMessage());
            }
        }
        journal.clear();
        BlockJournal.delete(journalFile);
        activeGifts.clear();
        return count;
    }

    public void simulatePhase(SeasonPhase phase) {
        long targetTime;
        switch (phase) {
            case PRE_SEASON:
                targetTime = settings.window().preSeasonStartMillis() + 1000L;
                break;
            case ACTIVE:
                targetTime = settings.window().activeStartMillis() + 1000L;
                break;
            case WRAP_UP:
                targetTime = settings.window().activeEndMillis() + 1000L;
                break;
            case INACTIVE:
            default:
                targetTime = settings.window().wrapUpEndMillis() + 1000L;
                break;
        }
        this.clock = new SimulatedSeasonClock(targetTime);
        transitionTo(phase);
    }

    public void resetClock() {
        this.clock = SeasonClock.system();
        tick();
    }

    public SeasonPhase currentPhase() {
        return currentPhase;
    }

    public SeasonSettings settings() {
        return settings;
    }

    public BlockJournal journal() {
        return journal;
    }

    public SeasonClock clock() {
        return clock;
    }

    public GiftClaims claims() {
        return claims;
    }

    public Map<String, GiftLocation> activeGifts() {
        return Collections.unmodifiableMap(activeGifts);
    }
}
