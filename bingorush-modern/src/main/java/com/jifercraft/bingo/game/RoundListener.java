package com.jifercraft.bingo.game;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

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
        if (event.getView().getTopInventory().getHolder() instanceof ModeVoteManager.VoteMenuHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() == event.getView().getTopInventory()
                    && event.getWhoClicked() instanceof Player player) {
                holder.handleClick(player, event.getSlot(), event.getClick());
            }
            return;
        }
        if (game.isCardMenuView(event.getView())) {
            event.setCancelled(true);
            return;
        }
        // The lobby menus are read only too: the click runs the action the
        // item carries, and everything else in the view is blocked.
        if (game.isTeamMenuView(event.getView())) {
            event.setCancelled(true);
            if (event.getClickedInventory() == event.getView().getTopInventory()
                    && event.getWhoClicked() instanceof Player player) {
                game.teamMenuClick(player, event.getCurrentItem());
            }
            return;
        }
        if (game.isLobbyItem(event.getCurrentItem()) || game.isLobbyItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            game.checkClaims(player);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ModeVoteManager.VoteMenuHolder
                || game.isCardMenuView(event.getView())
                || game.isTeamMenuView(event.getView())) {
            event.setCancelled(true);
        } else if (game.isLobbyItem(event.getOldCursor())) {
            event.setCancelled(true);
        } else if (game.blocksCardDrag(event) && event.getWhoClicked() instanceof Player player) {
            event.setCancelled(true);
            game.cardLocked(player);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (game.modeVoteManager() != null) {
            game.modeVoteManager().onInventoryClose(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        game.checkClaims(event.getPlayer());
    }

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        game.advancementDone(event);
    }

    /** Right click with the card item opens the card, nothing else happens. */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (game.isLobbyVoteItem(event.getItem())) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND || event.getHand() == EquipmentSlot.OFF_HAND) {
                if (game.modeVoteManager() != null) {
                    game.modeVoteManager().promptVote(event.getPlayer());
                }
            }
            return;
        }
        if (game.isLobbyTeamsItem(event.getItem())) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND || event.getHand() == EquipmentSlot.OFF_HAND) {
                game.openTeamMenu(event.getPlayer());
            }
            return;
        }
        if (game.isLobbyLeaveItem(event.getItem())) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND || event.getHand() == EquipmentSlot.OFF_HAND) {
                game.leaveWaitingLobby(event.getPlayer());
            }
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
        if (game.isLobbyTeamsItem(event.getItem())) {
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
        ItemStack newItem = player.getInventory().getItem(event.getNewSlot());
        if (newItem != null && newItem.getType() == Material.COMPASS) {
            game.pointCompass(player, false);
        }
    }

    /** The card item cannot be moved into chests or around the inventory. */
    @EventHandler
    public void onCardClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && game.blocksCardClick(player, event)) {
            event.setCancelled(true);
            game.cardLocked(player);
        }
    }

    /** An item frame, armor stand or allay would take the book out of the hand. */
    @EventHandler(ignoreCancelled = true)
    public void onCardEntityUse(PlayerInteractEntityEvent event) {
        if (CardRules.blocksEntityUse(event.getRightClicked().getType().name())
                && game.holdsCard(event.getPlayer(), event.getHand())) {
            event.setCancelled(true);
            game.cardLocked(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCardArmorStand(PlayerArmorStandManipulateEvent event) {
        if (game.isCardItem(event.getPlayerItem())) {
            event.setCancelled(true);
            game.cardLocked(event.getPlayer());
        }
    }

    // However a card ends up as an item on the ground, a death included, it
    // is not kept; its owner gets another one.
    @EventHandler(ignoreCancelled = true)
    public void onCardSpawn(ItemSpawnEvent event) {
        if (game.isCardItem(event.getEntity().getItemStack()) || game.isLobbyItem(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** Nor dropped on the ground, where the round end would not find it. */
    @EventHandler
    public void onCardDrop(PlayerDropItemEvent event) {
        if (game.isLobbyItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            return;
        }
        if (game.isCardItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            game.cardLocked(event.getPlayer());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        game.playerDied(event);
    }

    // Post respawn: the player is back in the world, so a game mode change
    // or a new item sticks.
    @EventHandler
    public void onRespawn(PlayerPostRespawnEvent event) {
        game.playerRespawned(event.getPlayer());
    }

    // Without a bed or an anchor in the round's world, vanilla sends the
    // player to the server spawn, out of the round. HIGH so a bed in another
    // world does not win either.
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawnLocation(PlayerRespawnEvent event) {
        Location point = game.respawnLocation(event.getPlayer());
        if (point == null) {
            return;
        }
        boolean ownBed = (event.isBedSpawn() || event.isAnchorSpawn())
                && point.getWorld().equals(event.getRespawnLocation().getWorld());
        if (!ownBed) {
            event.setRespawnLocation(point);
        }
    }


    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        game.playerJoined(event.getPlayer());
    }

    /** An open menu nobody can see anymore is not refreshed. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        game.menuClosed(event.getPlayer().getUniqueId());
    }

    /** In case the close event slips past, the quit event drops the menu too. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        game.playerQuit(event.getPlayer().getUniqueId());
    }
}
