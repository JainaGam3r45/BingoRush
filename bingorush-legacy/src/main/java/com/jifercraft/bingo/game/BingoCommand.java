package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.arena.ArenaManager;
import com.jifercraft.bingo.arena.ArenaMenu;
import com.jifercraft.bingo.arena.ArenaOption;
import com.jifercraft.bingo.arena.InvalidArenaValue;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.history.MatchSummary;
import com.jifercraft.bingo.season.Season;
import com.jifercraft.bingo.season.SeasonPhase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * /bingo and its shortcuts (/card, /teams), the classic way: the versions
 * this jar runs on have no Brigadier API. Same subcommands and permissions as
 * the Modern jar, minus the ones that need a world per arena. The help list
 * and the permission of each subcommand come from the tables below.
 */
public final class BingoCommand implements CommandExecutor, TabCompleter {

    public static final String PLAY = "bingo.play";
    public static final String ROUND = "bingo.admin.round";
    public static final String ARENA = ArenaMenu.PERMISSION;
    public static final String TEAMS = "bingo.admin.teams";
    public static final String HISTORY = "bingo.admin.history";
    public static final String RELOAD = "bingo.admin.reload";
    public static final String SEASON = "bingo.admin.season";

    private static final Map<String, String> SUBCOMMANDS = new LinkedHashMap<>();
    private static final Map<String, String> HELP = new LinkedHashMap<>();
    // Kept in arenas.yml so both jars share the file, but only the Modern jar
    // does anything with them.
    private static final Set<ArenaOption> MODERN_ONLY =
            EnumSet.of(ArenaOption.BORDER, ArenaOption.PVP, ArenaOption.KEEP_INVENTORY, ArenaOption.DAYLIGHT);
    private static final List<String> ARENA_ACTIONS = Arrays.asList(
            "list", "create", "edit", "set", "preset", "savepreset", "delete", "select", "setwaiting");

    static {
        for (String sub : Arrays.asList("join", "randomjoin", "autojoin", "leave", "spectate", "coords", "c",
                "card", "teams", "lobby")) {
            SUBCOMMANDS.put(sub, PLAY);
        }
        for (String sub : Arrays.asList("help", "ayuda", "version")) {
            SUBCOMMANDS.put(sub, null);
        }
        for (String sub : Arrays.asList("start", "stop", "pause", "resume", "reroll")) {
            SUBCOMMANDS.put(sub, ROUND);
        }
        SUBCOMMANDS.put("setlobby", ARENA);
        SUBCOMMANDS.put("arena", ARENA);
        SUBCOMMANDS.put("history", HISTORY);
        SUBCOMMANDS.put("export", HISTORY);
        SUBCOMMANDS.put("reload", RELOAD);
        SUBCOMMANDS.put("season", SEASON);

        for (String key : Arrays.asList("join", "randomjoin", "teams", "card", "leave", "spectate", "coords", "lobby")) {
            HELP.put(key, PLAY);
        }
        HELP.put("version", null);
        for (String key : Arrays.asList("start", "stop", "pause", "resume", "reroll", "card-spectator")) {
            HELP.put(key, ROUND);
        }
        for (String key : Arrays.asList("arena", "arena-create", "arena-set", "arena-preset", "arena-waiting", "setlobby")) {
            HELP.put(key, ARENA);
        }
        HELP.put("teams-admin", TEAMS);
        HELP.put("history", HISTORY);
        HELP.put("export", HISTORY);
        HELP.put("reload", RELOAD);
        HELP.put("season", SEASON);
    }

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
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("card")) {
            return asPlayer(sender, PLAY, game::openCard);
        }
        if (name.equals("teams")) {
            return asPlayer(sender, PLAY, game::openTeamMenu);
        }
        bingo(sender, args);
        return true;
    }

    private static boolean allowed(CommandSender sender, String permission) {
        return permission == null || sender.hasPermission(permission);
    }

    private void bingo(CommandSender sender, String[] args) {
        if (args.length == 0) {
            help(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.containsKey(sub)) {
            help(sender);
            return;
        }
        if (!allowed(sender, SUBCOMMANDS.get(sub))) {
            messages().send(sender, "no-permission");
            return;
        }
        switch (sub) {
            case "help":
            case "ayuda":
                help(sender);
                break;
            case "version":
                version(sender);
                break;
            case "arena":
                arena(sender, args);
                break;
            case "setlobby":
                asPlayer(sender, ARENA, game::setLobby);
                break;
            case "lobby":
                asPlayer(sender, PLAY, game::teleportToLobby);
                break;
            case "randomjoin":
            case "autojoin":
                asPlayer(sender, PLAY, game::randomJoin);
                break;
            case "join":
                asPlayer(sender, PLAY, player -> game.joinTeam(player, args.length > 1 ? args[1] : null));
                break;
            case "leave":
                asPlayer(sender, PLAY, game::leaveTeam);
                break;
            case "spectate":
                asPlayer(sender, PLAY, game::spectate);
                break;
            case "coords":
            case "c":
                asPlayer(sender, PLAY, game::shareCoordinates);
                break;
            case "card":
                if (args.length > 1 && sender.hasPermission(ROUND)) {
                    asPlayer(sender, ROUND, player -> game.openCardSpectator(player, args[1]));
                } else {
                    asPlayer(sender, PLAY, game::openCard);
                }
                break;
            case "start":
                start(sender, args);
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
                teams(sender, args);
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
                    usage(sender, "export");
                }
                break;
            case "season":
                season(sender, args);
                break;
            default:
                help(sender);
        }
    }

    // A subcommand typed without its arguments: say what is missing and show
    // its help line.
    private void usage(CommandSender sender, String helpKey) {
        messages().send(sender, "usage-hint");
        messages().send(sender, "help." + helpKey);
    }

    private void arena(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (sender instanceof Player) {
                plugin.arenaMenu().openArenaList((Player) sender);
            } else {
                listArenas(sender);
            }
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            listArenas(sender);
            return;
        }
        if (!ARENA_ACTIONS.contains(action)) {
            usage(sender, "arena");
            return;
        }
        if (args.length < 3) {
            usage(sender, helpFor(action));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        ArenaManager arenas = plugin.arenaManager();
        switch (action) {
            case "create":
                String preset = args.length > 3 ? args[3] : null;
                ArenaManager.Result created = arenas.create(id, preset);
                if (!created.ok()) {
                    plugin.arenaMenu().sendProblem(sender, created.problem(),
                            created.problem() == ArenaManager.Problem.UNKNOWN_PRESET ? preset : id);
                    return;
                }
                messages().send(sender, "arena.created", Placeholder.unparsed("arena", id));
                if (sender instanceof Player) {
                    plugin.arenaMenu().openArenaEdit((Player) sender, id);
                }
                break;
            case "edit":
                asPlayer(sender, ARENA, player -> plugin.arenaMenu().openArenaEdit(player, id));
                break;
            case "set":
                if (args.length < 5) {
                    usage(sender, "arena-set");
                    return;
                }
                setOption(sender, id, args[3], String.join(" ", Arrays.copyOfRange(args, 4, args.length)));
                break;
            case "preset":
                if (args.length < 4) {
                    usage(sender, "arena-preset");
                    return;
                }
                ArenaManager.Result applied = arenas.applyPreset(id, args[3]);
                if (!applied.ok()) {
                    plugin.arenaMenu().sendProblem(sender, applied.problem(),
                            applied.problem() == ArenaManager.Problem.UNKNOWN_PRESET ? args[3] : id);
                    return;
                }
                messages().send(sender, "arena.preset-applied", Placeholder.unparsed("arena", id),
                        Placeholder.unparsed("preset", args[3].toLowerCase(Locale.ROOT)));
                break;
            case "savepreset":
                if (args.length < 4) {
                    usage(sender, "arena-preset");
                    return;
                }
                GameMapDefinition source = arenas.getArena(id);
                if (source == null) {
                    plugin.arenaMenu().sendProblem(sender, ArenaManager.Problem.NOT_FOUND, id);
                } else if (!arenas.savePreset(args[3], source)) {
                    messages().send(sender, "arena.invalid-preset-name", Placeholder.unparsed("value", args[3]));
                } else {
                    messages().send(sender, "arena.preset-saved", Placeholder.unparsed("arena", id),
                            Placeholder.unparsed("preset", ArenaOption.toId(args[3])));
                }
                break;
            case "delete":
                if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
                    messages().send(sender, "arena.delete-confirm", Placeholder.unparsed("arena", id));
                    return;
                }
                ArenaManager.Result deleted = arenas.delete(id);
                if (!deleted.ok()) {
                    plugin.arenaMenu().sendProblem(sender, deleted.problem(), id);
                    return;
                }
                messages().send(sender, "arena.deleted", Placeholder.unparsed("arena", id));
                break;
            case "select":
                if (arenas.getArena(id) == null) {
                    plugin.arenaMenu().sendProblem(sender, ArenaManager.Problem.NOT_FOUND, id);
                } else if (!game.selectMap(id)) {
                    messages().send(sender, "arena.select-busy");
                } else {
                    messages().send(sender, "arena.selected", Placeholder.unparsed("arena", id));
                }
                break;
            case "setwaiting":
                boolean clear = args.length > 3 && args[3].equalsIgnoreCase("clear");
                if (clear) {
                    setWaiting(sender, id, null);
                } else {
                    asPlayer(sender, ARENA, player -> setWaiting(player, id, player.getLocation()));
                }
                break;
            default:
                usage(sender, "arena");
        }
    }

    private static String helpFor(String action) {
        switch (action) {
            case "create":
                return "arena-create";
            case "set":
                return "arena-set";
            case "preset":
            case "savepreset":
                return "arena-preset";
            case "setwaiting":
                return "arena-waiting";
            default:
                return "arena";
        }
    }

    private void listArenas(CommandSender sender) {
        List<GameMapDefinition> arenas = plugin.arenaManager().getAllArenas();
        if (arenas.isEmpty()) {
            messages().send(sender, "arena.list-empty");
            return;
        }
        messages().send(sender, "arena.list-header", Placeholder.unparsed("count", String.valueOf(arenas.size())));
        for (GameMapDefinition arena : arenas) {
            messages().send(sender, "arena.list-entry",
                    Placeholder.unparsed("arena", arena.id()),
                    Placeholder.unparsed("name", arena.name()),
                    Placeholder.unparsed("mode", ArenaOption.MODE.format(arena.mode())),
                    Placeholder.unparsed("team_size", String.valueOf(arena.teamSize())),
                    Placeholder.unparsed("max_teams", String.valueOf(arena.maxTeams())),
                    Placeholder.unparsed("selected", game.isMapSelected(arena.id()) ? "*" : ""));
        }
    }

    private void setOption(CommandSender sender, String id, String optionKey, String raw) {
        ArenaOption option = ArenaOption.byKey(optionKey);
        if (option == null) {
            messages().send(sender, "arena.unknown-option", Placeholder.unparsed("value", optionKey),
                    Placeholder.unparsed("options", String.join(", ", settingKeys())));
            return;
        }
        if (MODERN_ONLY.contains(option)) {
            messages().send(sender, "arena.modern-only", Placeholder.unparsed("option", option.key()));
            return;
        }
        Object value;
        try {
            value = option.parse(raw);
        } catch (InvalidArenaValue e) {
            plugin.arenaMenu().sendInvalid(sender, e);
            return;
        }
        ArenaManager.Result result = plugin.arenaManager().set(id, option, value);
        if (!result.ok()) {
            plugin.arenaMenu().sendProblem(sender, result.problem(), id);
            return;
        }
        messages().send(sender, "arena.updated", Placeholder.unparsed("arena", id),
                Placeholder.component("option", messages().render("arena-option-name." + option.key())),
                Placeholder.component("value", plugin.arenaMenu().valueComponent(option, value)));
    }

    private static List<String> settingKeys() {
        List<String> keys = new ArrayList<>();
        for (ArenaOption option : ArenaOption.values()) {
            if (!MODERN_ONLY.contains(option)) {
                keys.add(option.key());
            }
        }
        return keys;
    }

    private void setWaiting(CommandSender sender, String id, Location location) {
        ArenaManager.Result result = plugin.arenaManager().setWaitingLobby(id, location);
        if (!result.ok()) {
            plugin.arenaMenu().sendProblem(sender, result.problem(), id);
            return;
        }
        if (location == null) {
            messages().send(sender, "arena-menu.waiting-cleared");
            return;
        }
        messages().send(sender, "waiting-lobby-set",
                Placeholder.unparsed("map", id),
                Placeholder.unparsed("world", location.getWorld().getName()),
                Placeholder.unparsed("x", String.format(Locale.ROOT, "%.1f", location.getX())),
                Placeholder.unparsed("y", String.format(Locale.ROOT, "%.1f", location.getY())),
                Placeholder.unparsed("z", String.format(Locale.ROOT, "%.1f", location.getZ())));
    }

    private void season(CommandSender sender, String[] args) {
        Season season = plugin.season();
        if (season == null || !season.settings().enabled()) {
            messages().send(sender, "season.disabled");
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "status":
                SeasonPhase phase = season.currentPhase();
                long now = season.clock().currentTimeMillis();
                long remaining = season.settings().windowAt(now).remainingMillisInPhase(now);
                long seconds = Math.max(0, remaining / 1000L);
                messages().send(sender, "season.status",
                        Placeholder.unparsed("phase", phase.name()),
                        Placeholder.unparsed("remaining", String.format(Locale.ROOT, "%02dh %02dm %02ds",
                                seconds / 3600, (seconds % 3600) / 60, seconds % 60)));
                break;
            case "phase":
                if (args.length < 3) {
                    usage(sender, "season");
                    return;
                }
                SeasonPhase target;
                try {
                    target = SeasonPhase.valueOf(args[2].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    messages().send(sender, "season.unknown-phase", Placeholder.unparsed("value", args[2]));
                    return;
                }
                season.simulatePhase(target);
                messages().send(sender, "season.simulated", Placeholder.unparsed("phase", target.name()));
                break;
            case "reset":
                season.resetClock();
                season(sender, new String[]{"season", "status"});
                break;
            case "restore":
                int count = season.restoreBlocks();
                messages().send(sender, count > 0 ? "season.restored" : "season.no-blocks",
                        Placeholder.unparsed("count", String.valueOf(count)));
                break;
            case "gift":
                gift(sender, season, args);
                break;
            case "preview":
                if (args.length < 3 || !(sender instanceof Player)) {
                    usage(sender, "season");
                } else if (args[2].equalsIgnoreCase("snowfall")) {
                    season.onCountdownSnow(((Player) sender).getLocation());
                } else if (args[2].equalsIgnoreCase("claim")) {
                    season.onClaim((Player) sender);
                }
                break;
            default:
                usage(sender, "season");
        }
    }

    private void gift(CommandSender sender, Season season, String[] args) {
        String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "spawn":
                Location lobby = game.lobbyLocation();
                if (lobby == null) {
                    messages().send(sender, "lobby-not-set");
                    return;
                }
                messages().send(sender, "season.gift-spawned",
                        Placeholder.unparsed("count", String.valueOf(season.spawnGifts(lobby))));
                break;
            case "clear":
                season.clearGifts();
                messages().send(sender, "season.gift-cleared");
                break;
            case "add":
                if (args.length < 4) {
                    usage(sender, "season");
                    return;
                }
                asPlayer(sender, SEASON, player -> {
                    boolean ok = season.addGift(player.getLocation(), args[3]);
                    messages().send(player, ok ? "season.gift-added" : "season.gift-unknown-type",
                            Placeholder.unparsed("type", args[3]),
                            Placeholder.unparsed("types", String.join(", ",
                                    season.settings().giftSettings().types().keySet())));
                });
                break;
            case "reset":
                if (args.length < 4) {
                    usage(sender, "season");
                    return;
                }
                if (args[3].equalsIgnoreCase("all")) {
                    season.claims().resetAll();
                    season.saveClaims();
                    messages().send(sender, "season.gift-reset-all");
                    return;
                }
                Player player = Bukkit.getPlayerExact(args[3]);
                if (player == null) {
                    messages().send(sender, "player-not-found", Placeholder.unparsed("player", args[3]));
                    return;
                }
                season.claims().reset(player.getUniqueId());
                season.saveClaims();
                messages().send(sender, "season.gift-reset-player", Placeholder.unparsed("player", player.getName()));
                break;
            default:
                usage(sender, "season");
        }
    }

    private void teams(CommandSender sender, String[] args) {
        if (args.length == 1) {
            // Players get the menu; the console keeps the chat list.
            if (sender instanceof Player) {
                game.openTeamMenu((Player) sender);
            } else {
                game.listTeams(sender);
            }
            return;
        }
        if (!sender.hasPermission(TEAMS)) {
            messages().send(sender, "no-permission");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add":
                if (args.length < 3) {
                    usage(sender, "teams-admin");
                    return;
                }
                Integer rgb = null;
                if (args.length > 3) {
                    rgb = color(sender, args[3]);
                    if (rgb == null) {
                        return;
                    }
                }
                game.createTeam(sender, args[2], rgb, args.length > 4 ? args[4] : null);
                break;
            case "auto":
                int count;
                try {
                    count = args.length > 2 ? Integer.parseInt(args[2]) : -1;
                } catch (NumberFormatException e) {
                    count = -1;
                }
                if (count < 2 || count > 16) {
                    usage(sender, "teams-admin");
                    return;
                }
                game.autoTeams(sender, count);
                break;
            case "color":
                if (args.length < 4) {
                    usage(sender, "teams-admin");
                    return;
                }
                Integer newColor = color(sender, args[3]);
                if (newColor != null) {
                    game.recolorTeam(sender, args[2], newColor);
                }
                break;
            case "tag":
                if (args.length < 4) {
                    usage(sender, "teams-admin");
                    return;
                }
                game.retagTeam(sender, args[2], args[3]);
                break;
            case "remove":
                if (args.length < 3) {
                    usage(sender, "teams-admin");
                    return;
                }
                game.removeTeam(sender, args[2]);
                break;
            case "join":
                Player target = args.length > 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (target == null) {
                    usage(sender, "teams-admin");
                    return;
                }
                game.assignPlayer(sender, target, args[3]);
                break;
            case "clear":
                game.clearTeams(sender);
                break;
            default:
                usage(sender, "teams-admin");
        }
    }

    private Integer color(CommandSender sender, String raw) {
        Integer rgb = TeamPalette.parse(raw);
        if (rgb == null) {
            messages().send(sender, "team-color-invalid", Placeholder.unparsed("value", raw));
        }
        return rgb;
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

    private void version(CommandSender sender) {
        String current = plugin.updateChecker() != null
                ? plugin.updateChecker().currentVersion().clean()
                : plugin.getDescription().getVersion();
        messages().send(sender, "version-info", Placeholder.unparsed("current", current));
        if (sender.hasPermission("bingo.update") && plugin.updateChecker() != null) {
            plugin.updateChecker().check(sender);
        }
    }

    private void help(CommandSender sender) {
        messages().send(sender, "help.header");
        for (Map.Entry<String, String> line : HELP.entrySet()) {
            if (allowed(sender, line.getValue())) {
                messages().send(sender, "help." + line.getKey());
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
        if (!allowed(sender, permission)) {
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
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (Map.Entry<String, String> sub : SUBCOMMANDS.entrySet()) {
                if (allowed(sender, sub.getValue())) {
                    options.add(sub.getKey());
                }
            }
            return matching(options, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.containsKey(sub) || !allowed(sender, SUBCOMMANDS.get(sub))) {
            return Collections.emptyList();
        }
        String last = args[args.length - 1];
        switch (sub) {
            case "join":
                return args.length == 2 ? matching(teamNames(), last) : Collections.<String>emptyList();
            case "card":
                return args.length == 2 && sender.hasPermission(ROUND) ? matching(teamNames(), last)
                        : Collections.<String>emptyList();
            case "start":
                return args.length <= 3
                        ? matching(Arrays.asList("items", "advancements", "lockout:true", "lockout:false"), last)
                        : Collections.<String>emptyList();
            case "teams":
                return completeTeams(sender, args, last);
            case "history":
            case "export":
                if (args.length != 2) {
                    return Collections.emptyList();
                }
                List<String> ids = new ArrayList<>();
                if (sub.equals("history")) {
                    ids.add("clear");
                }
                for (MatchSummary summary : plugin.matchHistory().summaries()) {
                    ids.add(summary.id());
                }
                return matching(ids, last);
            case "arena":
                return completeArena(args, last);
            case "season":
                return completeSeason(args, last);
            default:
                return Collections.emptyList();
        }
    }

    private List<String> completeTeams(CommandSender sender, String[] args, String last) {
        if (!sender.hasPermission(TEAMS)) {
            return Collections.emptyList();
        }
        if (args.length == 2) {
            return matching(Arrays.asList("add", "remove", "join", "clear", "auto", "color", "tag"), last);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && (action.equals("remove") || action.equals("color") || action.equals("tag"))) {
            return matching(teamNames(), last);
        }
        if (args.length == 3 && action.equals("join")) {
            return matching(onlineNames(), last);
        }
        if (args.length == 4 && action.equals("join")) {
            return matching(teamNames(), last);
        }
        if ((args.length == 4 && action.equals("add")) || (args.length == 4 && action.equals("color"))) {
            return matching(TeamPalette.names(), last);
        }
        if (args.length == 3 && action.equals("auto")) {
            return matching(Arrays.asList("2", "4", "8"), last);
        }
        return Collections.emptyList();
    }

    private List<String> completeArena(String[] args, String last) {
        if (args.length == 2) {
            return matching(ARENA_ACTIONS, last);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && !action.equals("create") && !action.equals("list")) {
            List<String> ids = new ArrayList<>();
            for (GameMapDefinition arena : plugin.arenaManager().getAllArenas()) {
                ids.add(arena.id());
            }
            return matching(ids, last);
        }
        if (args.length == 4) {
            switch (action) {
                case "create":
                case "preset":
                    return matching(plugin.arenaManager().presetNames(), last);
                case "set":
                    return matching(settingKeys(), last);
                case "delete":
                    return matching(Collections.singletonList("confirm"), last);
                case "setwaiting":
                    return matching(Collections.singletonList("clear"), last);
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 5 && action.equals("set")) {
            ArenaOption option = ArenaOption.byKey(args[3]);
            return option == null || MODERN_ONLY.contains(option)
                    ? Collections.<String>emptyList() : matching(option.suggestions(), last);
        }
        return Collections.emptyList();
    }

    private List<String> completeSeason(String[] args, String last) {
        if (args.length == 2) {
            return matching(Arrays.asList("status", "phase", "reset", "restore", "gift", "preview"), last);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            switch (action) {
                case "phase":
                    return matching(Arrays.asList("inactive", "pre_season", "active", "wrap_up"), last);
                case "gift":
                    return matching(Arrays.asList("spawn", "clear", "add", "reset"), last);
                case "preview":
                    return matching(Arrays.asList("snowfall", "claim"), last);
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 4 && action.equals("gift")) {
            if (args[2].equalsIgnoreCase("add") && plugin.season() != null) {
                return matching(new ArrayList<>(plugin.season().settings().giftSettings().types().keySet()), last);
            }
            if (args[2].equalsIgnoreCase("reset")) {
                List<String> targets = onlineNames();
                targets.add(0, "all");
                return matching(targets, last);
            }
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

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
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
