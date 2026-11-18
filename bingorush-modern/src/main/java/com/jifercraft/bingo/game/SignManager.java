package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.config.GameMapDefinition;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.data.type.WallHangingSign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Interactive signs that display round status and let players join games.
 */
public final class SignManager implements Listener {

    private final BingoRush plugin;
    private final Game game;
    private final File storageFile;
    private final List<SignStorage.SavedSign> signs = new ArrayList<>();

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
            if (!(block.getState() instanceof Sign sign)) {
                it.remove();
                save();
                continue;
            }
            renderSign(sign, saved.mapId());
        }
    }

    private void renderSign(Sign sign, String mapId) {
        GameMapDefinition map = plugin.bingoConfig() != null ? plugin.bingoConfig().getMap(mapId) : null;
        String mapName = map != null ? map.name() : mapId;

        org.bukkit.block.sign.SignSide side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
        side.line(0, plugin.messages().render("brand"));
        side.line(1, Component.text(mapName, NamedTextColor.WHITE));
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
        side.line(2, plugin.messages().render(statusKey));
        int current = isCurrentMap ? game.teams().totalMembers() : 0;
        int max = game.teams().all().size() * game.teamSizeCap();
        side.line(3, Component.text(current + "/" + max, NamedTextColor.GRAY));
        sign.update(true, false);
        updateAttachedBlock(sign, round, isCurrentMap);
    }

    private void updateAttachedBlock(Sign sign, Round round, boolean isCurrentMap) {
        Block attached = getAttachedBlock(sign.getBlock());
        if (attached == null) {
            return;
        }
        Material concrete;
        if (round != null && isCurrentMap) {
            if (round.state() == Round.State.COUNTDOWN) {
                concrete = Material.YELLOW_CONCRETE;
            } else if (round.state() == Round.State.PAUSED) {
                concrete = Material.ORANGE_CONCRETE;
            } else {
                concrete = Material.RED_CONCRETE;
            }
        } else {
            concrete = Material.LIME_CONCRETE;
        }
        if (attached.getType() != concrete && !attached.isEmpty() && !attached.isLiquid()
                && attached.getType() != Material.BEDROCK && attached.getType() != Material.BARRIER) {
            attached.setType(concrete);
        }
    }

    private Block getAttachedBlock(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof WallSign wallSign) {
            return block.getRelative(wallSign.getFacing().getOppositeFace());
        }
        if (data instanceof WallHangingSign wallHanging) {
            return block.getRelative(wallHanging.getFacing().getOppositeFace());
        }
        if (data instanceof HangingSign) {
            return block.getRelative(BlockFace.UP);
        }
        return block.getRelative(BlockFace.DOWN);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        String line0 = event.line(0) != null ? plain(event.line(0)).trim() : "";
        if (!line0.equalsIgnoreCase("[bingo]") && !line0.equalsIgnoreCase("[bingorush]")) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("bingo.admin")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        String mapId = event.line(1) != null ? plain(event.line(1)).trim().toLowerCase(Locale.ROOT) : "";
        if (mapId.isEmpty()) {
            mapId = "partida-1";
        }
        Block block = event.getBlock();
        signs.add(new SignStorage.SavedSign(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), mapId));
        save();
        plugin.messages().send(player, "sign-created", Placeholder.unparsed("map", mapId));
        Bukkit.getScheduler().runTask(plugin, this::updateAll);
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
        if (!(block.getState() instanceof Sign)) {
            return;
        }
        SignStorage.SavedSign match = findSign(block);
        if (match == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("bingo.admin")) {
            event.setCancelled(true);
            plugin.messages().send(player, "sign-cannot-break");
            return;
        }
        signs.remove(match);
        save();
        plugin.messages().send(player, "sign-removed");
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

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
