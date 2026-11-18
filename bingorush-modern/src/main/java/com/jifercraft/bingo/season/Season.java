package com.jifercraft.bingo.season;

import com.jifercraft.bingo.BingoRush;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

/**
 * Manages the active seasonal event cycle, periodic phase evaluations,
 * festive visual effects, and atomic block restoration.
 */
public final class Season implements Listener {

    private final BingoRush plugin;
    private final SeasonSettings settings;
    private final File journalFile;
    private final BlockJournal journal;
    private SeasonClock clock;
    private SeasonPhase currentPhase;
    private BukkitTask tickerTask;

    public Season(BingoRush plugin, SeasonSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.journalFile = new File(plugin.getDataFolder(), "data/season/blocks.json");
        this.journal = BlockJournal.load(journalFile, plugin.getLogger());
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
    }

    public void stop() {
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
        if (!journal.isEmpty()) {
            journal.save(journalFile);
        }
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

        switch (newPhase) {
            case PRE_SEASON:
                plugin.messages().broadcast("season.pre-season-chat",
                        Placeholder.unparsed("date", SeasonWindow.formatUtc(settings.window().activeStartMillis())));
                break;
            case ACTIVE:
                plugin.messages().broadcast("season.active-chat");
                break;
            case WRAP_UP:
                plugin.messages().broadcast("season.wrap-up-chat");
                break;
            case INACTIVE:
                if (oldPhase.isVisualsActive()) {
                    restoreBlocks();
                    plugin.messages().broadcast("season.ended-chat");
                }
                break;
        }
    }

    /**
     * Plays safe festive firework particles around a player after claiming a cell.
     * Generates purely visual particles with zero entity damage and no fire.
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
     * Modifies a block while safely recording its original state beforehand.
     * Rejects any container with inventories to prevent item loss or duplication.
     */
    public boolean modifyBlock(Block block, Material newMaterial, String tag) {
        if (block == null || block.getState() instanceof InventoryHolder) {
            return false;
        }
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        // Capture original state before first touch
        if (!journal.isTracked(worldName, x, y, z)) {
            BlockJournalEntry entry = new BlockJournalEntry(
                    worldName, x, y, z,
                    block.getBlockData().getAsString(),
                    tag, clock.currentTimeMillis());
            journal.record(entry);
        }

        block.setType(newMaterial, false);
        return true;
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
                // Safety guard: never overwrite an inventory block
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
}
