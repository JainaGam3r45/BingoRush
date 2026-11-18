package com.jifercraft.bingo.compat;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.game.Game;
import com.jifercraft.bingo.game.Round;
import com.jifercraft.bingo.game.Team;
import com.jifercraft.bingo.game.TeamLabel;
import java.util.Locale;
import java.util.UUID;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** PlaceholderAPI expansion for BingoRush. */
public final class BingoRushExpansion extends PlaceholderExpansion {

    private final BingoRush plugin;
    private final Game game;

    public BingoRushExpansion(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    @Override
    public String getIdentifier() {
        return "bingorush";
    }

    @Override
    public String getAuthor() {
        return "JainaGam3r45";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (params == null) {
            return "";
        }
        String p = params.toLowerCase(Locale.ROOT);
        Player player = offlinePlayer != null ? offlinePlayer.getPlayer() : null;
        UUID uuid = offlinePlayer != null ? offlinePlayer.getUniqueId() : null;

        if (uuid != null) {
            Team team = game.teams().of(uuid);
            if (p.equals("in_game")) {
                return (game.round() != null && team != null) ? "yes" : "no";
            }
            if (p.equals("has_team")) {
                return team != null ? "yes" : "no";
            }
            if (p.equals("team_name") || p.equals("team_raw")) {
                return team != null ? team.name() : "";
            }
            if (p.equals("team")) {
                if (team == null) {
                    return "";
                }
                return LegacyComponentSerializer.legacySection().serialize(
                        Component.text(team.name(), team.color()));
            }
            if (p.equals("team_color")) {
                return team != null ? team.color().asHexString() : "";
            }
            if (p.equals("team_color_legacy")) {
                if (team == null) {
                    return "";
                }
                return LegacyComponentSerializer.legacySection().serialize(
                        Component.text("", team.color()));
            }
            if (p.equals("team_initial")) {
                return team != null ? TeamLabel.initial(team.name()) : "";
            }
            if (p.equals("team_prefix")) {
                if (team == null) {
                    return "";
                }
                return "[" + LegacyComponentSerializer.legacySection().serialize(
                        Component.text(team.name(), team.color())) + "§r]";
            }
            if (p.equals("team_score") || p.equals("team_claims")) {
                if (team != null && game.round() != null) {
                    return String.valueOf(game.round().board().count(team.id()));
                }
                return "0";
            }
            if (p.equals("team_members") || p.equals("team_members_count")) {
                return team != null ? String.valueOf(team.size()) : "0";
            }
            if (p.equals("lives")) {
                if (game.round() != null) {
                    int left = game.round().livesLeft(uuid);
                    return left < 0 ? "∞" : String.valueOf(left);
                }
                return "0";
            }
            if (p.equals("is_spectator")) {
                return (player != null && player.getGameMode() == GameMode.SPECTATOR) ? "true" : "false";
            }
        }

        if (p.equals("status") || p.equals("game_state")) {
            Round round = game.round();
            if (round == null) {
                return "WAITING";
            }
            return round.state().name();
        }
        if (p.equals("players_count")) {
            return String.valueOf(game.teams().totalMembers());
        }
        if (p.equals("max_players")) {
            return String.valueOf(game.teams().all().size() * game.teamSizeCap());
        }
        if (p.equals("teams_count")) {
            return String.valueOf(game.teams().all().size());
        }
        if (p.equals("time_left")) {
            return game.formattedTimeLeft();
        }
        if (p.equals("time_left_seconds")) {
            return String.valueOf(game.timeLeftSeconds());
        }
        if (p.equals("game_mode")) {
            return game.round() != null ? game.round().mode().name().toLowerCase(Locale.ROOT) : "items";
        }
        if (p.equals("win_mode")) {
            return plugin.bingoConfig() != null ? plugin.bingoConfig().winMode().name().toLowerCase(Locale.ROOT) : "full";
        }
        if (p.equals("lockout")) {
            return (plugin.bingoConfig() != null && plugin.bingoConfig().lockout()) ? "true" : "false";
        }
        if (p.equals("leading_team")) {
            Team leader = game.leadingTeam();
            return leader != null ? leader.name() : "";
        }
        if (p.equals("leading_score")) {
            return String.valueOf(game.leadingScore());
        }

        if (p.equals("season_active")) {
            return (plugin.season() != null && plugin.season().currentPhase().isVisualsActive()) ? "true" : "false";
        }
        if (p.equals("season_phase")) {
            return plugin.season() != null ? plugin.season().currentPhase().name() : "INACTIVE";
        }
        if (p.equals("season_time_left")) {
            if (plugin.season() == null || !plugin.season().settings().enabled()) {
                return "00h 00m 00s";
            }
            long now = plugin.season().clock().currentTimeMillis();
            long remaining = plugin.season().settings().windowAt(now).remainingMillisInPhase(now);
            long remSeconds = Math.max(0, remaining / 1000L);
            return String.format(Locale.ROOT, "%02dh %02dm %02ds",
                    remSeconds / 3600, (remSeconds % 3600) / 60, remSeconds % 60);
        }
        if (p.equals("season_gifts_total")) {
            return plugin.season() != null ? String.valueOf(plugin.season().activeGifts().size()) : "0";
        }
        if (p.equals("season_gifts_found")) {
            if (uuid != null && plugin.season() != null) {
                return String.valueOf(plugin.season().claims().claimedCount(uuid));
            }
            return "0";
        }

        return null;
    }
}
