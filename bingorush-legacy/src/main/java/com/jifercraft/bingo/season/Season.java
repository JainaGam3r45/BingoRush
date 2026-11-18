package com.jifercraft.bingo.season;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XSound;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.compat.ServerVersion;
import java.io.File;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

/**
 * Manages seasonal event cycles and safe block restoration on Spigot/Paper 1.8.8
 * to 1.21.x. Adapts block data serialization and particle effects to the server version.
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
            playParticleSafe(loc, "FIREWORKS_SPARK", 16);
            XSound.ENTITY_FIREWORK_ROCKET_BLAST.play(loc, 0.7f, 1.2f);
        }
    }

    public void onCountdownSnow(Location center) {
        if (!currentPhase.isVisualsActive() || !settings.snowfall() || center == null) {
            return;
        }
        World world = center.getWorld();
        if (world != null) {
            playParticleSafe(center.clone().add(0, 4.0, 0), "SNOW_SHOVEL", 30);
            XSound.BLOCK_NOTE_BLOCK_CHIME.play(center, 0.5f, 1.4f);
        }
    }

    @SuppressWarnings("deprecation")
    private void playParticleSafe(Location loc, String effectName, int count) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        try {
            Effect effect = Effect.valueOf(effectName);
            for (int i = 0; i < Math.min(count, 5); i++) {
                world.playEffect(loc, effect, 0);
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    public boolean modifyBlock(Block block, Material newMaterial, String tag) {
        if (block == null || block.getState() instanceof InventoryHolder) {
            return false;
        }
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        if (!journal.isTracked(worldName, x, y, z)) {
            String serialized = block.getType().name() + ":" + block.getData();
            BlockJournalEntry entry = new BlockJournalEntry(
                    worldName, x, y, z, serialized, tag, clock.currentTimeMillis());
            journal.record(entry);
        }

        block.setType(newMaterial, false);
        return true;
    }

    @SuppressWarnings("deprecation")
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
                String[] parts = entry.blockData().split(":");
                Material mat = Material.matchMaterial(parts[0]);
                byte data = parts.length > 1 ? Byte.parseByte(parts[1]) : 0;
                if (mat != null) {
                    block.setTypeIdAndData(mat.getId(), data, false);
                    count++;
                }
            } catch (Exception e) {
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
