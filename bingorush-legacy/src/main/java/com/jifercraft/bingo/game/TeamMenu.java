package com.jifercraft.bingo.game;

import com.cryptomorin.xseries.XMaterial;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.Messages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * The lobby team selector: one wool per team in its color, with the member
 * list in the lore, and a leave button. Every open menu redraws when
 * membership changes, so two players racing for the last slot see it fill.
 */
public final class TeamMenu {

    private static final int COLUMNS = 9;
    // The lore shows at most this many member names; the rest collapse into one line.
    private static final int MAX_MEMBER_LINES = 10;

    private final BingoRush plugin;
    private final Game game;
    private final Messages messages;
    private final Map<UUID, Inventory> openMenus = new HashMap<>();

    public TeamMenu(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
        this.messages = plugin.messages();
    }

    public void open(Player player) {
        if (game.round() != null) {
            // The picker is a lobby menu. During a round the same command is a
            // shortcut to /bingo join, which knows what a late joiner may do.
            game.joinTeam(player, null);
            return;
        }
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.TEAMS);
        int size = game.teams().all().size() <= COLUMNS ? 27 : 36;
        Inventory top = Bukkit.createInventory(holder, size, plugin.inventoryTitle(messages.render("team-menu-title")));
        holder.attach(top);
        player.openInventory(top);
        openMenus.put(player.getUniqueId(), top);
        refreshAll();
    }

    /** Redraws every open menu, called whenever membership or the team list moves. */
    public void refreshAll() {
        Iterator<Map.Entry<UUID, Inventory>> iterator = openMenus.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Inventory> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            render(entry.getValue(), player);
        }
    }

    /** Closes every open menu, for when a round starts. */
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

    /** Runs the action behind the clicked slot; fillers do nothing. */
    public void click(Player player, MenuHolder holder, int slot) {
        if (game.round() != null) {
            return;
        }
        String action = holder.actionAt(slot);
        if ("leave".equals(action)) {
            leave(player);
        } else if ("team".equals(action)) {
            Integer teamId = holder.teamAt(slot);
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
        game.rememberName(player);
        messages.send(player, "team-joined", Game.teamPlaceholder(target));
        game.playSound(player, BingoConfig.SoundId.TEAM_CHIME);
        game.display().apply(player, target);
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
        game.display().apply(player, null);
        refreshAll();
    }

    private void render(Inventory top, Player viewer) {
        MenuHolder holder = MenuHolder.of(top, MenuHolder.Kind.TEAMS);
        if (holder == null) {
            return;
        }
        holder.clearActions();
        int size = top.getSize();
        for (int slot = 0; slot < size; slot++) {
            top.setItem(slot, LegacyItems.item(XMaterial.GRAY_STAINED_GLASS_PANE, " ", null, false));
        }
        List<Team> teams = game.teams().all();
        // One centered row fits nine teams; beyond that a second row splits them evenly.
        int capacity = size == 36 ? 2 * COLUMNS : COLUMNS;
        if (teams.size() > capacity) {
            teams = teams.subList(0, capacity);
        }
        int firstRow = teams.size() <= COLUMNS ? teams.size() : (teams.size() + 1) / 2;
        placeRow(top, holder, teams.subList(0, firstRow), 1, viewer);
        placeRow(top, holder, teams.subList(firstRow, teams.size()), 2, viewer);
        int leaveSlot = size - 5;
        top.setItem(leaveSlot, LegacyItems.item(XMaterial.BARRIER,
                messages.legacy(messages.render("team-menu-leave-name")),
                messages.legacy(Arrays.asList(messages.render("menu-cell-separator"), messages.render("team-menu-leave-lore"))),
                false));
        holder.action(leaveSlot, "leave");
    }

    private void placeRow(Inventory top, MenuHolder holder, List<Team> rowTeams, int row, Player viewer) {
        int count = rowTeams.size();
        int start = (COLUMNS - count) / 2;
        for (int i = 0; i < count; i++) {
            int slot = row * COLUMNS + start + i;
            Team team = rowTeams.get(i);
            top.setItem(slot, teamItem(team, viewer));
            holder.team(slot, team.id());
        }
    }

    private org.bukkit.inventory.ItemStack teamItem(Team team, Player viewer) {
        int cap = game.teamSizeCap();
        Team viewerTeam = game.teams().of(viewer.getUniqueId());
        boolean own = viewerTeam != null && viewerTeam.id() == team.id();
        boolean full = team.size() >= cap;
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
            lore.add(messages.render("team-menu-lore-member", Placeholder.unparsed("member", names.get(i))));
        }
        if (names.size() > shown) {
            lore.add(messages.render("team-menu-lore-more", Placeholder.unparsed("more", String.valueOf(names.size() - shown))));
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
        return LegacyItems.item(LegacyItems.woolFor(team.color()),
                messages.legacy(messages.render("team-menu-item-name", Game.teamPlaceholder(team))),
                messages.legacy(lore), own);
    }
}
