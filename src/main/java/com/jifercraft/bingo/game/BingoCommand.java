package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.config.Messages;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The /bingo tree. English command and subcommand literals. The
 * admin branches hide themselves from anyone without bingo.admin.
 */
public final class BingoCommand {

    private static final List<String> PLAYER_HELP = List.of("join", "teams", "card", "leave", "coords", "version");
    private static final List<String> ADMIN_HELP = List.of(
            "card-spectator", "start", "setup", "stop", "pause", "resume", "reroll", "teams-admin",
            "history", "export", "reload");

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
                .then(Commands.literal("join")
                        .requires(this::isPlayerAllowed)
                        .executes(ctx -> runForPlayer(ctx, player -> game.joinTeam(player, null)))
                        .then(Commands.argument("team", StringArgumentType.string())
                                .suggests(this::suggestTeams)
                                .executes(ctx -> runForPlayer(ctx,
                                        player -> game.joinTeam(player, StringArgumentType.getString(ctx, "team"))))))
                .then(Commands.literal("leave")
                        .requires(this::isPlayerAllowed)
                        .executes(ctx -> runForPlayer(ctx, game::leaveTeam)))
                .then(Commands.literal("coords")
                        .requires(this::isPlayerAllowed)
                        .executes(ctx -> runForPlayer(ctx, game::shareCoordinates)))
                .then(Commands.literal("c")
                        .requires(this::isPlayerAllowed)
                        .executes(ctx -> runForPlayer(ctx, game::shareCoordinates)))
                .then(Commands.literal("card")
                        .requires(this::isPlayerAllowed)
                        .executes(ctx -> runForPlayer(ctx, game::openCard))
                        .then(Commands.argument("team", StringArgumentType.string())
                                .requires(this::isAdmin)
                                .suggests(this::suggestTeams)
                                .executes(ctx -> runForPlayer(ctx, player ->
                                        game.openCardSpectator(player, StringArgumentType.getString(ctx, "team"))))))
                .then(Commands.literal("start")
                        .requires(this::isAdmin)
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
                                        null, StringArgumentType.getString(ctx, "args"))))))
                .then(Commands.literal("setup")
                        .requires(this::isAdmin)
                        .executes(ctx -> runForPlayer(ctx, game::openSetupMenu)))
                .then(Commands.literal("stop").requires(this::isAdmin)
                        .executes(ctx -> run(ctx, game::stopRound)))
                .then(Commands.literal("pause").requires(this::isAdmin)
                        .executes(ctx -> run(ctx, game::pauseRound)))
                .then(Commands.literal("resume").requires(this::isAdmin)
                        .executes(ctx -> run(ctx, game::resumeRound)))
                .then(Commands.literal("reroll").requires(this::isAdmin)
                        .executes(ctx -> run(ctx, game::rerollCard)))
                .then(Commands.literal("reload").requires(this::isAdmin)
                        .executes(ctx -> run(ctx, sender -> {
                            plugin.reloadSettings();
                            plugin.messages().send(sender, "reloaded");
                        })))
                .then(Commands.literal("teams")
                        .requires(this::isPlayerAllowed)
                        .executes(ctx -> {
                            // Players get the menu; the console keeps the chat list.
                            if (ctx.getSource().getExecutor() instanceof Player player) {
                                game.openTeamMenu(player);
                            } else {
                                game.listTeams(ctx.getSource().getSender());
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("add")
                                .requires(this::isAdmin)
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("color", ArgumentTypes.hexColor())
                                                .executes(ctx -> run(ctx, sender -> game.createTeam(sender,
                                                        StringArgumentType.getString(ctx, "name"),
                                                        ctx.getArgument("color", TextColor.class)))))))
                        .then(Commands.literal("remove")
                                .requires(this::isAdmin)
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(this::suggestTeams)
                                        .executes(ctx -> run(ctx, sender -> game.removeTeam(sender,
                                                StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("join")
                                .requires(this::isAdmin)
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
                        .then(Commands.literal("clear")
                                .requires(this::isAdmin)
                                .executes(ctx -> run(ctx, game::clearTeams))))
                .then(Commands.literal("history")
                        .requires(this::isAdmin)
                        .executes(ctx -> run(ctx, sender -> game.showHistory(sender, 1)))
                        .then(Commands.literal("clear")
                                .executes(ctx -> run(ctx, sender -> {
                                    plugin.matchHistory().clearHistory();
                                    plugin.messages().send(sender, "history.cleared");
                                })))
                        .then(Commands.argument("query", StringArgumentType.string())
                                .suggests(this::suggestHistory)
                                .executes(ctx -> run(ctx, sender ->
                                        game.showHistoryOrDetails(sender, StringArgumentType.getString(ctx, "query"))))))
                .then(Commands.literal("export")
                        .requires(this::isAdmin)
                        .then(Commands.argument("id", StringArgumentType.string())
                                .suggests(this::suggestMatchIds)
                                .executes(ctx -> run(ctx, sender ->
                                        game.exportMatch(sender, StringArgumentType.getString(ctx, "id"))))))
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> buildCardAlias(String alias) {
        return Commands.literal(alias)
                .requires(this::isPlayerAllowed)
                .executes(ctx -> runForPlayer(ctx, game::openCard))
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> buildTeamAlias(String alias) {
        return Commands.literal(alias)
                .requires(this::isPlayerAllowed)
                .executes(ctx -> runForPlayer(ctx, game::openTeamMenu))
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> buildSetupAlias(String alias) {
        return Commands.literal(alias)
                .requires(this::isAdmin)
                .executes(ctx -> runForPlayer(ctx, game::openSetupMenu))
                .build();
    }

    private boolean isAdmin(CommandSourceStack source) {
        return source.getSender().hasPermission("bingo.admin");
    }

    private boolean isPlayerAllowed(CommandSourceStack source) {
        return source.getSender().hasPermission("bingo.play");
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        Messages messages = plugin.messages();
        CommandSender sender = ctx.getSource().getSender();
        messages.send(sender, "help.header");
        for (String key : PLAYER_HELP) {
            messages.send(sender, "help." + key);
        }
        if (sender.hasPermission("bingo.admin")) {
            for (String key : ADMIN_HELP) {
                messages.send(sender, "help." + key);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int version(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String ver = plugin.updateChecker() != null
                ? plugin.updateChecker().currentVersion().clean()
                : plugin.getPluginMeta().getVersion();
        plugin.messages().send(sender, "version-info",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("current", ver));
        if (isAdmin(ctx.getSource()) && plugin.updateChecker() != null) {
            plugin.updateChecker().check(sender);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int run(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<CommandSender> action) {
        action.accept(ctx.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }

    /** Commands that only work for a player, with a clear line for the console. */
    private int runForPlayer(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<Player> action) {
        if (ctx.getSource().getExecutor() instanceof Player player) {
            action.accept(player);
        } else {
            plugin.messages().send(ctx.getSource().getSender(), "players-only");
        }
        return Command.SINGLE_SUCCESS;
    }

    private CompletableFuture<Suggestions> suggestTeams(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (Team team : game.teams().all()) {
            if (team.name().toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                builder.suggest(team.name());
            }
        }
        return builder.buildFuture();
    }

    private void handleStart(CommandSender sender, BingoGameMode mode, String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) {
            game.startRound(sender, mode, null, null);
            return;
        }
        String trimmed = rawArgs.trim();
        String[] parts = trimmed.split("\\s+", 2);
        String first = parts[0].toLowerCase(java.util.Locale.ROOT);
        Boolean lockout = null;
        String seed = null;

        if (first.startsWith("lockout:")) {
            String val = first.substring("lockout:".length());
            if ("true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val)) {
                lockout = Boolean.TRUE;
            } else if ("false".equalsIgnoreCase(val) || "no".equalsIgnoreCase(val)) {
                lockout = Boolean.FALSE;
            }
        } else if ("true".equalsIgnoreCase(first)) {
            lockout = Boolean.TRUE;
        } else if ("false".equalsIgnoreCase(first)) {
            lockout = Boolean.FALSE;
        }

        if (lockout != null) {
            seed = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : null;
        } else {
            seed = trimmed;
        }

        game.startRound(sender, mode, lockout, seed);
    }

    private CompletableFuture<Suggestions> suggestStartArgs(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String option : List.of("lockout:true", "lockout:false", "true", "false")) {
            if (option.startsWith(remaining)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestMatchIds(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (var summary : plugin.matchHistory().summaries()) {
            if (summary.id().toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                builder.suggest(summary.id());
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestHistory(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        if ("clear".startsWith(remaining)) {
            builder.suggest("clear");
        }
        int totalPages = plugin.matchHistory().totalPages(5);
        for (int p = 1; p <= Math.min(totalPages, 5); p++) {
            String str = String.valueOf(p);
            if (str.startsWith(remaining)) {
                builder.suggest(str);
            }
        }
        for (var summary : plugin.matchHistory().summaries()) {
            if (summary.id().toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                builder.suggest(summary.id());
            }
        }
        return builder.buildFuture();
    }
}
