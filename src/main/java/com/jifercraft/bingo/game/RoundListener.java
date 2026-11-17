package com.jifercraft.bingo.game;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

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

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        game.checkAdvancementClaim(event.getPlayer(), event.getAdvancement());
    }

    /** Right click with the card item opens the card, nothing else happens. */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!game.isCardItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        // Avoid double-opening when holding the item in one hand and clicking with both
        if (event.getHand() == EquipmentSlot.HAND || event.getHand() == EquipmentSlot.OFF_HAND) {
            game.openCard(event.getPlayer());
        }
    }

    @EventHandler
    public void onCompassInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getItem() == null || event.getItem().getType() != Material.COMPASS) {
            return;
        }
        if (event.getHand() == EquipmentSlot.HAND) {
            game.pointCompass(event.getPlayer(), true);
        } else if (event.getHand() == EquipmentSlot.OFF_HAND
                && event.getPlayer().getInventory().getItemInMainHand().getType() != Material.COMPASS) {
            game.pointCompass(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        org.bukkit.inventory.ItemStack newItem = player.getInventory().getItem(event.getNewSlot());
        if (newItem != null && newItem.getType() == Material.COMPASS) {
            game.pointCompass(player, false);
        }
    }

    /** The card item cannot be moved into chests or around the inventory. */
    @EventHandler
    public void onCardClick(InventoryClickEvent event) {
        if (event.getCurrentItem() != null && game.isCardItem(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    /** Nor dropped on the ground, where the round end would not find it. */
    @EventHandler
    public void onCardDrop(PlayerDropItemEvent event) {
        if (game.isCardItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
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
