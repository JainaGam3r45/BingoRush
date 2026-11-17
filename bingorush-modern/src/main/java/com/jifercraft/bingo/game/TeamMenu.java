package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.Messages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.MenuType;
import org.bukkit.persistence.PersistentDataType;

/**
 * The lobby team menu: one wool block per team with the member list in the
 * lore, and a barrier to leave. Clicks join, switch or leave, and every open
 * view redraws when membership moves, like the card menu does after a claim.
 * Only reachable while no round is running; starting one closes the rest.
 */
public final class TeamMenu {

    private static final int COLUMNS = 9;
    // The lore shows at most this many member names; the rest collapse into one line.
    private static final int MAX_MEMBER_LINES = 10;

    private record OpenMenu(Inventory top) {
    }

    private final BingoRush plugin;
    private final Game game;
    private final Messages messages;
    private final NamespacedKey tag;
    private final NamespacedKey actionKey;
    private final NamespacedKey teamKey;
    private final Map<UUID, OpenMenu> openMenus = new HashMap<>();

    public TeamMenu(BingoRush plugin, Game game, NamespacedKey tag) {
        this.plugin = plugin;
        this.game = game;
        this.messages = plugin.messages();
        this.tag = tag;
        this.actionKey = new NamespacedKey(plugin, "team-menu-action");
        this.teamKey = new NamespacedKey(plugin, "team-menu-team");
    }

    /** True when the stack is one of ours, so its view must stay read only. */
    public boolean isMenuItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    public void open(Player player) {
        if (game.round() != null) {
            messages.send(player, "round-already-running");
            return;
        }
        InventoryView view = (game.teams().all().size() <= COLUMNS ? MenuType.GENERIC_9X3 : MenuType.GENERIC_9X4)
                .builder()
                .title(messages.render("team-menu-title"))
                .build(player);
        view.open();
        openMenus.put(player.getUniqueId(), new OpenMenu(view.getTopInventory()));
        refreshAll();
    }

    /**
     * Redraws every open menu, called whenever membership or the team list
     * moves. Menus nobody has open anymore are dropped.
     */
    public void refreshAll() {
        Iterator<Map.Entry<UUID, OpenMenu>> iterator = openMenus.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, OpenMenu> entry = iterator.next();
            OpenMenu menu = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || player.getOpenInventory().getTopInventory() != menu.top()) {
                iterator.remove();
                continue;
            }
            render(menu.top(), player);
        }
    }

    /** Closes every open menu, for when a round starts. */
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

    /** Runs the action written in the clicked item; fillers do nothing. */
    public void click(Player player, ItemStack stack) {
        if (game.round() != null || stack == null || !stack.hasItemMeta()) {
            return;
        }
        String action = stack.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        if (action.equals("leave")) {
            leave(player);
            return;
        }
        if (action.equals("team")) {
            Integer teamId = stack.getItemMeta().getPersistentDataContainer().get(teamKey, PersistentDataType.INTEGER);
            Team team = teamId == null ? null : game.teams().byId(teamId);
            if (team != null) {
                join(player, team);
            }
        }
    }

    private void join(Player player, Team target) {
        Team current = game.teams().of(player.getUniqueId());
        if (current != null && current.id() == target.id()) {
            messages.send(player, "team-menu-already", Game.teamPlaceholder(target));
            game.playSound(player, BingoConfig.SoundId.MENU_CLICK);
            return;
        }
        if (target.size() >= game.teamSizeCap()) {
            messages.send(player, "team-full", Game.teamPlaceholder(target));
            game.playSound(player, BingoConfig.SoundId.MENU_CLICK);
            return;
        }
        // The old team only lets go once the target had room, so a failed
        // switch never leaves the player teamless.
        if (current != null) {
            current.remove(player.getUniqueId());
        }
        target.add(player.getUniqueId());
        messages.send(player, "team-joined", Game.teamPlaceholder(target));
        game.playSound(player, BingoConfig.SoundId.TEAM_CHIME);
        refreshAll();
    }

    private void leave(Player player) {
        Team current = game.teams().of(player.getUniqueId());
        if (current == null) {
            messages.send(player, "team-not-in");
            game.playSound(player, BingoConfig.SoundId.MENU_CLICK);
            return;
        }
        current.remove(player.getUniqueId());
        messages.send(player, "team-left");
        game.playSound(player, BingoConfig.SoundId.MENU_CLICK);
        refreshAll();
    }

    private void render(Inventory top, Player viewer) {
        int size = top.getSize();
        for (int slot = 0; slot < size; slot++) {
            top.setItem(slot, filler());
        }
        List<Team> teams = game.teams().all();
        // One centered row fits nine teams; beyond that a second row splits them evenly.
        int capacity = size == 36 ? 2 * COLUMNS : COLUMNS;
        if (teams.size() > capacity) {
            teams = teams.subList(0, capacity);
        }
        int firstRow = teams.size() <= COLUMNS ? teams.size() : (teams.size() + 1) / 2;
        placeRow(top, teams.subList(0, firstRow), 1, viewer);
        placeRow(top, teams.subList(firstRow, teams.size()), 2, viewer);
        top.setItem(size - 5, leaveItem());
    }

    private void placeRow(Inventory top, List<Team> rowTeams, int row, Player viewer) {
        int count = rowTeams.size();
        if (count == 0) {
            return;
        }
        int start = (COLUMNS - count) / 2;
        for (int i = 0; i < count; i++) {
            top.setItem(row * COLUMNS + start + i, teamItem(rowTeams.get(i), viewer));
        }
    }

    private ItemStack teamItem(Team team, Player viewer) {
        int cap = game.teamSizeCap();
        Team viewerTeam = game.teams().of(viewer.getUniqueId());
        boolean own = viewerTeam != null && viewerTeam.id() == team.id();
        boolean full = team.size() >= cap;

        ItemStack stack = woolFor(team.color()).createItemStack();
        stack.editMeta(meta -> {
            meta.displayName(messages.render("team-menu-item-name", Game.teamPlaceholder(team)));

            List<Component> lore = new ArrayList<>();
            lore.add(messages.render("menu-cell-separator"));
            lore.add(messages.render("team-menu-lore-count",
                    Placeholder.unparsed("count", String.valueOf(team.size())),
                    Placeholder.unparsed("max", String.valueOf(cap))));
            lore.add(messages.render("team-menu-lore-members"));

            List<String> names = new ArrayList<>();
            for (UUID memberId : team.members()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member != null) {
                    names.add(member.getName());
                }
            }
            int shown = Math.min(names.size(), MAX_MEMBER_LINES);
            for (int i = 0; i < shown; i++) {
                lore.add(messages.render("team-menu-lore-member",
                        Placeholder.unparsed("member", names.get(i))));
            }
            if (names.size() > shown) {
                lore.add(messages.render("team-menu-lore-more",
                        Placeholder.unparsed("more", String.valueOf(names.size() - shown))));
            }
            if (names.isEmpty()) {
                lore.add(messages.render("team-menu-lore-empty"));
            }

            lore.add(messages.render("menu-cell-separator"));
            if (own) {
                lore.add(messages.render("team-menu-lore-yours"));
            } else if (full) {
                lore.add(messages.render("team-menu-lore-full"));
            } else {
                lore.add(messages.render("team-menu-lore-join"));
            }
            meta.lore(lore);

            meta.addItemFlags(ItemFlag.values());
            meta.setEnchantmentGlintOverride(own);
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "team");
            meta.getPersistentDataContainer().set(teamKey, PersistentDataType.INTEGER, team.id());
        });
        return stack;
    }

    private ItemStack leaveItem() {
        ItemStack stack = ItemType.BARRIER.createItemStack();
        stack.editMeta(meta -> {
            meta.displayName(messages.render("team-menu-leave-name"));
            meta.lore(List.of(
                    messages.render("menu-cell-separator"),
                    messages.render("team-menu-leave-lore")));
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "leave");
        });
        return stack;
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

    private static ItemType woolFor(TextColor color) {
        if (color == null) {
            return ItemType.WHITE_WOOL;
        }
        int r = color.red();
        int g = color.green();
        int b = color.blue();

        record Wool(ItemType wool, int r, int g, int b) {}
        Wool[] wools = {
            new Wool(ItemType.WHITE_WOOL, 249, 255, 254),
            new Wool(ItemType.ORANGE_WOOL, 249, 128, 29),
            new Wool(ItemType.MAGENTA_WOOL, 199, 78, 189),
            new Wool(ItemType.LIGHT_BLUE_WOOL, 58, 179, 218),
            new Wool(ItemType.YELLOW_WOOL, 254, 216, 61),
            new Wool(ItemType.LIME_WOOL, 128, 199, 31),
            new Wool(ItemType.PINK_WOOL, 243, 140, 170),
            new Wool(ItemType.GRAY_WOOL, 71, 79, 82),
            new Wool(ItemType.LIGHT_GRAY_WOOL, 156, 157, 151),
            new Wool(ItemType.CYAN_WOOL, 22, 156, 156),
            new Wool(ItemType.PURPLE_WOOL, 137, 50, 184),
            new Wool(ItemType.BLUE_WOOL, 60, 68, 170),
            new Wool(ItemType.BROWN_WOOL, 131, 84, 50),
            new Wool(ItemType.GREEN_WOOL, 94, 124, 22),
            new Wool(ItemType.RED_WOOL, 176, 46, 38),
            new Wool(ItemType.BLACK_WOOL, 29, 29, 33)
        };

        ItemType best = ItemType.WHITE_WOOL;
        long bestDist = Long.MAX_VALUE;
        for (Wool wool : wools) {
            long dr = r - wool.r;
            long dg = g - wool.g;
            long db = b - wool.b;
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = wool.wool;
            }
        }
        return best;
    }
}
