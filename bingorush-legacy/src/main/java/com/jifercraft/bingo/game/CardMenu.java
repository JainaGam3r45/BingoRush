package com.jifercraft.bingo.game;

import com.cryptomorin.xseries.XMaterial;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.ClaimBoard;
import com.jifercraft.bingo.config.Messages;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The card menu: the 5x5 card centered in a 9x5 inventory, one icon per cell
 * with the tier and the claim state in the lore, the viewer's own claims
 * glowing. Every open menu is tracked, so when a claim lands the plugin
 * redraws all the menus of that round in the same tick.
 */
public final class CardMenu {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    // The card sits centered, two columns away from each border.
    private static final int FIRST_CARD_COLUMN = 2;
    private static final int COLUMNS = 9;

    // One entry per player with the menu open, with the round and viewed team.
    private static final class OpenMenu {
        private final Round round;
        private final Inventory top;
        private final int teamId;

        private OpenMenu(Round round, Inventory top, int teamId) {
            this.round = round;
            this.top = top;
            this.teamId = teamId;
        }
    }

    private final BingoRush plugin;
    private final Messages messages;
    private final Teams teams;
    private final Map<UUID, OpenMenu> openMenus = new HashMap<>();

    public CardMenu(BingoRush plugin, Teams teams) {
        this.plugin = plugin;
        this.messages = plugin.messages();
        this.teams = teams;
    }

    public void open(Player player, Round round, Team viewer, boolean spectator) {
        Component title = spectator
                ? messages.render("menu-title-spectator", Game.teamPlaceholder(viewer))
                : messages.render("menu-title");
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CARD);
        Inventory top = Bukkit.createInventory(holder, 45, plugin.inventoryTitle(title));
        holder.attach(top);
        render(top, round, viewer);
        player.openInventory(top);
        openMenus.put(player.getUniqueId(), new OpenMenu(round, top, viewer.id()));
    }

    /**
     * Redraws every open menu of this round, called in the same tick the
     * claim landed. The close event drops menus nobody has open anymore.
     */
    public void refresh(Round round) {
        Iterator<Map.Entry<UUID, OpenMenu>> iterator = openMenus.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, OpenMenu> entry = iterator.next();
            OpenMenu menu = entry.getValue();
            if (menu.round != round) {
                continue;
            }
            if (Bukkit.getPlayer(entry.getKey()) == null) {
                iterator.remove();
                continue;
            }
            Team viewer = teams.byId(menu.teamId);
            if (viewer != null) {
                render(menu.top, round, viewer);
            }
        }
    }

    /** Closes every open menu, for when the round ends. */
    public void closeAll() {
        List<Player> viewers = new ArrayList<>();
        for (UUID playerId : openMenus.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                viewers.add(player);
            }
        }
        openMenus.clear();
        // closeInventory fires the close event, so the map is empty first.
        for (Player player : viewers) {
            player.closeInventory();
        }
    }

    /** Drops the tracking of one viewer, from the close and quit events. */
    public void forget(UUID playerId) {
        openMenus.remove(playerId);
    }

    private void render(Inventory top, Round round, Team viewer) {
        for (int slot = 0; slot < top.getSize(); slot++) {
            int column = slot % COLUMNS;
            if (column < FIRST_CARD_COLUMN || column >= FIRST_CARD_COLUMN + BingoCard.COLUMNS) {
                top.setItem(slot, LegacyItems.item(XMaterial.GRAY_STAINED_GLASS_PANE, " ", null, false));
            }
        }
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            int row = cell / BingoCard.COLUMNS;
            int column = cell % BingoCard.COLUMNS;
            top.setItem(row * COLUMNS + column + FIRST_CARD_COLUMN, cellItem(round, viewer, cell));
        }
    }

    private ItemStack cellItem(Round round, Team viewer, int cell) {
        boolean advancement = round.mode() == BingoGameMode.ADVANCEMENTS;
        String key = round.card().item(cell);
        ClaimBoard board = round.board();
        boolean lockout = board.isLockout();
        OptionalInt owner = board.owner(cell);
        boolean own = owner.isPresent() && owner.getAsInt() == viewer.id();
        boolean rival = lockout && owner.isPresent() && owner.getAsInt() != viewer.id();

        XMaterial icon;
        String name = null;
        if (rival) {
            Team rivalTeam = teams.byId(owner.getAsInt());
            icon = LegacyItems.paneFor(rivalTeam != null ? rivalTeam.color() : null);
        } else if (advancement) {
            icon = XMaterial.KNOWLEDGE_BOOK.isSupported() ? XMaterial.KNOWLEDGE_BOOK : XMaterial.BOOK;
        } else {
            XMaterial material = plugin.bingoConfig().material(key);
            icon = material != null ? material : XMaterial.BARRIER;
        }
        // Item cells keep the client's own item name, already in the player's
        // language; display names here are plain text and cannot translate.
        if (advancement || rival) {
            name = messages.legacy(messages.render("menu-cell-name",
                    Placeholder.unparsed("item", LegacyItems.prettyKey(key)),
                    Placeholder.unparsed("advancement", LegacyItems.prettyKey(key))));
        }

        String tierId = (advancement ? plugin.bingoConfig().advancementPool() : plugin.bingoConfig().pool()).tierOf(key);
        List<Component> lore = new ArrayList<>();
        lore.add(messages.render("menu-cell-separator"));
        if (tierId != null) {
            lore.add(messages.render("menu-cell-difficulty",
                    Placeholder.component("tier", messages.render("tier-" + tierId))));
            lore.add(messages.render("menu-cell-separator"));
        }
        String hint = advancement ? "menu-status-available-hint-advancement" : "menu-status-available-hint";
        if (lockout) {
            if (!owner.isPresent()) {
                lore.add(messages.render("menu-status-available"));
                lore.add(messages.render(hint));
            } else if (own) {
                lore.add(messages.render("menu-status-lockout-own"));
                lore.add(messages.render("menu-status-own-time", time(board.claimTime(cell, viewer.id()))));
            } else {
                Team rivalTeam = teams.byId(owner.getAsInt());
                TagResolver rivalTag = rivalTeam != null ? Game.teamPlaceholder(rivalTeam) : Placeholder.unparsed("team", "?");
                lore.add(messages.render("menu-status-lockout-locked"));
                lore.add(messages.render("menu-status-lockout-claimed-by", rivalTag));
                lore.add(messages.render("menu-status-lockout-time", time(board.claimTime(cell, owner.getAsInt()))));
                lore.add(messages.render("menu-status-lockout-hint"));
            }
        } else {
            List<Integer> claimers = board.claimers(cell);
            if (claimers.isEmpty()) {
                lore.add(messages.render("menu-status-available"));
                lore.add(messages.render(hint));
            } else {
                if (own) {
                    lore.add(messages.render("menu-status-own"));
                    lore.add(messages.render("menu-status-own-by", Game.teamPlaceholder(viewer)));
                    lore.add(messages.render("menu-status-own-time", time(board.claimTime(cell, viewer.id()))));
                }
                for (int teamId : claimers) {
                    if (teamId == viewer.id()) {
                        continue;
                    }
                    Team claimer = teams.byId(teamId);
                    if (claimer != null) {
                        lore.add(messages.render("menu-status-rival", Game.teamPlaceholder(claimer)));
                    }
                }
            }
        }
        lore.add(messages.render("menu-cell-separator"));
        return LegacyItems.item(icon, name, messages.legacy(lore), own);
    }

    private static TagResolver time(long epochMs) {
        return Placeholder.unparsed("time", CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())));
    }
}
