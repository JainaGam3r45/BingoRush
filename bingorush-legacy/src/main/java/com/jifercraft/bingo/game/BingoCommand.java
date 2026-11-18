package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.history.MatchSummary;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * /bingo and its shortcuts (/card, /teams, /setup), the classic way: the
 * versions this jar runs on have no Brigadier API. Same subcommands and
 * permissions as the Modern jar.
 */
public final class BingoCommand implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_HELP = Arrays.asList("join", "teams", "card", "leave", "spectate", "coords", "lobby", "randomjoin", "version");
    private static final List<String> ADMIN_HELP = Arrays.asList(
            "card-spectator", "start", "setup", "stop", "pause", "resume", "reroll", "teams-admin",
            "setlobby", "history", "export", "reload");
    private static final List<String> PLAYER_SUBCOMMANDS = Arrays.asList(
            "help", "version", "join", "leave", "spectate", "coords", "c", "card", "teams", "lobby", "randomjoin", "autojoin");
    private static final List<String> ADMIN_SUBCOMMANDS = Arrays.asList(
            "start", "setup", "stop", "pause", "resume", "reroll", "reload", "setlobby", "history", "export");

    private final BingoRush plugin;
    private final Game game;

    public BingoCommand(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    private Messages messages() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "card":
                return asPlayer(sender, "bingo.play", game::openCard);
            case "teams":
                return asPlayer(sender, "bingo.play", game::openTeamMenu);
            case "setup":
                return asPlayer(sender, "bingo.admin", game::openSetupMenu);
            default:
                bingo(sender, args);
                return true;
        }
    }

    private void bingo(CommandSender sender, String[] args) {
        if (args.length == 0) {
            help(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("bingo.admin");
        if (ADMIN_SUBCOMMANDS.contains(sub) && !admin) {
            messages().send(sender, "no-permission");
            return;
        }
        if (PLAYER_SUBCOMMANDS.contains(sub) && !sender.hasPermission("bingo.play") && !admin) {
            messages().send(sender, "no-permission");
            return;
        }
        switch (sub) {
            case "help":
            case "ayuda":
                help(sender);
                break;
            case "version":
                version(sender, admin);
                break;
            case "setlobby":
                asPlayer(sender, "bingo.admin", game::setLobby);
                break;
            case "lobby":
                asPlayer(sender, "bingo.play", game::teleportToLobby);
                break;
            case "randomjoin":
            case "autojoin":
                asPlayer(sender, "bingo.play", game::randomJoin);
                break;
            case "join":
                asPlayer(sender, null, player -> game.joinTeam(player, args.length > 1 ? args[1] : null));
                break;
            case "leave":
                asPlayer(sender, null, game::leaveTeam);
                break;
            case "spectate":
                asPlayer(sender, null, game::spectate);
                break;
            case "coords":
            case "c":
                asPlayer(sender, null, game::shareCoordinates);
                break;
            case "card":
                if (args.length > 1 && admin) {
                    asPlayer(sender, null, player -> game.openCardSpectator(player, args[1]));
                } else {
                    asPlayer(sender, null, game::openCard);
                }
                break;
            case "start":
                start(sender, args);
                break;
            case "setup":
                asPlayer(sender, null, game::openSetupMenu);
                break;
            case "stop":
                game.stopRound(sender);
                break;
            case "pause":
                game.pauseRound(sender);
                break;
            case "resume":
                game.resumeRound(sender);
                break;
            case "reroll":
                game.rerollCard(sender);
                break;
            case "reload":
                plugin.reloadSettings();
                messages().send(sender, "reloaded");
                break;
            case "teams":
                teams(sender, args, admin);
                break;
            case "history":
                if (args.length == 1) {
                    game.showHistory(sender, 1);
                } else if (args[1].equalsIgnoreCase("clear")) {
                    plugin.matchHistory().clearHistory();
                    messages().send(sender, "history.cleared");
                } else {
                    game.showHistoryOrDetails(sender, args[1]);
                }
                break;
            case "export":
                if (args.length > 1) {
                    game.exportMatch(sender, args[1]);
                } else {
                    messages().send(sender, "help.export");
                }
                break;
            default:
                help(sender);
        }
    }

    private void teams(CommandSender sender, String[] args, boolean admin) {
        if (args.length == 1) {
            // Players get the menu; the console keeps the chat list.
            if (sender instanceof Player) {
                game.openTeamMenu((Player) sender);
            } else {
                game.listTeams(sender);
            }
            return;
        }
        if (!admin) {
            messages().send(sender, "no-permission");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add":
                TextColor color = args.length > 3 ? TextColor.fromHexString(args[3]) : null;
                if (args.length < 4 || color == null) {
                    messages().send(sender, "help.teams-admin");
                    return;
                }
                game.createTeam(sender, args[2], color);
                break;
            case "remove":
                if (args.length < 3) {
                    messages().send(sender, "help.teams-admin");
                    return;
                }
                game.removeTeam(sender, args[2]);
                break;
            case "join":
                Player target = args.length > 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (target == null) {
                    messages().send(sender, "help.teams-admin");
                    return;
                }
                game.assignPlayer(sender, target, args[3]);
                break;
            case "clear":
                game.clearTeams(sender);
                break;
            default:
                messages().send(sender, "help.teams-admin");
        }
    }

    /** /bingo start [items|advancements] [lockout:true|false] [seed...] */
    private void start(CommandSender sender, String[] args) {
        BingoGameMode mode = null;
        int next = 1;
        if (args.length > 1) {
            BingoGameMode parsed = BingoGameMode.parse(args[1]);
            if (parsed != null) {
                mode = parsed;
                next = 2;
            }
        }
        Boolean lockout = null;
        if (args.length > next) {
            String first = args[next].toLowerCase(Locale.ROOT);
            String value = first.startsWith("lockout:") ? first.substring("lockout:".length()) : first;
            if ("true".equals(value) || "yes".equals(value)) {
                lockout = Boolean.TRUE;
                next++;
            } else if ("false".equals(value) || "no".equals(value)) {
                lockout = Boolean.FALSE;
                next++;
            }
        }
        String seed = args.length > next ? String.join(" ", Arrays.copyOfRange(args, next, args.length)) : null;
        game.startRound(sender, mode, lockout, seed);
    }

    private void version(CommandSender sender, boolean admin) {
        String current = plugin.updateChecker() != null
                ? plugin.updateChecker().currentVersion().clean()
                : plugin.getDescription().getVersion();
        messages().send(sender, "version-info", Placeholder.unparsed("current", current));
        if (admin && plugin.updateChecker() != null) {
            plugin.updateChecker().check(sender);
        }
    }

    private void help(CommandSender sender) {
        messages().send(sender, "help.header");
        for (String key : PLAYER_HELP) {
            messages().send(sender, "help." + key);
        }
        if (sender.hasPermission("bingo.admin")) {
            for (String key : ADMIN_HELP) {
                messages().send(sender, "help." + key);
            }
        }
    }

    private interface PlayerAction {
        void run(Player player);
    }

    /** Commands that only work for a player, with a clear line for the console. */
    private boolean asPlayer(CommandSender sender, String permission, PlayerAction action) {
        if (!(sender instanceof Player)) {
            messages().send(sender, "players-only");
            return true;
        }
        if (permission != null && !sender.hasPermission(permission)) {
            messages().send(sender, "no-permission");
            return true;
        }
        action.run((Player) sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("bingo")) {
            return Collections.emptyList();
        }
        boolean admin = sender.hasPermission("bingo.admin");
        if (args.length == 1) {
            List<String> options = new ArrayList<>(PLAYER_SUBCOMMANDS);
            if (admin) {
                options.addAll(ADMIN_SUBCOMMANDS);
            }
            return matching(options, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "join":
                    return matching(teamNames(), args[1]);
                case "card":
                    return admin ? matching(teamNames(), args[1]) : Collections.<String>emptyList();
                case "start":
                    return admin ? matching(Arrays.asList("items", "advancements", "lockout:true", "lockout:false"), args[1])
                            : Collections.<String>emptyList();
                case "teams":
                    return admin ? matching(Arrays.asList("add", "remove", "join", "clear"), args[1])
                            : Collections.<String>emptyList();
                case "history":
                case "export":
                    if (!admin) {
                        return Collections.emptyList();
                    }
                    List<String> ids = new ArrayList<>();
                    if (sub.equals("history")) {
                        ids.add("clear");
                    }
                    for (MatchSummary summary : plugin.matchHistory().summaries()) {
                        ids.add(summary.id());
                    }
                    return matching(ids, args[1]);
                default:
                    return Collections.emptyList();
            }
        }
        if (admin && sub.equals("start") && args.length == 3) {
            return matching(Arrays.asList("lockout:true", "lockout:false"), args[2]);
        }
        if (admin && sub.equals("teams") && args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            return matching(teamNames(), args[2]);
        }
        if (admin && sub.equals("teams") && args.length == 3 && args[1].equalsIgnoreCase("join")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return matching(names, args[2]);
        }
        if (admin && sub.equals("teams") && args.length == 4 && args[1].equalsIgnoreCase("join")) {
            return matching(teamNames(), args[3]);
        }
        return Collections.emptyList();
    }

    private List<String> teamNames() {
        List<String> names = new ArrayList<>();
        for (Team team : game.teams().all()) {
            names.add(team.name());
        }
        return names;
    }

    private static List<String> matching(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
