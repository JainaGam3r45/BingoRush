package com.jifercraft.bingo.season;

import com.cryptomorin.xseries.XBlock;
import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XSound;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.api.events.BingoClaimEvent;
import com.jifercraft.bingo.config.BingoConfig;
import java.io.File;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

/**
 * The optional seasonal layer: lobby snow, snowflakes, claim fireworks and a
 * gift hunt around the lobby, inside the dates in config.yml. Every block it
 * places goes through the journal first, so turning the season off, or the
 * dates running out, puts the lobby back the way it was, even after a crash.
 * Particles use the old Effect names, which only exist up to 1.12; newer
 * servers on this jar get the sounds and no particles.
 */
public final class Season implements Listener {

    private static final String SNOW_TAG = "season_snow";
    private static final String GIFT_TAG = "season_gift:";

    private final BingoRush plugin;
    private final SeasonSettings settings;
    private final File journalFile;
    private final BlockJournal journal;
    private final File claimsFile;
    private final GiftClaims claims;
    private final Map<String, GiftLocation> activeGifts = new ConcurrentHashMap<>();
    private final Map<String, Optional<Effect>> effects = new HashMap<>();
    // From 1.9 a click fires once per hand; the second one must not say
    // "already found" right after the first found the gift.
    private final Map<UUID, Long> lastGiftClick = new HashMap<>();
    private final Random random = new Random();
    private final SeasonLobby lobbyEffects;
    // Players the gift chime reached last second; the countdown leaves
    // their action bar alone.
    private final Set<UUID> nearGift = new HashSet<>();

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
        this.lobbyEffects = new SeasonLobby(plugin, this, settings);
        this.currentPhase = settings.enabled()
                ? window().phaseAt(clock.currentTimeMillis())
                : SeasonPhase.INACTIVE;
    }

    public void start() {
        if (!currentPhase.isVisualsActive()) {
            // Switched off or out of its dates since the last start: whatever
            // it left in the lobby goes back now.
            if (!journal.isEmpty()) {
                int restored = restoreBlocks();
                plugin.getLogger().info("The season is not running, put back " + restored + " lobby block(s)");
            }
        } else {
            rebuildGifts();
            decorateLobby();
        }
        if (!settings.enabled()) {
            return;
        }
        tickerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1200L);
        proximityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::proximityTick, 40L, 20L);
    }

    private SeasonWindow window() {
        return settings.windowAt(clock.currentTimeMillis());
    }

    public void stop() {
        lobbyEffects.clear();
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
        if (proximityTask != null) {
            proximityTask.cancel();
            proximityTask = null;
        }
        // The blocks stay while the server is off; the journal knows them.
        if (journal.isEmpty()) {
            BlockJournal.delete(journalFile);
        } else {
            journal.save(journalFile);
        }
        claims.save(claimsFile);
    }

    public void tick() {
        if (!settings.enabled()) {
            return;
        }
        SeasonPhase newPhase = window().phaseAt(clock.currentTimeMillis());
        if (newPhase != currentPhase) {
            transitionTo(newPhase);
        }
    }

    private void transitionTo(SeasonPhase newPhase) {
        SeasonPhase oldPhase = currentPhase;
        currentPhase = newPhase;
        switch (newPhase) {
            case PRE_SEASON:
                plugin.messages().broadcast("season.pre-season-chat",
                        Placeholder.unparsed("date", DateTimeFormatter.ISO_LOCAL_DATE.format(
                                Instant.ofEpochMilli(window().activeStartMillis()).atZone(settings.calendar().zone()))));
                break;
            case ACTIVE:
                decorateLobby();
                plugin.messages().broadcast("season.active-chat");
                break;
            case WRAP_UP:
                clearGifts();
                plugin.messages().broadcast("season.wrap-up-chat");
                break;
            default:
                lobbyEffects.clear();
                restoreBlocks();
                // A new season starts with every gift unfound again.
                claims.resetAll();
                saveClaims();
                if (oldPhase.isVisualsActive()) {
                    plugin.messages().broadcast("season.ended-chat");
                }
        }
    }

    private void decorateLobby() {
        Location lobby = lobby();
        if (lobby == null) {
            return;
        }
        if (settings.lobbySnow()) {
            applyLobbySnow(lobby);
        }
        if (currentPhase == SeasonPhase.ACTIVE && settings.giftSettings().enabled()
                && settings.giftSettings().autoSpawn() && activeGifts.isEmpty()) {
            spawnGifts(lobby);
        }
    }

    // Only a lobby the admin set: the main world spawn is nobody's lobby to decorate.
    private Location lobby() {
        BingoConfig config = plugin.bingoConfig();
        return config != null && config.hasConfiguredLobby() ? config.lobbyLocation() : null;
    }

    // Before 1.15 getHighestBlockYAt is the first air block above the
    // ground; from 1.15 it is the ground itself. Either way, the free spot.
    private static Block surface(World world, int x, int z) {
        Block block = world.getBlockAt(x, world.getHighestBlockYAt(x, z), z);
        return block.getType() == Material.AIR ? block : block.getRelative(BlockFace.UP);
    }

    private static boolean canDecorate(Block target) {
        Block floor = target.getRelative(BlockFace.DOWN);
        return target.getType() == Material.AIR && floor.getType().isSolid()
                && !(floor.getState() instanceof InventoryHolder);
    }

    @SuppressWarnings("deprecation")
    private static String blockData(Block block) {
        return block.getType().name() + ":" + block.getData();
    }

    /** Lays a layer of snow on the open floor around the lobby. */
    public int applyLobbySnow(Location lobby) {
        World world = lobby.getWorld();
        int r = settings.lobbyRadius();
        if (world == null || r <= 0) {
            return 0;
        }
        int placed = 0;
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                Block target = surface(world, lobby.getBlockX() + dx, lobby.getBlockZ() + dz);
                if (!canDecorate(target) || journal.isTracked(world.getName(), target.getX(), target.getY(), target.getZ())) {
                    continue;
                }
                journal.record(new BlockJournalEntry(world.getName(), target.getX(), target.getY(), target.getZ(),
                        blockData(target), SNOW_TAG, clock.currentTimeMillis()));
                XBlock.setType(target, XMaterial.SNOW, false);
                placed++;
            }
        }
        if (placed > 0) {
            saveJournal();
        }
        return placed;
    }

    /** Hides the configured number of gifts around the lobby. */
    public int spawnGifts(Location lobby) {
        World world = lobby.getWorld();
        List<GiftType> types = new ArrayList<>(settings.giftSettings().types().values());
        if (!settings.giftSettings().enabled() || world == null || types.isEmpty()) {
            return 0;
        }
        clearGifts();
        int target = settings.giftSettings().autoSpawnCount();
        int r = Math.max(10, settings.lobbyRadius());
        int spawned = 0;
        for (int attempts = 0; attempts < 200 && spawned < target; attempts++) {
            int dx = random.nextInt(r * 2 + 1) - r;
            int dz = random.nextInt(r * 2 + 1) - r;
            if (dx * dx + dz * dz > r * r || (Math.abs(dx) < 3 && Math.abs(dz) < 3)) {
                continue;
            }
            Block block = surface(world, lobby.getBlockX() + dx, lobby.getBlockZ() + dz);
            if (!canDecorate(block) || block.getRelative(BlockFace.UP).getType() != Material.AIR
                    || journal.isTracked(world.getName(), block.getX(), block.getY(), block.getZ())) {
                continue;
            }
            placeGift(block, types.get(random.nextInt(types.size())));
            spawned++;
        }
        if (spawned > 0) {
            saveJournal();
        }
        return spawned;
    }

    /** Puts a gift where the admin stands; false for an unknown type. */
    public boolean addGift(Location location, String typeId) {
        GiftType type = settings.giftSettings().types().get(typeId);
        Block block = location.getBlock();
        if (type == null || block.getState() instanceof InventoryHolder
                || journal.isTracked(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) {
            return false;
        }
        placeGift(block, type);
        saveJournal();
        return true;
    }

    private void placeGift(Block block, GiftType type) {
        String world = block.getWorld().getName();
        journal.record(new BlockJournalEntry(world, block.getX(), block.getY(), block.getZ(),
                blockData(block), GIFT_TAG + type.id(), clock.currentTimeMillis()));
        CustomSkull.setSkull(block, type.texture(), plugin.getLogger());
        GiftLocation gift = giftAt(world, block.getX(), block.getY(), block.getZ(), type.id());
        activeGifts.put(gift.coordinateKey(), gift);
    }

    // The id comes from the position, so the same gift keeps its id across
    // restarts and a player who found it does not find it twice.
    private static GiftLocation giftAt(String world, int x, int y, int z, String typeId) {
        return new GiftLocation(world + "@" + x + "," + y + "," + z, typeId, world, x, y, z);
    }

    private void rebuildGifts() {
        activeGifts.clear();
        for (BlockJournalEntry entry : journal.entries()) {
            if (entry.tag() != null && entry.tag().startsWith(GIFT_TAG)) {
                GiftLocation gift = giftAt(entry.world(), entry.x(), entry.y(), entry.z(),
                        entry.tag().substring(GIFT_TAG.length()));
                activeGifts.put(gift.coordinateKey(), gift);
            }
        }
    }

    /** Takes every gift down and puts back what was there before. */
    public void clearGifts() {
        restore(entry -> entry.tag() != null && entry.tag().startsWith(GIFT_TAG));
        activeGifts.clear();
    }

    /** Puts back every block the season changed, newest first. */
    public int restoreBlocks() {
        int restored = restore(entry -> true);
        activeGifts.clear();
        return restored;
    }

    // Entries in a world that is not loaded stay in the journal for the next
    // try. XSeries maps the saved material to this version's block, which
    // setTypeIdAndData cannot do past 1.12.
    private int restore(Predicate<BlockJournalEntry> which) {
        int count = 0;
        for (BlockJournalEntry entry : journal.rollbackEntries()) {
            if (!which.test(entry)) {
                continue;
            }
            World world = Bukkit.getWorld(entry.world());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            if (!(block.getState() instanceof InventoryHolder)) {
                String material = entry.blockData().split(":")[0];
                Optional<XMaterial> original = XMaterial.matchXMaterial(material);
                if (original.isPresent()) {
                    XBlock.setType(block, original.get(), false);
                    count++;
                } else {
                    plugin.getLogger().warning("Could not put back the block at " + entry.coordinateKey()
                            + ": unknown material " + material);
                }
            }
            journal.forget(entry);
        }
        saveJournal();
        return count;
    }

    private void saveJournal() {
        if (journal.isEmpty()) {
            write(() -> BlockJournal.delete(journalFile));
        } else {
            write(() -> journal.save(journalFile));
        }
    }

    public void saveClaims() {
        write(() -> claims.save(claimsFile));
    }

    private void write(Runnable save) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    save.run();
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Could not save season data: " + e.getMessage());
                }
            });
        } else {
            save.run();
        }
    }

    void effect(Location location, String configured, String fallback) {
        String name = configured == null || configured.trim().isEmpty() ? fallback : configured.trim();
        Optional<Effect> effect = effects.computeIfAbsent(name.toUpperCase(Locale.ROOT), Season::effectNamed);
        if (!effect.isPresent() && !name.equals(fallback)) {
            effect = effects.computeIfAbsent(fallback, Season::effectNamed);
        }
        if (effect.isPresent()) {
            location.getWorld().playEffect(location, effect.get(), 0);
        }
    }

    private static Optional<Effect> effectNamed(String name) {
        try {
            return Optional.of(Effect.valueOf(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void sound(Location location, String configured, XSound fallback, float volume, float pitch) {
        Optional<XSound> sound = configured == null || configured.trim().isEmpty()
                ? Optional.<XSound>empty() : XSound.of(configured.trim());
        sound.orElse(fallback).play(location, volume, pitch);
    }

    /**
     * Once a second: snowflakes around the lobby, and a chime that rises in
     * pitch as a player gets close to a gift they have not found.
     */
    public void proximityTick() {
        lobbyEffects.tick(currentPhase, clock.currentTimeMillis(), lobby());
        if (!currentPhase.isVisualsActive()) {
            return;
        }
        if (settings.snowfall()) {
            Location lobby = lobby();
            if (lobby != null && lobby.getWorld() != null) {
                int radius = Math.max(25, settings.lobbyRadius());
                for (Player player : lobby.getWorld().getPlayers()) {
                    if (player.getLocation().distanceSquared(lobby) <= (double) radius * radius) {
                        effect(player.getLocation().add(0, 3.5, 0), null, "SNOW_SHOVEL");
                    }
                }
            }
        }
        if (!settings.giftSettings().enabled() || activeGifts.isEmpty()) {
            return;
        }
        double radius = settings.giftSettings().proximityRadius();
        Map<Player, Double> nearest = new HashMap<>();
        for (GiftLocation gift : activeGifts.values()) {
            World world = Bukkit.getWorld(gift.world());
            if (world == null || !world.isChunkLoaded(gift.x() >> 4, gift.z() >> 4)) {
                continue;
            }
            Location center = new Location(world, gift.x() + 0.5, gift.y() + 0.5, gift.z() + 0.5);
            GiftType type = settings.giftSettings().types().get(gift.typeId());
            effect(center.clone().add(0, 0.2, 0), type == null ? null : type.particle(), "HAPPY_VILLAGER");
            for (Player player : world.getPlayers()) {
                if (claims.hasClaimed(player.getUniqueId(), gift.id())) {
                    continue;
                }
                double dist = player.getLocation().distance(center);
                if (dist <= radius) {
                    float closeness = (float) (1.0 - dist / radius);
                    sound(center, type == null ? null : type.proximitySound(), XSound.BLOCK_NOTE_BLOCK_CHIME,
                            0.3f + closeness * 0.5f, 0.6f + closeness * 0.9f);
                    Double previous = nearest.get(player);
                    if (previous == null || dist < previous) {
                        nearest.put(player, dist);
                    }
                }
            }
        }
        nearGift.clear();
        for (Player player : nearest.keySet()) {
            nearGift.add(player.getUniqueId());
        }
        if (settings.giftSettings().actionbarClue()) {
            for (Map.Entry<Player, Double> entry : nearest.entrySet()) {
                plugin.messages().audience(entry.getKey()).sendActionBar(plugin.messages().render(
                        "season.gift-proximity-actionbar",
                        Placeholder.unparsed("distance", String.format(Locale.ROOT, "%.1f", entry.getValue()))));
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block clicked = event.getClickedBlock();
        GiftLocation gift = activeGifts.get(clicked.getWorld().getName() + ":"
                + clicked.getX() + "," + clicked.getY() + "," + clicked.getZ());
        if (gift == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastGiftClick.put(uuid, now);
        if (last != null && now - last < 150) {
            return;
        }
        GiftType type = settings.giftSettings().types().get(gift.typeId());
        String giftName = type != null ? type.displayName() : gift.typeId();
        int total = activeGifts.size();
        if (!claims.recordClaim(uuid, gift.id())) {
            plugin.messages().send(player, "season.gift-already-claimed",
                    Placeholder.unparsed("found", String.valueOf(foundOf(uuid))),
                    Placeholder.unparsed("total", String.valueOf(total)));
            XSound.BLOCK_NOTE_BLOCK_BASS.play(clicked.getLocation(), 0.6f, 0.8f);
            return;
        }
        saveClaims();
        int found = foundOf(uuid);
        Location center = clicked.getLocation().add(0.5, 0.6, 0.5);
        effect(center, type == null ? null : type.clickParticle(), "FIREWORKS_SPARK");
        sound(center, type == null ? null : type.clickSound(), XSound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        if (type != null) {
            runRewards(type.rewardCommands(), player);
        }
        plugin.messages().send(player, "season.gift-found",
                Placeholder.parsed("gift_name", giftName),
                Placeholder.unparsed("found", String.valueOf(found)),
                Placeholder.unparsed("total", String.valueOf(total)));
        if (settings.giftSettings().titleClue()) {
            plugin.messages().audience(player).showTitle(Title.title(plugin.messages().render("season.gift-title"),
                    plugin.messages().render("season.gift-subtitle",
                            Placeholder.parsed("gift_name", giftName),
                            Placeholder.unparsed("found", String.valueOf(found)),
                            Placeholder.unparsed("total", String.valueOf(total)))));
        }
        if (found == total) {
            plugin.messages().send(player, "season.gift-all-found", Placeholder.unparsed("total", String.valueOf(total)));
            runRewards(settings.giftSettings().completionCommands(), player);
            if (settings.giftSettings().titleClue()) {
                plugin.messages().audience(player).showTitle(Title.title(
                        plugin.messages().render("season.gift-all-title"),
                        plugin.messages().render("season.gift-all-subtitle",
                                Placeholder.unparsed("total", String.valueOf(total)))));
            }
        }
    }

    // Only the gifts standing now count, so an old find does not push
    // anyone past the total.
    private int foundOf(UUID player) {
        int found = 0;
        for (GiftLocation gift : activeGifts.values()) {
            if (claims.hasClaimed(player, gift.id())) {
                found++;
            }
        }
        return found;
    }

    private static void runRewards(List<String> commands, Player player) {
        for (String command : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }
    }

    boolean nearGift(Player player) {
        return nearGift.contains(player.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // After the join messages of everyone else, or it gets buried.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                lobbyEffects.greet(player, currentPhase, clock.currentTimeMillis());
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lobbyEffects.forget(event.getPlayer().getUniqueId());
        nearGift.remove(event.getPlayer().getUniqueId());
        lastGiftClick.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onBingoClaim(BingoClaimEvent event) {
        if (event.getPlayer() != null) {
            onClaim(event.getPlayer());
        }
    }

    /** A small firework over a player who just claimed a cell. */
    public void onClaim(Player player) {
        if (!currentPhase.isVisualsActive() || !settings.claimFirework()) {
            return;
        }
        Location loc = player.getLocation().add(0, 3.0, 0);
        effect(loc, null, "FIREWORKS_SPARK");
        XSound.ENTITY_FIREWORK_ROCKET_BLAST.play(loc, 0.7f, 1.2f);
    }

    /** Snowflakes over the waiting players during the countdown. */
    public void onCountdownSnow(Location center) {
        if (center == null || center.getWorld() == null || !currentPhase.isVisualsActive() || !settings.snowfall()) {
            return;
        }
        effect(center.clone().add(0, 4.0, 0), null, "SNOW_SHOVEL");
        XSound.BLOCK_NOTE_BLOCK_CHIME.play(center, 0.5f, 1.4f);
    }

    /** Jumps to a phase for testing, with the clock moved to match. */
    public void simulatePhase(SeasonPhase phase) {
        long target;
        switch (phase) {
            case PRE_SEASON:
                target = window().preSeasonStartMillis() + 1000L;
                break;
            case ACTIVE:
                target = window().activeStartMillis() + 1000L;
                break;
            case WRAP_UP:
                target = window().activeEndMillis() + 1000L;
                break;
            default:
                target = window().wrapUpEndMillis() + 1000L;
        }
        clock = new SimulatedSeasonClock(target);
        transitionTo(phase);
    }

    public void resetClock() {
        clock = SeasonClock.system();
        tick();
    }

    public SeasonPhase currentPhase() {
        return currentPhase;
    }

    public SeasonSettings settings() {
        return settings;
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
