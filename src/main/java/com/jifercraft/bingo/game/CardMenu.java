package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.ClaimBoard;
import com.jifercraft.bingo.config.Messages;
import io.papermc.paper.advancement.AdvancementDisplay;
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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.MenuType;
import org.bukkit.persistence.PersistentDataType;

/**
 * The card menu: the 5x5 card centered in a 9x5 view, one icon per cell with
 * the tier and the claim state in the lore, the viewer's own claims glowing.
 * Every open view is tracked, so when a claim lands the plugin redraws all
 * the menus of that round in the same tick. Every item carries a persistent
 * tag, and the listener checks the first slot of the top inventory with it to
 * make the whole view read only.
 */
public final class CardMenu {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    // The card sits centered, two columns away from each border.
    private static final int FIRST_CARD_COLUMN = 2;
    private static final int COLUMNS = 9;

    // One entry per player with the menu open, with the round and viewed team.
    private record OpenMenu(Round round, Inventory top, int teamId) {
    }

    private final BingoRush plugin;
    private final Messages messages;
    private final Teams teams;
    private final NamespacedKey tag;
    private final Map<UUID, OpenMenu> openMenus = new HashMap<>();

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

    public void open(Player player, Round round, Team viewer, boolean spectator) {
        Component title = spectator
                ? messages.render("menu-title-spectator", Game.teamPlaceholder(viewer))
                : messages.render("menu-title");
        InventoryView view = MenuType.GENERIC_9X5.builder()
                .title(title)
                .build(player);
        render(view.getTopInventory(), round, viewer);
        view.open();
        openMenus.put(player.getUniqueId(), new OpenMenu(round, view.getTopInventory(), viewer.id()));
    }

    /**
     * Redraws every open menu of this round, called in the same tick the
     * claim landed. Menus nobody has open anymore are dropped.
     */
    public void refresh(Round round) {
        Iterator<Map.Entry<UUID, OpenMenu>> iterator = openMenus.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, OpenMenu> entry = iterator.next();
            OpenMenu menu = entry.getValue();
            if (menu.round() != round) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || player.getOpenInventory().getTopInventory() != menu.top()) {
                iterator.remove();
                continue;
            }
            Team viewer = teams.byId(menu.teamId());
            if (viewer != null) {
                render(menu.top(), round, viewer);
            }
        }
    }

    /** Closes every open menu, for when the round ends. */
    public void closeAll() {
        List<Player> viewers = new ArrayList<>();
        for (Map.Entry<UUID, OpenMenu> entry : openMenus.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getOpenInventory().getTopInventory() == entry.getValue().top()) {
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
                top.setItem(slot, filler());
            }
        }
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            int row = cell / BingoCard.COLUMNS;
            int column = cell % BingoCard.COLUMNS;
            top.setItem(row * COLUMNS + column + FIRST_CARD_COLUMN, cellItem(round, viewer, cell));
        }
    }

    private ItemStack filler() {
        ItemStack stack = ItemType.GRAY_STAINED_GLASS_PANE.createItemStack();
        stack.editMeta(meta -> {
            meta.displayName(Component.empty());
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    private ItemStack cellItem(Round round, Team viewer, int cell) {
        if (round.mode() == BingoGameMode.ADVANCEMENTS) {
            return advancementCellItem(round, viewer, cell);
        }
        return itemCellItem(round, viewer, cell);
    }

    private ItemStack itemCellItem(Round round, Team viewer, int cell) {
        String itemKey = round.card().item(cell);
        ItemType type = plugin.bingoConfig().itemType(itemKey);
        ClaimBoard board = round.board();
        boolean lockout = board.isLockout();
        OptionalInt owner = board.owner(cell);
        boolean own = owner.isPresent() && owner.getAsInt() == viewer.id();
        boolean rival = lockout && owner.isPresent() && owner.getAsInt() != viewer.id();

        ItemStack stack;
        if (rival) {
            Team rivalTeam = teams.byId(owner.getAsInt());
            stack = stainedGlassPaneFor(rivalTeam != null ? rivalTeam.color() : null).createItemStack();
        } else {
            stack = (type == null ? ItemType.BARRIER : type).createItemStack();
        }

        String tierId = plugin.bingoConfig().pool().tierOf(itemKey);
        stack.editMeta(meta -> {
            Component translatable = Component.translatable(type == null ? itemKey : type.translationKey());
            meta.displayName(messages.render("menu-cell-name", Placeholder.component("item", translatable)));

            List<Component> lore = new ArrayList<>();
            lore.add(messages.render("menu-cell-separator"));
            if (tierId != null) {
                Component tierComp = messages.render("tier-" + tierId);
                lore.add(messages.render("menu-cell-difficulty", Placeholder.component("tier", tierComp)));
            }
            lore.add(messages.render("menu-cell-separator"));

            if (lockout) {
                if (owner.isEmpty()) {
                    lore.add(messages.render("menu-status-available"));
                    lore.add(messages.render("menu-status-available-hint"));
                } else if (own) {
                    lore.add(messages.render("menu-status-lockout-own"));
                    long claimedAt = board.claimTime(cell, viewer.id());
                    lore.add(messages.render("menu-status-own-time",
                            Placeholder.unparsed("time", CLOCK.format(
                                    Instant.ofEpochMilli(claimedAt).atZone(ZoneId.systemDefault())))));
                } else {
                    Team rivalTeam = teams.byId(owner.getAsInt());
                    TagResolver rivalTag = rivalTeam != null ? Game.teamPlaceholder(rivalTeam) : Placeholder.unparsed("team", "?");
                    long claimedAt = board.claimTime(cell, owner.getAsInt());
                    lore.add(messages.render("menu-status-lockout-locked"));
                    lore.add(messages.render("menu-status-lockout-claimed-by", rivalTag));
                    lore.add(messages.render("menu-status-lockout-time",
                            Placeholder.unparsed("time", CLOCK.format(
                                    Instant.ofEpochMilli(claimedAt).atZone(ZoneId.systemDefault())))));
                    lore.add(messages.render("menu-status-lockout-hint"));
                }
            } else {
                List<Integer> claimers = board.claimers(cell);
                if (claimers.isEmpty()) {
                    lore.add(messages.render("menu-status-available"));
                    lore.add(messages.render("menu-status-available-hint"));
                } else {
                    if (own) {
                        lore.add(messages.render("menu-status-own"));
                        lore.add(messages.render("menu-status-own-by", Game.teamPlaceholder(viewer)));
                        long claimedAt = board.claimTime(cell, viewer.id());
                        lore.add(messages.render("menu-status-own-time",
                                Placeholder.unparsed("time", CLOCK.format(
                                        Instant.ofEpochMilli(claimedAt).atZone(ZoneId.systemDefault())))));
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
            meta.lore(lore);

            meta.addItemFlags(ItemFlag.values());
            meta.setEnchantmentGlintOverride(own ? Boolean.TRUE : Boolean.FALSE);
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    private ItemStack advancementCellItem(Round round, Team viewer, int cell) {
        String key = round.card().item(cell);
        Advancement adv = plugin.bingoConfig().advancement(key);
        AdvancementDisplay display = adv != null ? adv.getDisplay() : null;
        ClaimBoard board = round.board();
        boolean lockout = board.isLockout();
        OptionalInt owner = board.owner(cell);
        boolean own = owner.isPresent() && owner.getAsInt() == viewer.id();
        boolean rival = lockout && owner.isPresent() && owner.getAsInt() != viewer.id();

        ItemStack stack;
        if (rival) {
            Team rivalTeam = teams.byId(owner.getAsInt());
            stack = stainedGlassPaneFor(rivalTeam != null ? rivalTeam.color() : null).createItemStack();
        } else if (display != null && display.icon() != null) {
            stack = display.icon().clone();
        } else {
            stack = ItemType.KNOWLEDGE_BOOK.createItemStack();
        }

        String tierId = plugin.bingoConfig().advancementPool().tierOf(key);
        stack.editMeta(meta -> {
            Component advTitle = display != null ? display.title() : Component.text(key);
            meta.displayName(messages.render("menu-cell-name",
                    Placeholder.component("item", advTitle),
                    Placeholder.component("advancement", advTitle)));

            List<Component> lore = new ArrayList<>();
            lore.add(messages.render("menu-cell-separator"));

            if (display != null && display.description() != null) {
                lore.add(messages.render("menu-cell-advancement-desc",
                        Placeholder.component("description", display.description())));
                lore.add(messages.render("menu-cell-separator"));
            }

            if (tierId != null) {
                Component tierComp = messages.render("tier-" + tierId);
                lore.add(messages.render("menu-cell-difficulty", Placeholder.component("tier", tierComp)));
                lore.add(messages.render("menu-cell-separator"));
            }

            if (lockout) {
                if (owner.isEmpty()) {
                    lore.add(messages.render("menu-status-available"));
                    lore.add(messages.render("menu-status-available-hint-advancement"));
                } else if (own) {
                    lore.add(messages.render("menu-status-lockout-own"));
                    long claimedAt = board.claimTime(cell, viewer.id());
                    lore.add(messages.render("menu-status-own-time",
                            Placeholder.unparsed("time", CLOCK.format(
                                    Instant.ofEpochMilli(claimedAt).atZone(ZoneId.systemDefault())))));
                } else {
                    Team rivalTeam = teams.byId(owner.getAsInt());
                    TagResolver rivalTag = rivalTeam != null ? Game.teamPlaceholder(rivalTeam) : Placeholder.unparsed("team", "?");
                    long claimedAt = board.claimTime(cell, owner.getAsInt());
                    lore.add(messages.render("menu-status-lockout-locked"));
                    lore.add(messages.render("menu-status-lockout-claimed-by", rivalTag));
                    lore.add(messages.render("menu-status-lockout-time",
                            Placeholder.unparsed("time", CLOCK.format(
                                    Instant.ofEpochMilli(claimedAt).atZone(ZoneId.systemDefault())))));
                    lore.add(messages.render("menu-status-lockout-hint"));
                }
            } else {
                List<Integer> claimers = board.claimers(cell);
                if (claimers.isEmpty()) {
                    lore.add(messages.render("menu-status-available"));
                    lore.add(messages.render("menu-status-available-hint-advancement"));
                } else {
                    if (own) {
                        lore.add(messages.render("menu-status-own"));
                        lore.add(messages.render("menu-status-own-by", Game.teamPlaceholder(viewer)));
                        long claimedAt = board.claimTime(cell, viewer.id());
                        lore.add(messages.render("menu-status-own-time",
                                Placeholder.unparsed("time", CLOCK.format(
                                        Instant.ofEpochMilli(claimedAt).atZone(ZoneId.systemDefault())))));
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
            meta.lore(lore);

            meta.addItemFlags(ItemFlag.values());
            meta.setEnchantmentGlintOverride(own ? Boolean.TRUE : Boolean.FALSE);
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    private static ItemType stainedGlassPaneFor(TextColor color) {
        if (color == null) {
            return ItemType.RED_STAINED_GLASS_PANE;
        }
        int r = color.red();
        int g = color.green();
        int b = color.blue();

        record ColorPane(ItemType pane, int r, int g, int b) {}
        ColorPane[] panes = {
            new ColorPane(ItemType.WHITE_STAINED_GLASS_PANE, 249, 255, 254),
            new ColorPane(ItemType.ORANGE_STAINED_GLASS_PANE, 249, 128, 29),
            new ColorPane(ItemType.MAGENTA_STAINED_GLASS_PANE, 199, 78, 189),
            new ColorPane(ItemType.LIGHT_BLUE_STAINED_GLASS_PANE, 58, 179, 218),
            new ColorPane(ItemType.YELLOW_STAINED_GLASS_PANE, 254, 216, 61),
            new ColorPane(ItemType.LIME_STAINED_GLASS_PANE, 128, 199, 31),
            new ColorPane(ItemType.PINK_STAINED_GLASS_PANE, 243, 140, 170),
            new ColorPane(ItemType.GRAY_STAINED_GLASS_PANE, 71, 79, 82),
            new ColorPane(ItemType.LIGHT_GRAY_STAINED_GLASS_PANE, 156, 157, 151),
            new ColorPane(ItemType.CYAN_STAINED_GLASS_PANE, 22, 156, 156),
            new ColorPane(ItemType.PURPLE_STAINED_GLASS_PANE, 137, 50, 184),
            new ColorPane(ItemType.BLUE_STAINED_GLASS_PANE, 60, 68, 170),
            new ColorPane(ItemType.BROWN_STAINED_GLASS_PANE, 131, 84, 50),
            new ColorPane(ItemType.GREEN_STAINED_GLASS_PANE, 94, 124, 22),
            new ColorPane(ItemType.RED_STAINED_GLASS_PANE, 176, 46, 38),
            new ColorPane(ItemType.BLACK_STAINED_GLASS_PANE, 29, 29, 33)
        };

        ItemType best = ItemType.RED_STAINED_GLASS_PANE;
        long bestDist = Long.MAX_VALUE;
        for (ColorPane cp : panes) {
            long dr = r - cp.r;
            long dg = g - cp.g;
            long db = b - cp.b;
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = cp.pane;
            }
        }
        return best;
    }
}
