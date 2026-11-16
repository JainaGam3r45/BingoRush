package com.jifercraft.bingo.game;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Turns gameplay events into claim checks. The events cover the fast paths:
 * picking items up, clicking them out of chests or crafts, filling buckets.
 * InventoryClickEvent also fires for its subclasses, so crafting and villager
 * trades come through the same handler. The periodic sweep in Game catches
 * what no event fires for, such as /give.
 */
public final class RoundListener implements Listener {

    private final Game game;

    public RoundListener(Game game) {
        this.game = game;
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            game.checkClaims(player);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        // The card menu is a read only view; the check is a tagged item in
        // the first slot, which every opened menu always has.
        if (game.isCardMenuView(event.getView())) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            game.checkClaims(player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (game.isCardMenuView(event.getView())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        game.checkClaims(event.getPlayer());
    }

    /** A player who left during a round gets their saved items back. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        game.restoreInventory(event.getPlayer());
    }

    /** An open card menu nobody can see anymore is not refreshed. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        game.cardMenuClosed(event.getPlayer().getUniqueId());
    }

    /** In case the close event slips past, the quit event drops the menu too. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        game.cardMenuClosed(event.getPlayer().getUniqueId());
    }
}
