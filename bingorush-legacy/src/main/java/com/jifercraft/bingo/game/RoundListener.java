package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Turns gameplay events into claim checks. The events cover the fast paths:
 * picking items up, clicking them out of chests or crafts, filling buckets.
 * The periodic sweep in Game catches what no event fires for, such as /give.
 * Only events that exist on 1.8.8 live here; advancement events are
 * registered separately on 1.12 and newer.
 */
public final class RoundListener implements Listener {

    private final BingoRush plugin;
    private final Game game;

    public RoundListener(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    // Deprecated on newer servers in favor of an entity event that 1.8 lacks,
    // and still fired for players on all of them.
    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        // The item is not in the inventory until the event is done.
        Bukkit.getScheduler().runTask(plugin, () -> game.checkClaims(player));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        MenuHolder menu = MenuHolder.of(event.getInventory(), null);
        if (menu != null) {
            // Our menus are read only: the click runs the action of the slot
            // and nothing moves.
            event.setCancelled(true);
            boolean top = event.getRawSlot() >= 0 && event.getRawSlot() < event.getInventory().getSize();
            if (top && event.getWhoClicked() instanceof Player) {
                game.menuClick((Player) event.getWhoClicked(), menu, event.getRawSlot());
            }
            return;
        }
        // The card item cannot be moved into chests or around the inventory.
        if (game.isCardItem(event.getCurrentItem()) || game.isCardItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player) {
            Player player = (Player) event.getWhoClicked();
            Bukkit.getScheduler().runTask(plugin, () -> game.checkClaims(player));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (MenuHolder.of(event.getInventory(), null) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack newItem = player.getInventory().getItem(event.getNewSlot());
        if (newItem != null && newItem.getType() == Material.COMPASS) {
            game.pointCompass(player, false);
        }
    }

    /** Nor dropped on the ground, where the round end would not find it. */
    @EventHandler
    public void onCardDrop(PlayerDropItemEvent event) {
        if (game.isCardItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> game.checkClaims(player));
    }

    /**
     * Right click with the card opens it. From 1.9 the event fires once per
     * hand, but only the hand holding the card carries it as the item, so
     * this still opens one menu.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (game.isCardItem(item)) {
            event.setCancelled(true);
            game.openCard(event.getPlayer());
        } else if (item != null && item.getType() == Material.COMPASS) {
            game.pointCompass(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        game.playerDied(event);
    }

    // The player is not back in the world yet during this event, so the game
    // mode change and the card wait one tick.
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                game.playerRespawned(player);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        game.playerJoined(event.getPlayer());
    }

    /** An open menu nobody can see anymore is not refreshed. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (MenuHolder.of(event.getInventory(), null) != null) {
            game.menuClosed(event.getPlayer().getUniqueId());
        }
    }

    /** In case the close event slips past, the quit event drops the menu too. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        game.menuClosed(event.getPlayer().getUniqueId());
    }
}
