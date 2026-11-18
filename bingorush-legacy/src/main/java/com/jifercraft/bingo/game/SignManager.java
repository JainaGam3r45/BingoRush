package com.jifercraft.bingo.game;

import com.cryptomorin.xseries.XBlock;
import com.cryptomorin.xseries.XMaterial;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.config.GameMapDefinition;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Interactive signs that display round status and let players join games.
 */
public final class SignManager implements Listener {

    private final BingoRush plugin;
    private final Game game;
    private final File storageFile;
    private final List<SignStorage.SavedSign> signs = new ArrayList<SignStorage.SavedSign>();

    public SignManager(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
        this.storageFile = new File(plugin.getDataFolder(), "data/signs.json");
        this.signs.addAll(SignStorage.load(storageFile));
    }

    public void updateAll() {
        Iterator<SignStorage.SavedSign> it = signs.iterator();
        while (it.hasNext()) {
            SignStorage.SavedSign saved = it.next();
            World world = Bukkit.getWorld(saved.world());
            if (world == null || !world.isChunkLoaded(saved.x() >> 4, saved.z() >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(saved.x(), saved.y(), saved.z());
            if (!(block.getState() instanceof Sign)) {
                it.remove();
                save();
                continue;
            }
            renderSign((Sign) block.getState(), saved.mapId());
        }
    }

    private void renderSign(Sign sign, String mapId) {
        GameMapDefinition map = plugin.bingoConfig() != null ? plugin.bingoConfig().getMap(mapId) : null;
        String mapName = map != null ? map.name() : mapId;

        sign.setLine(0, plugin.messages().legacy(plugin.messages().render("brand")));
        sign.setLine(1, mapName);
        Round round = game.round();
        boolean isCurrentMap = game.isMapSelected(mapId);
        String statusKey = "sign-status-waiting";
        if (round != null && isCurrentMap) {
            if (round.state() == Round.State.COUNTDOWN) {
                statusKey = "sign-status-starting";
            } else if (round.state() == Round.State.PAUSED) {
                statusKey = "sign-status-paused";
            } else {
                statusKey = "sign-status-in-game";
            }
        }
        sign.setLine(2, plugin.messages().legacy(plugin.messages().render(statusKey)));
        int current = isCurrentMap ? game.teams().totalMembers() : 0;
        int max = game.teams().all().size() * game.teamSizeCap();
        sign.setLine(3, current + "/" + max);
        sign.update(true, false);
        updateAttachedBlock(sign, round, isCurrentMap);
    }

    private void updateAttachedBlock(Sign sign, Round round, boolean isCurrentMap) {
        Block attached = getAttachedBlock(sign.getBlock());
        if (attached == null) {
            return;
        }
        XMaterial concrete;
        if (round != null && isCurrentMap) {
            if (round.state() == Round.State.COUNTDOWN) {
                concrete = XMaterial.YELLOW_CONCRETE;
            } else if (round.state() == Round.State.PAUSED) {
                concrete = XMaterial.ORANGE_CONCRETE;
            } else {
                concrete = XMaterial.RED_CONCRETE;
            }
        } else {
            concrete = XMaterial.LIME_CONCRETE;
        }
        if (!XBlock.isSimilar(attached, concrete) && attached.getType() != Material.AIR
                && !attached.isLiquid() && attached.getType() != Material.BEDROCK
                && attached.getType() != Material.BARRIER) {
            XBlock.setType(attached, concrete);
        }
    }

    private Block getAttachedBlock(Block block) {
        if (block.getState().getData() instanceof org.bukkit.material.Sign) {
            org.bukkit.material.Sign signData = (org.bukkit.material.Sign) block.getState().getData();
            return block.getRelative(signData.getAttachedFace());
        }
        return block.getRelative(BlockFace.DOWN);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        String line0 = event.getLine(0) != null ? event.getLine(0).trim() : "";
        if (!line0.equalsIgnoreCase("[bingo]") && !line0.equalsIgnoreCase("[bingorush]")) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("bingo.admin")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        String mapId = event.getLine(1) != null ? event.getLine(1).trim().toLowerCase(Locale.ROOT) : "";
        if (mapId.isEmpty()) {
            mapId = "partida-1";
        }
        Block block = event.getBlock();
        signs.add(new SignStorage.SavedSign(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), mapId));
        save();
        plugin.messages().send(player, "sign-created", Placeholder.unparsed("map", mapId));
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                updateAll();
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Sign)) {
            return;
        }
        SignStorage.SavedSign match = findSign(block);
        if (match == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (game.round() == null) {
            game.selectMap(match.mapId());
            game.giveLobbyWaitingItems(player);
            Location waitingLobby = game.waitingLobbyLocation(match.mapId());
            if (waitingLobby != null) {
                player.teleport(waitingLobby);
            }
            game.openTeamMenu(player);
        } else if (game.isMapSelected(match.mapId())) {
            game.randomJoin(player);
        } else {
            plugin.messages().send(player, "round-already-running");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        // 1. Direct sign break
        SignStorage.SavedSign directMatch = findSign(block);
        if (directMatch != null) {
            if (!player.hasPermission("bingo.admin")) {
                event.setCancelled(true);
                plugin.messages().send(player, "sign-cannot-break");
                return;
            }
            signs.remove(directMatch);
            save();
            plugin.messages().send(player, "sign-removed");
        }

        // 2. Break of the support block holding an attached sign
        List<SignStorage.SavedSign> attachedSigns = findSignsAttachedTo(block);
        if (!attachedSigns.isEmpty()) {
            if (!player.hasPermission("bingo.admin")) {
                event.setCancelled(true);
                plugin.messages().send(player, "sign-cannot-break");
                return;
            }
            for (SignStorage.SavedSign s : attachedSigns) {
                signs.remove(s);
            }
            save();
            plugin.messages().send(player, "sign-removed");
        }
    }

    private List<SignStorage.SavedSign> findSignsAttachedTo(Block block) {
        List<SignStorage.SavedSign> attached = new ArrayList<>();
        BlockFace[] faces = new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};
        for (BlockFace face : faces) {
            Block neighbor = block.getRelative(face);
            SignStorage.SavedSign saved = findSign(neighbor);
            if (saved != null) {
                Block targetAttached = getAttachedBlock(neighbor);
                if (targetAttached != null && targetAttached.getX() == block.getX()
                        && targetAttached.getY() == block.getY()
                        && targetAttached.getZ() == block.getZ()
                        && targetAttached.getWorld().getName().equals(block.getWorld().getName())) {
                    attached.add(saved);
                }
            }
        }
        return attached;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        final Block block = event.getBlock();
        final SignStorage.SavedSign match = findSign(block);
        if (match != null) {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    if (!(block.getState() instanceof Sign)) {
                        if (signs.remove(match)) {
                            save();
                        }
                    }
                }
            });
        }
    }

    private SignStorage.SavedSign findSign(Block block) {
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        for (SignStorage.SavedSign s : signs) {
            if (s.x() == x && s.y() == y && s.z() == z && s.world().equals(worldName)) {
                return s;
            }
        }
        return null;
    }

    private void save() {
        SignStorage.save(storageFile, signs);
    }
}
