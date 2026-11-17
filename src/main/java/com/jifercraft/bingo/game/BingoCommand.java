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

    private static final List<String> PLAYER_HELP = List.of("join", "card", "leave");
    private static final List<String> ADMIN_HELP = List.of(
            "start", "stop", "pause", "resume", "reroll", "teams", "reload");

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
                .then(Commands.literal("card")
                        .requires(this::isPlayerAllowed)
                        .executes(ctx -> runForPlayer(ctx, game::openCard)))
                .then(Commands.literal("start")
                        .requires(this::isAdmin)
                        .executes(ctx -> run(ctx, sender -> game.startRound(sender, null)))
                        .then(Commands.argument("seed", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, sender -> game.startRound(sender,
                                        StringArgumentType.getString(ctx, "seed"))))))
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
                        .requires(this::isAdmin)
                        .executes(ctx -> run(ctx, game::listTeams))
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("color", ArgumentTypes.hexColor())
                                                .executes(ctx -> run(ctx, sender -> game.createTeam(sender,
                                                        StringArgumentType.getString(ctx, "name"),
                                                        ctx.getArgument("color", TextColor.class)))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(this::suggestTeams)
                                        .executes(ctx -> run(ctx, sender -> game.removeTeam(sender,
                                                StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("join")
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
                                .executes(ctx -> run(ctx, game::clearTeams))))
                .build();
    }

    public LiteralCommandNode<CommandSourceStack> buildCardAlias(String alias) {
        return Commands.literal(alias)
                .requires(this::isPlayerAllowed)
                .executes(ctx -> runForPlayer(ctx, game::openCard))
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
}
