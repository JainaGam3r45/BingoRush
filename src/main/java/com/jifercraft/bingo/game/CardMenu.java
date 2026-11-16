package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.ClaimBoard;
import com.jifercraft.bingo.config.Messages;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.MenuType;
import org.bukkit.persistence.PersistentDataType;

/**
 * The /bingo carta menu: the 5x5 card centered in a 9x5 view, one icon per
 * cell with the claim state in the lore. It is a snapshot on open, not a live
 * view; the player runs the command again when they want it fresh. Every item
 * carries a persistent tag, and the listener checks the first slot of the top
 * inventory with it to make the whole view read only.
 */
public final class CardMenu {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    // The card sits centered, two columns away from each border.
    private static final int FIRST_CARD_COLUMN = 2;
    private static final int COLUMNS = 9;

    private final BingoRush plugin;
    private final Messages messages;
    private final Teams teams;
    private final NamespacedKey tag;

    public CardMenu(BingoRush plugin, Teams teams, NamespacedKey tag) {
        this.plugin = plugin;
        this.messages = plugin.messages();
        this.teams = teams;
        this.tag = tag;
    }

    /** True when the stack is one of ours, so its view must stay read only. */
    public boolean isMenuItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    public void open(Player player, Round round, Team viewer) {
        InventoryView view = MenuType.GENERIC_9X5.builder()
                .title(messages.render("menu-title"))
                .build(player);
        int rows = view.getTopInventory().getSize() / COLUMNS;
        for (int slot = 0; slot < view.getTopInventory().getSize(); slot++) {
            int column = slot % COLUMNS;
            if (column < FIRST_CARD_COLUMN || column >= FIRST_CARD_COLUMN + BingoCard.COLUMNS) {
                view.getTopInventory().setItem(slot, filler());
            }
        }
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            int row = cell / BingoCard.COLUMNS;
            int column = cell % BingoCard.COLUMNS;
            int slot = row * COLUMNS + column + FIRST_CARD_COLUMN;
            if (row >= rows) {
                break;
            }
            view.getTopInventory().setItem(slot, cellItem(round, viewer, cell));
        }
        view.open();
    }

    private ItemStack filler() {
        ItemStack stack = ItemType.GRAY_STAINED_GLASS_PANE.createItemStack();
        stack.editMeta(meta -> {
            meta.displayName(Component.empty());
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    private ItemStack cellItem(Round round, Team viewer, int cell) {
        String itemKey = round.card().item(cell);
        ItemType type = plugin.bingoConfig().itemType(itemKey);
        ItemStack stack = (type == null ? ItemType.BARRIER : type).createItemStack();
        ClaimBoard board = round.board();
        String tierId = plugin.bingoConfig().pool().tierOf(itemKey);
        boolean own = board.claimers(cell).contains(viewer.id());
        stack.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            if (tierId != null) {
                lore.add(messages.render("menu-cell-tier",
                        Placeholder.unparsed("tier", tierId)));
            }
            List<Integer> claimers = board.claimers(cell);
            if (claimers.isEmpty()) {
                lore.add(messages.render("menu-cell-unclaimed"));
            } else {
                for (int teamId : claimers) {
                    Team claimer = teams.byId(teamId);
                    if (claimer != null) {
                        lore.add(messages.render("menu-claimed-by", Game.teamPlaceholder(claimer)));
                    }
                }
                if (own) {
                    long claimedAt = board.claimTime(cell, viewer.id());
                    lore.add(messages.render("menu-claimed-at",
                            Placeholder.unparsed("time", CLOCK.format(
                                    Instant.ofEpochMilli(claimedAt).atZone(ZoneId.systemDefault())))));
                }
            }
            meta.lore(lore);
            // The viewer's own claims glow; rival claims stay dull, so the
            // state reads at a glance before the lore.
            meta.setEnchantmentGlintOverride(own ? Boolean.TRUE : Boolean.FALSE);
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }
}
