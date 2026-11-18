package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.arena.ArenaManager;
import com.jifercraft.bingo.arena.ArenaMenu;
import com.jifercraft.bingo.arena.ArenaOption;
import com.jifercraft.bingo.arena.InvalidArenaValue;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.season.Season;
import com.jifercraft.bingo.season.SeasonPhase;
import com.jifercraft.bingo.world.MatchWorldManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The /bingo tree. Literals are English and every branch carries its own
 * permission, so tab completion only offers what the sender may run. The
 * help list comes from {@link #HELP}, the same table that names the
 * permission of each line, so the two cannot drift apart.
 */
public final class BingoCommand {

    public static final String PLAY = "bingo.play";
    public static final String ROUND = "bingo.admin.round";
    public static final String ARENA = ArenaMenu.PERMISSION;
    public static final String TEAMS = "bingo.admin.teams";
    public static final String HISTORY = "bingo.admin.history";
    public static final String RELOAD = "bingo.admin.reload";
    public static final String SEASON = "bingo.admin.season";

    private record HelpLine(String key, String permission) {
    }

    private static final List<HelpLine> HELP = List.of(
            new HelpLine("join", PLAY), new HelpLine("randomjoin", PLAY), new HelpLine("teams", PLAY),
            new HelpLine("card", PLAY), new HelpLine("leave", PLAY), new HelpLine("spectate", PLAY),
            new HelpLine("coords", PLAY), new HelpLine("lobby", PLAY), new HelpLine("vote", PLAY),
            new HelpLine("version", null),
            new HelpLine("start", ROUND), new HelpLine("stop", ROUND), new HelpLine("pause", ROUND),
            new HelpLine("resume", ROUND), new HelpLine("reroll", ROUND), new HelpLine("card-spectator", ROUND),
            new HelpLine("arena", ARENA), new HelpLine("arena-create", ARENA), new HelpLine("arena-set", ARENA),
            new HelpLine("arena-preset", ARENA), new HelpLine("arena-world", ARENA),
            new HelpLine("arena-waiting", ARENA), new HelpLine("setlobby", ARENA), new HelpLine("structure", ARENA),
            new HelpLine("teams-admin", TEAMS), new HelpLine("history", HISTORY), new HelpLine("export", HISTORY),
            new HelpLine("reload", RELOAD), new HelpLine("season", SEASON));

    private final BingoRush plugin;
    private final Game game;

    public BingoCommand(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("bingo")
                .executes(this::help)
                .then(Commands.literal("help").executes(this::help))
                .then(Commands.literal("ayuda").executes(this::help))
                .then(Commands.literal("version").executes(this::version))
                .then(player("join").executes(ctx -> runForPlayer(ctx, p -> game.joinTeam(p, null)))
                        .then(Commands.argument("team", StringArgumentType.string())
                                .suggests(this::suggestTeams)
                                .executes(ctx -> runForPlayer(ctx,
                                        p -> game.joinTeam(p, StringArgumentType.getString(ctx, "team"))))))
                .then(player("randomjoin").executes(ctx -> runForPlayer(ctx, game::randomJoin)))
                .then(player("autojoin").executes(ctx -> runForPlayer(ctx, game::randomJoin)))
                .then(player("leave").executes(ctx -> runForPlayer(ctx, game::leaveTeam)))
                .then(player("spectate").executes(ctx -> runForPlayer(ctx, game::spectate)))
                .then(player("coords").executes(ctx -> runForPlayer(ctx, game::shareCoordinates)))
                .then(player("c").executes(ctx -> runForPlayer(ctx, game::shareCoordinates)))
                .then(player("lobby").executes(ctx -> runForPlayer(ctx, game::teleportToLobby)))
                .then(player("vote").executes(ctx -> runForPlayer(ctx, p -> {
                    if (game.votingEnabled()) {
                        plugin.modeVoteManager().promptVote(p);
                    } else {
                        plugin.messages().send(p, "vote-disabled");
                    }
                })))
                .then(player("card").executes(ctx -> runForPlayer(ctx, game::openCard))
                        .then(Commands.argument("team", StringArgumentType.string())
                                .requires(source -> has(source, ROUND))
                                .suggests(this::suggestTeams)
                                .executes(ctx -> runForPlayer(ctx, p ->
                                        game.openCardSpectator(p, StringArgumentType.getString(ctx, "team"))))))
                .then(teamsNode())
                .then(startNode())
                .then(admin("stop", ROUND).executes(ctx -> run(ctx, game::stopRound)))
                .then(admin("pause", ROUND).executes(ctx -> run(ctx, game::pauseRound)))
                .then(admin("resume", ROUND).executes(ctx -> run(ctx, game::resumeRound)))
                .then(admin("reroll", ROUND).executes(ctx -> run(ctx, game::rerollCard)))
                .then(admin("setlobby", ARENA).executes(ctx -> runForPlayer(ctx, game::setLobby)))
                .then(arenaNode())
                .then(structureNode())
                .then(historyNode())
                .then(admin("export", HISTORY).executes(ctx -> usage(ctx, "export"))
                        .then(Commands.argument("id", StringArgumentType.string())
                                .suggests(this::suggestMatchIds)
                                .executes(ctx -> run(ctx, sender ->
                                        game.exportMatch(sender, StringArgumentType.getString(ctx, "id"))))))
                .then(admin("reload", RELOAD).executes(ctx -> run(ctx, sender -> {
                    plugin.reloadSettings();
                    plugin.messages().send(sender, "reloaded");
                })))
                .then(seasonNode())
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> buildCardAlias(String alias) {
        return player(alias).executes(ctx -> runForPlayer(ctx, game::openCard)).build();
    }

    public LiteralCommandNode<CommandSourceStack> buildTeamAlias(String alias) {
        return player(alias).executes(ctx -> runForPlayer(ctx, game::openTeamMenu)).build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> player(String literal) {
        return Commands.literal(literal).requires(source -> has(source, PLAY));
    }

    private LiteralArgumentBuilder<CommandSourceStack> admin(String literal, String permission) {
        return Commands.literal(literal).requires(source -> has(source, permission));
    }

    private static boolean has(CommandSourceStack source, String permission) {
        return permission == null || source.getSender().hasPermission(permission);
    }

    private LiteralArgumentBuilder<CommandSourceStack> teamsNode() {
        return player("teams")
                .executes(ctx -> {
                    // Players get the menu; the console keeps the chat list.
                    if (ctx.getSource().getExecutor() instanceof Player p) {
                        game.openTeamMenu(p);
                    } else {
                        game.listTeams(ctx.getSource().getSender());
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(admin("add", TEAMS).executes(ctx -> usage(ctx, "teams-admin"))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> run(ctx, sender -> addTeam(sender, ctx, false, false)))
                                .then(Commands.argument("color", StringArgumentType.word())
                                        .suggests(this::suggestColors)
                                        .executes(ctx -> run(ctx, sender -> addTeam(sender, ctx, true, false)))
                                        .then(Commands.argument("tag", StringArgumentType.word())
                                                .executes(ctx -> run(ctx, sender -> addTeam(sender, ctx, true, true)))))))
                .then(admin("auto", TEAMS).executes(ctx -> usage(ctx, "teams-admin"))
                        .then(Commands.argument("count", IntegerArgumentType.integer(2, 16))
                                .executes(ctx -> run(ctx, sender ->
                                        game.autoTeams(sender, IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(admin("color", TEAMS).executes(ctx -> usage(ctx, "teams-admin"))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(this::suggestTeams)
                                .then(Commands.argument("color", StringArgumentType.word())
                                        .suggests(this::suggestColors)
                                        .executes(ctx -> run(ctx, sender -> {
                                            Integer rgb = color(sender, StringArgumentType.getString(ctx, "color"));
                                            if (rgb != null) {
                                                game.recolorTeam(sender, StringArgumentType.getString(ctx, "name"), rgb);
                                            }
                                        })))))
                .then(admin("tag", TEAMS).executes(ctx -> usage(ctx, "teams-admin"))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(this::suggestTeams)
                                .then(Commands.argument("tag", StringArgumentType.word())
                                        .executes(ctx -> run(ctx, sender -> game.retagTeam(sender,
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "tag")))))))
                .then(admin("remove", TEAMS).executes(ctx -> usage(ctx, "teams-admin"))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(this::suggestTeams)
                                .executes(ctx -> run(ctx, sender -> game.removeTeam(sender,
                                        StringArgumentType.getString(ctx, "name"))))))
                .then(admin("join", TEAMS).executes(ctx -> usage(ctx, "teams-admin"))
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .then(Commands.argument("team", StringArgumentType.string())
                                        .suggests(this::suggestTeams)
                                        // resolve() throws CommandSyntaxException, so the
                                        // resolution happens here, outside the Consumer.
                                        .executes(ctx -> {
                                            List<Player> targets = ctx.getArgument("player",
                                                    PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
                                            return run(ctx, sender -> {
                                                if (!targets.isEmpty()) {
                                                    game.assignPlayer(sender, targets.getFirst(),
                                                            StringArgumentType.getString(ctx, "team"));
                                                }
                                            });
                                        }))))
                .then(admin("clear", TEAMS).executes(ctx -> run(ctx, game::clearTeams)));
    }

    private void addTeam(CommandSender sender, CommandContext<CommandSourceStack> ctx, boolean withColor, boolean withTag) {
        Integer rgb = null;
        if (withColor) {
            rgb = color(sender, StringArgumentType.getString(ctx, "color"));
            if (rgb == null) {
                return;
            }
        }
        game.createTeam(sender, StringArgumentType.getString(ctx, "name"), rgb,
                withTag ? StringArgumentType.getString(ctx, "tag") : null);
    }

    private Integer color(CommandSender sender, String raw) {
        Integer rgb = TeamPalette.parse(raw);
        if (rgb == null) {
            plugin.messages().send(sender, "team-color-invalid", Placeholder.unparsed("value", raw));
        }
        return rgb;
    }

    private LiteralArgumentBuilder<CommandSourceStack> startNode() {
        return admin("start", ROUND)
                .executes(ctx -> run(ctx, sender -> game.startRound(sender, null, null, null)))
                .then(Commands.literal("items")
                        .executes(ctx -> run(ctx, sender -> game.startRound(sender, BingoGameMode.ITEMS, null, null)))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(this::suggestStartArgs)
                                .executes(ctx -> run(ctx, sender -> handleStart(sender,
                                        BingoGameMode.ITEMS, StringArgumentType.getString(ctx, "args"))))))
                .then(Commands.literal("advancements")
                        .executes(ctx -> run(ctx, sender -> game.startRound(sender, BingoGameMode.ADVANCEMENTS, null, null)))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(this::suggestStartArgs)
                                .executes(ctx -> run(ctx, sender -> handleStart(sender,
                                        BingoGameMode.ADVANCEMENTS, StringArgumentType.getString(ctx, "args"))))))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests(this::suggestStartArgs)
                        .executes(ctx -> run(ctx, sender -> handleStart(sender,
                                null, StringArgumentType.getString(ctx, "args")))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> arenaNode() {
        return admin("arena", ARENA)
                .executes(ctx -> {
                    if (ctx.getSource().getExecutor() instanceof Player p) {
                        plugin.arenaMenu().openArenaList(p);
                    } else {
                        listArenas(ctx.getSource().getSender());
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("list").executes(ctx -> run(ctx, this::listArenas)))
                .then(Commands.literal("create").executes(ctx -> usage(ctx, "arena-create"))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> run(ctx, sender -> createArena(sender, ctx, null)))
                                .then(Commands.argument("preset", StringArgumentType.word())
                                        .suggests(this::suggestPresets)
                                        .executes(ctx -> run(ctx, sender -> createArena(sender, ctx,
                                                StringArgumentType.getString(ctx, "preset")))))))
                .then(Commands.literal("edit").executes(ctx -> usage(ctx, "arena"))
                        .then(arenaArgument().executes(ctx -> runForPlayer(ctx, p ->
                                plugin.arenaMenu().openArenaEdit(p, arenaId(ctx))))))
                .then(Commands.literal("set").executes(ctx -> usage(ctx, "arena-set"))
                        .then(arenaArgument().executes(ctx -> usage(ctx, "arena-set"))
                                .then(Commands.argument("option", StringArgumentType.word())
                                        .suggests(this::suggestOptions)
                                        .executes(ctx -> usage(ctx, "arena-set"))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .suggests(this::suggestOptionValues)
                                                .executes(ctx -> run(ctx, sender -> setOption(sender, ctx)))))))
                .then(Commands.literal("preset").executes(ctx -> usage(ctx, "arena-preset"))
                        .then(arenaArgument().executes(ctx -> usage(ctx, "arena-preset"))
                                .then(Commands.argument("preset", StringArgumentType.word())
                                        .suggests(this::suggestPresets)
                                        .executes(ctx -> run(ctx, sender -> applyPreset(sender, ctx))))))
                .then(Commands.literal("savepreset").executes(ctx -> usage(ctx, "arena-preset"))
                        .then(arenaArgument().executes(ctx -> usage(ctx, "arena-preset"))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> run(ctx, sender -> savePreset(sender, ctx))))))
                .then(Commands.literal("delete").executes(ctx -> usage(ctx, "arena"))
                        .then(arenaArgument().executes(ctx -> run(ctx, sender ->
                                        plugin.messages().send(sender, "arena.delete-confirm",
                                                Placeholder.unparsed("arena", arenaId(ctx)))))
                                .then(Commands.literal("confirm").executes(ctx -> run(ctx, sender ->
                                        deleteArena(sender, arenaId(ctx)))))))
                .then(Commands.literal("select").executes(ctx -> usage(ctx, "arena"))
                        .then(arenaArgument().executes(ctx -> run(ctx, sender -> selectArena(sender, arenaId(ctx))))))
                .then(Commands.literal("prepare").executes(ctx -> usage(ctx, "arena-world"))
                        .then(arenaArgument().executes(ctx -> run(ctx, sender -> prepareWorld(sender, arenaId(ctx))))))
                .then(Commands.literal("reset").executes(ctx -> usage(ctx, "arena-world"))
                        .then(arenaArgument().executes(ctx -> run(ctx, sender -> resetWorld(sender, arenaId(ctx))))))
                .then(Commands.literal("setwaiting").executes(ctx -> usage(ctx, "arena-waiting"))
                        .then(arenaArgument()
                                .executes(ctx -> runForPlayer(ctx, p -> setWaiting(p, arenaId(ctx), p.getLocation())))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> run(ctx, sender -> setWaiting(sender, arenaId(ctx), null))))));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> arenaArgument() {
        return Commands.argument("arena", StringArgumentType.word()).suggests(this::suggestArenas);
    }

    private static String arenaId(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "arena").toLowerCase(Locale.ROOT);
    }

    private LiteralArgumentBuilder<CommandSourceStack> structureNode() {
        return admin("structure", ARENA)
                .executes(ctx -> run(ctx, sender -> plugin.messages().send(sender, "structure.guide")))
                .then(Commands.literal("pos1").executes(ctx -> runForPlayer(ctx, p -> {
                    plugin.matchWorldManager().setPos1(p.getUniqueId(), p.getLocation());
                    sendPoint(p, "structure.pos1");
                })))
                .then(Commands.literal("pos2").executes(ctx -> runForPlayer(ctx, p -> {
                    plugin.matchWorldManager().setPos2(p.getUniqueId(), p.getLocation());
                    sendPoint(p, "structure.pos2");
                })))
                .then(Commands.literal("center").executes(ctx -> runForPlayer(ctx, p -> {
                    plugin.matchWorldManager().setCenter(p.getUniqueId(), p.getLocation());
                    sendPoint(p, "structure.center");
                })))
                .then(Commands.literal("save").executes(ctx -> usage(ctx, "structure"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestArenas)
                                .executes(ctx -> runForPlayer(ctx, p -> {
                                    String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                                    plugin.arenaMenu().sendCapture(p,
                                            plugin.matchWorldManager().saveStructure(p.getUniqueId(), name), name);
                                }))))
                .then(Commands.literal("list").executes(ctx -> run(ctx, sender -> {
                    List<String> names = plugin.matchWorldManager().listStructures();
                    plugin.messages().send(sender, names.isEmpty() ? "structure.none" : "structure.list",
                            Placeholder.unparsed("names", String.join(", ", names)));
                })))
                .then(Commands.literal("delete").executes(ctx -> usage(ctx, "structure"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestStructures)
                                .executes(ctx -> run(ctx, sender -> {
                                    String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                                    boolean deleted = plugin.matchWorldManager().deleteStructure(name);
                                    plugin.messages().send(sender, deleted ? "structure.deleted" : "structure.not-found",
                                            Placeholder.unparsed("name", name));
                                }))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> historyNode() {
        return admin("history", HISTORY)
                .executes(ctx -> run(ctx, sender -> game.showHistory(sender, 1)))
                .then(Commands.literal("clear").executes(ctx -> run(ctx, sender -> {
                    plugin.matchHistory().clearHistory();
                    plugin.messages().send(sender, "history.cleared");
                })))
                .then(Commands.argument("query", StringArgumentType.string())
                        .suggests(this::suggestHistory)
                        .executes(ctx -> run(ctx, sender ->
                                game.showHistoryOrDetails(sender, StringArgumentType.getString(ctx, "query")))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> seasonNode() {
        return admin("season", SEASON)
                .executes(this::seasonStatus)
                .then(Commands.literal("status").executes(this::seasonStatus))
                .then(Commands.literal("phase").executes(ctx -> usage(ctx, "season"))
                        .then(Commands.argument("phase", StringArgumentType.word())
                                .suggests(this::suggestPhases)
                                .executes(this::seasonPhase)))
                .then(Commands.literal("reset").executes(ctx -> withSeason(ctx, season -> {
                    season.resetClock();
                    seasonStatus(ctx);
                })))
                .then(Commands.literal("restore").executes(ctx -> withSeason(ctx, season -> {
                    int count = season.restoreBlocks();
                    plugin.messages().send(ctx.getSource().getSender(),
                            count > 0 ? "season.restored" : "season.no-blocks",
                            Placeholder.unparsed("count", String.valueOf(count)));
                })))
                .then(Commands.literal("gift")
                        .then(Commands.literal("spawn").executes(ctx -> withSeason(ctx, season -> {
                            Location lobby = game.lobbyLocation();
                            if (lobby == null) {
                                plugin.messages().send(ctx.getSource().getSender(), "lobby-not-set");
                                return;
                            }
                            plugin.messages().send(ctx.getSource().getSender(), "season.gift-spawned",
                                    Placeholder.unparsed("count", String.valueOf(season.spawnGifts(lobby))));
                        })))
                        .then(Commands.literal("clear").executes(ctx -> withSeason(ctx, season -> {
                            season.clearGifts();
                            plugin.messages().send(ctx.getSource().getSender(), "season.gift-cleared");
                        })))
                        .then(Commands.literal("add").executes(ctx -> usage(ctx, "season"))
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests(this::suggestGiftTypes)
                                        .executes(ctx -> runForPlayer(ctx, p -> {
                                            Season season = activeSeason(p);
                                            if (season == null) {
                                                return;
                                            }
                                            String type = StringArgumentType.getString(ctx, "type");
                                            boolean ok = season.addGift(p.getLocation(), type);
                                            plugin.messages().send(p, ok ? "season.gift-added" : "season.gift-unknown-type",
                                                    Placeholder.unparsed("type", type),
                                                    Placeholder.unparsed("types", String.join(", ",
                                                            season.settings().giftSettings().types().keySet())));
                                        }))))
                        .then(Commands.literal("reset").executes(ctx -> usage(ctx, "season"))
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .suggests((c, b) -> suggestGiftResetTargets(b))
                                        .executes(this::seasonGiftReset))))
                .then(Commands.literal("preview")
                        .then(Commands.literal("snowfall").executes(ctx -> runForPlayer(ctx, p -> {
                            Season season = activeSeason(p);
                            if (season != null) {
                                season.onCountdownSnow(p.getLocation());
                            }
                        })))
                        .then(Commands.literal("claim").executes(ctx -> runForPlayer(ctx, p -> {
                            Season season = activeSeason(p);
                            if (season != null) {
                                season.onClaim(p);
                            }
                        }))));
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        Messages messages = plugin.messages();
        CommandSender sender = ctx.getSource().getSender();
        messages.send(sender, "help.header");
        for (HelpLine line : HELP) {
            if (line.permission() == null || sender.hasPermission(line.permission())) {
                messages.send(sender, "help." + line.key());
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    // A branch typed without its arguments: say what is missing and show
    // the help line for it instead of the vanilla "incomplete command".
    private int usage(CommandContext<CommandSourceStack> ctx, String helpKey) {
        CommandSender sender = ctx.getSource().getSender();
        plugin.messages().send(sender, "usage-hint");
        plugin.messages().send(sender, "help." + helpKey);
        return Command.SINGLE_SUCCESS;
    }

    private int version(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String ver = plugin.updateChecker() != null
                ? plugin.updateChecker().currentVersion().clean()
                : plugin.getPluginMeta().getVersion();
        plugin.messages().send(sender, "version-info", Placeholder.unparsed("current", ver));
        if (sender.hasPermission("bingo.update") && plugin.updateChecker() != null) {
            plugin.updateChecker().check(sender);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int run(CommandContext<CommandSourceStack> ctx, Consumer<CommandSender> action) {
        action.accept(ctx.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }

    /** Commands that only work for a player, with a clear line for the console. */
    private int runForPlayer(CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
        if (ctx.getSource().getExecutor() instanceof Player p) {
            action.accept(p);
        } else {
            plugin.messages().send(ctx.getSource().getSender(), "players-only");
        }
        return Command.SINGLE_SUCCESS;
    }

    private void sendPoint(Player p, String key) {
        Location at = p.getLocation();
        plugin.messages().send(p, key,
                Placeholder.unparsed("x", String.valueOf(at.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(at.getBlockY())),
                Placeholder.unparsed("z", String.valueOf(at.getBlockZ())));
    }

    private void listArenas(CommandSender sender) {
        List<GameMapDefinition> arenas = plugin.arenaManager().getAllArenas();
        if (arenas.isEmpty()) {
            plugin.messages().send(sender, "arena.list-empty");
            return;
        }
        plugin.messages().send(sender, "arena.list-header", Placeholder.unparsed("count", String.valueOf(arenas.size())));
        for (GameMapDefinition arena : arenas) {
            plugin.messages().send(sender, "arena.list-entry",
                    Placeholder.unparsed("arena", arena.id()),
                    Placeholder.unparsed("name", arena.name()),
                    Placeholder.unparsed("mode", ArenaOption.MODE.format(arena.mode())),
                    Placeholder.unparsed("team_size", String.valueOf(arena.teamSize())),
                    Placeholder.unparsed("max_teams", String.valueOf(arena.maxTeams())),
                    Placeholder.unparsed("selected", game.isMapSelected(arena.id()) ? "*" : ""));
        }
    }

    private void createArena(CommandSender sender, CommandContext<CommandSourceStack> ctx, String preset) {
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        ArenaManager.Result result = plugin.arenaManager().create(id, preset);
        if (!result.ok()) {
            plugin.arenaMenu().sendProblem(sender, result.problem(),
                    result.problem() == ArenaManager.Problem.UNKNOWN_PRESET ? preset : id);
            return;
        }
        plugin.messages().send(sender, "arena.created", Placeholder.unparsed("arena", id));
        if (sender instanceof Player p) {
            plugin.arenaMenu().openArenaEdit(p, id);
        }
    }

    private void setOption(CommandSender sender, CommandContext<CommandSourceStack> ctx) {
        String id = arenaId(ctx);
        String optionKey = StringArgumentType.getString(ctx, "option");
        ArenaOption option = ArenaOption.byKey(optionKey);
        if (option == null) {
            plugin.messages().send(sender, "arena.unknown-option", Placeholder.unparsed("value", optionKey),
                    Placeholder.unparsed("options", optionKeys()));
            return;
        }
        Object value;
        try {
            value = option.parse(StringArgumentType.getString(ctx, "value"));
        } catch (InvalidArenaValue e) {
            plugin.arenaMenu().sendInvalid(sender, e);
            return;
        }
        ArenaManager.Result result = plugin.arenaManager().set(id, option, value);
        if (!result.ok()) {
            plugin.arenaMenu().sendProblem(sender, result.problem(), id);
            return;
        }
        plugin.messages().send(sender, "arena.updated", Placeholder.unparsed("arena", id),
                Placeholder.component("option", plugin.messages().render("arena-option-name." + option.key())),
                Placeholder.component("value", plugin.arenaMenu().valueComponent(option, value)));
        if (option == ArenaOption.SEED && plugin.matchWorldManager().world(id) != null) {
            plugin.messages().send(sender, "arena.seed-after-reset", Placeholder.unparsed("arena", id));
        }
    }

    private void applyPreset(CommandSender sender, CommandContext<CommandSourceStack> ctx) {
        String id = arenaId(ctx);
        String preset = StringArgumentType.getString(ctx, "preset");
        ArenaManager.Result result = plugin.arenaManager().applyPreset(id, preset);
        if (!result.ok()) {
            plugin.arenaMenu().sendProblem(sender, result.problem(),
                    result.problem() == ArenaManager.Problem.UNKNOWN_PRESET ? preset : id);
            return;
        }
        plugin.messages().send(sender, "arena.preset-applied", Placeholder.unparsed("arena", id),
                Placeholder.unparsed("preset", preset.toLowerCase(Locale.ROOT)));
    }

    private void savePreset(CommandSender sender, CommandContext<CommandSourceStack> ctx) {
        String id = arenaId(ctx);
        String name = StringArgumentType.getString(ctx, "name");
        GameMapDefinition arena = plugin.arenaManager().getArena(id);
        if (arena == null) {
            plugin.arenaMenu().sendProblem(sender, ArenaManager.Problem.NOT_FOUND, id);
            return;
        }
        if (!plugin.arenaManager().savePreset(name, arena)) {
            plugin.messages().send(sender, "arena.invalid-preset-name", Placeholder.unparsed("value", name));
            return;
        }
        plugin.messages().send(sender, "arena.preset-saved", Placeholder.unparsed("arena", id),
                Placeholder.unparsed("preset", ArenaOption.toId(name)));
    }

    private void deleteArena(CommandSender sender, String id) {
        ArenaManager.Result result = plugin.arenaManager().delete(id);
        if (!result.ok()) {
            plugin.arenaMenu().sendProblem(sender, result.problem(), id);
            return;
        }
        plugin.messages().send(sender, "arena.deleted", Placeholder.unparsed("arena", id));
    }

    private void selectArena(CommandSender sender, String id) {
        if (plugin.arenaManager().getArena(id) == null) {
            plugin.arenaMenu().sendProblem(sender, ArenaManager.Problem.NOT_FOUND, id);
            return;
        }
        if (!game.selectMap(id)) {
            plugin.messages().send(sender, "arena.select-busy");
            return;
        }
        plugin.messages().send(sender, "arena.selected", Placeholder.unparsed("arena", id));
    }

    private void prepareWorld(CommandSender sender, String id) {
        GameMapDefinition arena = plugin.arenaManager().getArena(id);
        if (arena == null) {
            plugin.arenaMenu().sendProblem(sender, ArenaManager.Problem.NOT_FOUND, id);
            return;
        }
        long started = System.nanoTime();
        MatchWorldManager worlds = plugin.matchWorldManager();
        if (worlds.getOrCreateArenaWorld(arena) == null) {
            plugin.messages().send(sender, "arena.world-unavailable", Placeholder.unparsed("arena", id));
            return;
        }
        plugin.messages().send(sender, "arena.world-prepared", Placeholder.unparsed("arena", id),
                Placeholder.unparsed("key", worlds.overworldKey(id).toString()),
                Placeholder.unparsed("ms", String.valueOf((System.nanoTime() - started) / 1_000_000L)));
        // Nobody is in it yet; it goes away like any empty match world.
        worlds.notifyPlayerLeft(id);
    }

    private void resetWorld(CommandSender sender, String id) {
        if (plugin.arenaManager().getArena(id) == null) {
            plugin.arenaMenu().sendProblem(sender, ArenaManager.Problem.NOT_FOUND, id);
            return;
        }
        if (id.equals(game.roundArenaId())) {
            plugin.arenaMenu().sendProblem(sender, ArenaManager.Problem.IN_USE, id);
            return;
        }
        plugin.matchWorldManager().unloadAndDeleteWorld(id);
        plugin.messages().send(sender, "arena.world-reset", Placeholder.unparsed("arena", id));
    }

    private void setWaiting(CommandSender sender, String id, Location location) {
        ArenaManager.Result result = plugin.arenaManager().setWaitingLobby(id, location);
        if (!result.ok()) {
            plugin.arenaMenu().sendProblem(sender, result.problem(), id);
            return;
        }
        if (location == null) {
            plugin.messages().send(sender, "arena-menu.waiting-reset");
            return;
        }
        plugin.messages().send(sender, "waiting-lobby-set",
                Placeholder.unparsed("map", id),
                Placeholder.unparsed("world", location.getWorld().getKey().toString()),
                Placeholder.unparsed("x", String.format(Locale.ROOT, "%.1f", location.getX())),
                Placeholder.unparsed("y", String.format(Locale.ROOT, "%.1f", location.getY())),
                Placeholder.unparsed("z", String.format(Locale.ROOT, "%.1f", location.getZ())));
    }

    private static String optionKeys() {
        StringBuilder keys = new StringBuilder();
        for (ArenaOption option : ArenaOption.values()) {
            if (!keys.isEmpty()) {
                keys.append(", ");
            }
            keys.append(option.key());
        }
        return keys.toString();
    }

    private void handleStart(CommandSender sender, BingoGameMode mode, String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) {
            game.startRound(sender, mode, null, null);
            return;
        }
        String trimmed = rawArgs.trim();
        String[] parts = trimmed.split("\\s+", 2);
        String first = parts[0].toLowerCase(Locale.ROOT);
        Boolean lockout = null;
        if (first.startsWith("lockout:")) {
            String val = first.substring("lockout:".length());
            if ("true".equals(val) || "yes".equals(val)) {
                lockout = Boolean.TRUE;
            } else if ("false".equals(val) || "no".equals(val)) {
                lockout = Boolean.FALSE;
            }
        } else if ("true".equals(first)) {
            lockout = Boolean.TRUE;
        } else if ("false".equals(first)) {
            lockout = Boolean.FALSE;
        }
        String seed;
        if (lockout != null) {
            seed = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : null;
        } else {
            seed = trimmed;
        }
        game.startRound(sender, mode, lockout, seed);
    }

    private Season activeSeason(CommandSender sender) {
        Season season = plugin.season();
        if (season == null || !season.settings().enabled()) {
            plugin.messages().send(sender, "season.disabled");
            return null;
        }
        return season;
    }

    private int withSeason(CommandContext<CommandSourceStack> ctx, Consumer<Season> action) {
        Season season = activeSeason(ctx.getSource().getSender());
        if (season != null) {
            action.accept(season);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int seasonStatus(CommandContext<CommandSourceStack> ctx) {
        return withSeason(ctx, season -> {
            SeasonPhase phase = season.currentPhase();
            long now = season.clock().currentTimeMillis();
            long remaining = season.settings().windowAt(now).remainingMillisInPhase(now);
            long remSeconds = Math.max(0, remaining / 1000L);
            String formatted = String.format(Locale.ROOT, "%02dh %02dm %02ds",
                    remSeconds / 3600, (remSeconds % 3600) / 60, remSeconds % 60);
            plugin.messages().send(ctx.getSource().getSender(), "season.status",
                    Placeholder.unparsed("phase", phase.name()),
                    Placeholder.unparsed("remaining", formatted));
        });
    }

    private int seasonPhase(CommandContext<CommandSourceStack> ctx) {
        return withSeason(ctx, season -> {
            CommandSender sender = ctx.getSource().getSender();
            String phaseName = StringArgumentType.getString(ctx, "phase").toUpperCase(Locale.ROOT);
            SeasonPhase phase;
            try {
                phase = SeasonPhase.valueOf(phaseName);
            } catch (IllegalArgumentException e) {
                plugin.messages().send(sender, "season.unknown-phase", Placeholder.unparsed("value", phaseName));
                return;
            }
            season.simulatePhase(phase);
            plugin.messages().send(sender, "season.simulated", Placeholder.unparsed("phase", phase.name()));
        });
    }

    private int seasonGiftReset(CommandContext<CommandSourceStack> ctx) {
        return withSeason(ctx, season -> {
            CommandSender sender = ctx.getSource().getSender();
            String target = StringArgumentType.getString(ctx, "target");
            if ("all".equalsIgnoreCase(target)) {
                season.claims().resetAll();
                season.saveClaims();
                plugin.messages().send(sender, "season.gift-reset-all");
                return;
            }
            Player p = Bukkit.getPlayerExact(target);
            if (p == null) {
                plugin.messages().send(sender, "player-not-found", Placeholder.unparsed("player", target));
                return;
            }
            season.claims().reset(p.getUniqueId());
            season.saveClaims();
            plugin.messages().send(sender, "season.gift-reset-player", Placeholder.unparsed("player", p.getName()));
        });
    }

    private CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, Iterable<String> values) {
        String remaining = builder.getRemainingLowerCase();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(value);
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestTeams(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(builder, game.teams().all().stream().map(Team::name).toList());
    }

    private CompletableFuture<Suggestions> suggestColors(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(builder, TeamPalette.names());
    }

    private CompletableFuture<Suggestions> suggestArenas(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(builder, plugin.arenaManager().getAllArenas().stream().map(GameMapDefinition::id).toList());
    }

    private CompletableFuture<Suggestions> suggestPresets(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(builder, plugin.arenaManager().presetNames());
    }

    private CompletableFuture<Suggestions> suggestStructures(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(builder, plugin.matchWorldManager().listStructures());
    }

    private CompletableFuture<Suggestions> suggestOptions(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(builder, Arrays.stream(ArenaOption.values()).map(ArenaOption::key).toList());
    }

    private CompletableFuture<Suggestions> suggestOptionValues(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        ArenaOption option = ArenaOption.byKey(StringArgumentType.getString(ctx, "option"));
        return option == null ? builder.buildFuture() : suggest(builder, option.suggestions());
    }

    private CompletableFuture<Suggestions> suggestStartArgs(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(builder, List.of("lockout:true", "lockout:false", "true", "false"));
    }

    private CompletableFuture<Suggestions> suggestMatchIds(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(builder, plugin.matchHistory().summaries().stream().map(s -> s.id()).toList());
    }

    private CompletableFuture<Suggestions> suggestHistory(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        List<String> values = new ArrayList<>();
        values.add("clear");
        int totalPages = plugin.matchHistory().totalPages(5);
        for (int p = 1; p <= Math.min(totalPages, 5); p++) {
            values.add(String.valueOf(p));
        }
        plugin.matchHistory().summaries().forEach(s -> values.add(s.id()));
        return suggest(builder, values);
    }

    private CompletableFuture<Suggestions> suggestGiftTypes(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        Season season = plugin.season();
        return season == null ? builder.buildFuture()
                : suggest(builder, season.settings().giftSettings().types().keySet());
    }

    private CompletableFuture<Suggestions> suggestGiftResetTargets(SuggestionsBuilder builder) {
        List<String> values = new ArrayList<>();
        values.add("all");
        Bukkit.getOnlinePlayers().forEach(p -> values.add(p.getName()));
        return suggest(builder, values);
    }

    private CompletableFuture<Suggestions> suggestPhases(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return suggest(builder, Arrays.stream(SeasonPhase.values())
                .map(p -> p.name().toLowerCase(Locale.ROOT)).toList());
    }
}
