package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.compat.ServerVersion;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.DisplayFormats;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

/**
 * The team in front of each name: player list, nametag and chat all read
 * the scoreboard team, so one prefix covers the three. One scoreboard team
 * per bingo team and moment (lobby, playing, watching). These versions have
 * no team color for the name itself, so the prefix ends with it.
 */
public final class TeamDisplay {

    private static final String PREFIX = "br_";
    private static final String SPECTATORS = PREFIX + "spec";
    private final BingoRush plugin;

    TeamDisplay(BingoRush plugin) {
        this.plugin = plugin;
    }

    private DisplayFormats formats() {
        BingoConfig config = plugin.bingoConfig();
        return config == null ? null : config.display();
    }

    public void applyAll(Collection<Team> teams) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player, teamOf(player, teams));
        }
    }

    public void apply(Player player, Team team) {
        for (Scoreboard board : boardsInUse()) {
            apply(board, player, team);
        }
    }

    /** Copies everyone's prefix onto a board the HUD just made. */
    public void registerOnScoreboard(Scoreboard board, Collection<Team> teams) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(board, player, teamOf(player, teams));
        }
    }

    private static Team teamOf(Player player, Collection<Team> teams) {
        for (Team team : teams) {
            if (team.hasMember(player.getUniqueId())) {
                return team;
            }
        }
        return null;
    }

    // The lobby sidebar gives each player a board of their own, and the tab
    // list only shows the prefixes on the board its viewer has.
    private static Set<Scoreboard> boardsInUse() {
        Set<Scoreboard> boards = new HashSet<>();
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player online : Bukkit.getOnlinePlayers()) {
            boards.add(online.getScoreboard());
        }
        return boards;
    }

    private DisplayFormats.State stateOf(Player player, Team team) {
        Round round = plugin.game() == null ? null : plugin.game().round();
        if (round == null) {
            return team == null ? null : DisplayFormats.State.LOBBY;
        }
        if (round.isEliminated(player.getUniqueId())
                || (team == null && player.getGameMode() == GameMode.SPECTATOR)) {
            return DisplayFormats.State.SPECTATING;
        }
        return team == null ? null : DisplayFormats.State.PLAYING;
    }

    private void apply(Scoreboard board, Player player, Team team) {
        removeFromBingoTeams(board, player.getName());
        DisplayFormats formats = formats();
        DisplayFormats.State state = stateOf(player, team);
        if (formats == null || !formats.enabled() || state == null) {
            return;
        }
        String key = team == null ? SPECTATORS : PREFIX + team.id() + "_" + state.name().charAt(0);
        org.bukkit.scoreboard.Team sbTeam = board.getTeam(key);
        if (sbTeam == null) {
            sbTeam = board.registerNewTeam(key);
        }
        TextColor nameColor = team == null || state == DisplayFormats.State.SPECTATING
                ? NamedTextColor.GRAY : team.color();
        TagResolver resolver = TagResolver.resolver(
                TagResolver.resolver("team_color", Tag.styling(team == null ? NamedTextColor.GRAY : team.color())),
                Placeholder.unparsed("tag", team == null ? "" : team.tag()),
                Placeholder.unparsed("team", team == null ? "" : team.name()));
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        String prefix = legacy.serialize(plugin.messages().renderTemplate(formats.prefix(state), resolver));
        String color = legacy.serialize(Component.text(" ", NamedTextColor.nearestTo(nameColor))).trim();
        sbTeam.setPrefix(fit(prefix, color));
        if (!sbTeam.hasEntry(player.getName())) {
            sbTeam.addEntry(player.getName());
        }
    }

    // Before 1.13 a prefix holds 16 characters, color codes included; the
    // name color at the end must survive the cut.
    private static String fit(String prefix, String nameColor) {
        int limit = (ServerVersion.atLeast(13) ? 64 : 16) - nameColor.length();
        String cut = prefix.length() > limit ? prefix.substring(0, limit) : prefix;
        if (cut.endsWith("§")) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + nameColor;
    }

    public void clear(Player player) {
        for (Scoreboard board : boardsInUse()) {
            removeFromBingoTeams(board, player.getName());
        }
    }

    public void clearAll() {
        for (Scoreboard board : boardsInUse()) {
            cleanScoreboard(board);
        }
    }

    public void forget(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            clear(player);
        }
    }

    private void cleanScoreboard(Scoreboard board) {
        Set<org.bukkit.scoreboard.Team> ours = new HashSet<>();
        for (org.bukkit.scoreboard.Team t : board.getTeams()) {
            if (t.getName().startsWith(PREFIX)) {
                ours.add(t);
            }
        }
        for (org.bukkit.scoreboard.Team t : ours) {
            t.unregister();
        }
    }

    private void removeFromBingoTeams(Scoreboard board, String entry) {
        for (org.bukkit.scoreboard.Team t : board.getTeams()) {
            if (t.getName().startsWith(PREFIX) && t.hasEntry(entry)) {
                t.removeEntry(entry);
            }
        }
    }
}
