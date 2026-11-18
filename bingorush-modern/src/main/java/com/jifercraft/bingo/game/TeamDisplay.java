package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Team colors in the player list and nametags managed through scoreboard teams,
 * leaving player list names, headers and chat formatting untouched.
 */
public final class TeamDisplay {

    private static final String PREFIX = "br_";
    private final BingoRush plugin;

    TeamDisplay(BingoRush plugin) {
        this.plugin = plugin;
    }

    public void applyAll(Collection<Team> teams) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Team team = null;
            for (Team t : teams) {
                if (t.hasMember(player.getUniqueId())) {
                    team = t;
                    break;
                }
            }
            apply(player, team);
        }
    }

    public void apply(Player player, Team team) {
        if (player == null) {
            return;
        }
        updatePlayerScoreboard(player.getScoreboard(), player, team);
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        if (mainBoard != player.getScoreboard()) {
            updatePlayerScoreboard(mainBoard, player, team);
        }
    }

    /**
     * Registers all active bingo teams on the given scoreboard and adds their members.
     * Called by Hud when constructing the round scoreboard so players see team colors.
     */
    public void registerOnScoreboard(Scoreboard board, Collection<Team> teams) {
        if (board == null || teams == null) {
            return;
        }
        for (Team team : teams) {
            String teamKey = teamKey(team);
            org.bukkit.scoreboard.Team sbTeam = board.getTeam(teamKey);
            if (sbTeam == null) {
                sbTeam = board.registerNewTeam(teamKey);
            }
            NamedTextColor color = NamedTextColor.nearestTo(team.color());
            if (color != null) {
                sbTeam.color(color);
            }
            for (UUID memberId : team.members()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member != null && !sbTeam.hasEntry(member.getName())) {
                    sbTeam.addEntry(member.getName());
                }
            }
        }
    }

    private void updatePlayerScoreboard(Scoreboard board, Player player, Team team) {
        if (board == null || player == null) {
            return;
        }
        removeFromBingoTeams(board, player.getName());
        if (team == null) {
            return;
        }
        String teamKey = teamKey(team);
        org.bukkit.scoreboard.Team sbTeam = board.getTeam(teamKey);
        if (sbTeam == null) {
            sbTeam = board.registerNewTeam(teamKey);
        }
        NamedTextColor color = NamedTextColor.nearestTo(team.color());
        if (color != null) {
            sbTeam.color(color);
        }
        if (!sbTeam.hasEntry(player.getName())) {
            sbTeam.addEntry(player.getName());
        }
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        removeFromBingoTeams(player.getScoreboard(), player.getName());
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        if (mainBoard != player.getScoreboard()) {
            removeFromBingoTeams(mainBoard, player.getName());
        }
    }

    public void clearAll() {
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        cleanScoreboard(mainBoard);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getScoreboard() != mainBoard) {
                cleanScoreboard(player.getScoreboard());
            }
        }
    }

    public void forget(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            clear(player);
        }
    }

    private void cleanScoreboard(Scoreboard board) {
        if (board == null) {
            return;
        }
        Set<org.bukkit.scoreboard.Team> toRemove = new HashSet<>();
        for (org.bukkit.scoreboard.Team t : board.getTeams()) {
            if (t.getName().startsWith(PREFIX)) {
                toRemove.add(t);
            }
        }
        for (org.bukkit.scoreboard.Team t : toRemove) {
            t.unregister();
        }
    }

    private void removeFromBingoTeams(Scoreboard board, String entry) {
        if (board == null || entry == null) {
            return;
        }
        for (org.bukkit.scoreboard.Team t : board.getTeams()) {
            if (t.getName().startsWith(PREFIX) && t.hasEntry(entry)) {
                t.removeEntry(entry);
            }
        }
    }

    private static String teamKey(Team team) {
        String name = team.name().toLowerCase(Locale.ROOT);
        if (name.length() > 10) {
            name = name.substring(0, 10);
        }
        return PREFIX + team.id() + "_" + name;
    }
}
