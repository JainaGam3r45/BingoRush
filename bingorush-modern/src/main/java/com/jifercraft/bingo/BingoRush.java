package com.jifercraft.bingo;

import com.jifercraft.bingo.api.BingoRushAPI;
import com.jifercraft.bingo.compat.BingoRushExpansion;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.ConfigUpdater;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.game.BingoCommand;
import com.jifercraft.bingo.game.Game;
import com.jifercraft.bingo.game.RoundListener;
import com.jifercraft.bingo.game.SignManager;
import com.jifercraft.bingo.game.Teams;
import com.jifercraft.bingo.history.MatchHistory;
import com.jifercraft.bingo.updater.UpdateChecker;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class BingoRush extends JavaPlugin {

    private Messages messages;
    private BingoConfig bingoConfig;
    private Teams teams;
    private Game game;
    private UpdateChecker updateChecker;
    private MatchHistory matchHistory;

    @Override
    public void onEnable() {
        boolean spanishBefore = Messages.migrateOldMessages(this);
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
        } else {
            ConfigUpdater.update(this, "config.yml");
        }
        if (spanishBefore) {
            // The updater just added language: en. A server that already ran
            // in Spanish should not wake up in English after an update.
            keepSpanish(configFile);
        }
        messages = new Messages(this);
        loadConfigOrLog();
        teams = new Teams();
        if (bingoConfig != null) {
            teams.reset(bingoConfig.teamDefinitions());
        }
        File matchesDir = new File(getDataFolder(), "data/matches");
        matchHistory = new MatchHistory(matchesDir, getLogger());
        game = new Game(this, teams);
        game.loadSavedRoundIfPresent();
        game.display().applyAll(teams.all());

        SignManager signManager = new SignManager(this, game);
        game.signManager(signManager);
        getServer().getPluginManager().registerEvents(signManager, this);
        signManager.updateAll();

        getServer().getPluginManager().registerEvents(new RoundListener(game), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            BingoCommand bingoCmd = new BingoCommand(this, game);
            event.registrar().register(bingoCmd.build(), "Bingo minigame", List.of("b"));
            event.registrar().register(bingoCmd.buildCardAlias("card"));
            event.registrar().register(bingoCmd.buildTeamAlias("teams"));
            event.registrar().register(bingoCmd.buildSetupAlias("setup"));
        });

        getServer().getServicesManager().register(
                BingoRushAPI.class,
                new BingoRushAPI() {
                    @Override
                    public boolean isRoundRunning() {
                        return game.round() != null;
                    }

                    @Override
                    public Optional<com.jifercraft.bingo.game.Round> getActiveRound() {
                        return Optional.ofNullable(game.round());
                    }

                    @Override
                    public Optional<com.jifercraft.bingo.game.Team> getPlayerTeam(UUID playerId) {
                        return Optional.ofNullable(game.teams().of(playerId));
                    }

                    @Override
                    public boolean isPlayerInGame(UUID playerId) {
                        return game.round() != null && game.teams().of(playerId) != null;
                    }

                    @Override
                    public Collection<com.jifercraft.bingo.game.Team> getTeams() {
                        return game.teams().all();
                    }

                    @Override
                    public Location getLobbyLocation() {
                        return bingoConfig != null ? bingoConfig.lobbyLocation() : null;
                    }
                },
                this,
                ServicePriority.Normal
        );

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new BingoRushExpansion(this, game).register();
        }

        updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        if (bingoConfig == null || bingoConfig.updateNotifierEnabled()) {
            updateChecker.check();
            updateChecker.startPeriodicCheck();
        }

        messages.console("Enabled <hl>v<version></hl> <muted>(<mode>, <teams> teams)</muted>",
                Placeholder.unparsed("version", getPluginMeta().getVersion()),
                Placeholder.unparsed("mode", bingoConfig == null ? "config broken"
                        : bingoConfig.gameMode().name().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("teams", String.valueOf(teams.all().size())));
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            updateChecker.stopPeriodicCheck();
        }
        if (game != null) {
            game.shutdown();
        }
        if (matchHistory != null) {
            matchHistory.close();
        }
        if (messages != null) {
            messages.console("Disabled");
        }
    }

    public Messages messages() {
        return messages;
    }

    /** Null when the config has problems; a round cannot start until it is fixed. */
    public BingoConfig bingoConfig() {
        return bingoConfig;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }

    public MatchHistory matchHistory() {
        return matchHistory;
    }

    /**
     * Rereads both files. A broken config keeps the previous one and prints
     * every problem to the console, so the round in progress is never lost
     * over a typo. The teams are runtime state and survive a reload.
     */
    public void reloadSettings() {
        ConfigUpdater.update(this, "config.yml");
        reloadConfig();
        messages.reload();
        loadConfigOrLog();
        game.settingsReloaded();
        if (updateChecker != null) {
            if (bingoConfig == null || bingoConfig.updateNotifierEnabled()) {
                updateChecker.check();
                updateChecker.startPeriodicCheck();
            } else {
                updateChecker.stopPeriodicCheck();
            }
        }
    }

    private void keepSpanish(File configFile) {
        try {
            String text = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            Files.writeString(configFile.toPath(),
                    text.replaceFirst("(?m)^language:.*$", "language: \"es\""), StandardCharsets.UTF_8);
            reloadConfig();
            getLogger().info("Moved messages.yml to lang/messages_es.yml and set language: es");
        } catch (IOException e) {
            getLogger().warning("Could not set language: es in config.yml: " + e.getMessage());
        }
    }

    private void loadConfigOrLog() {
        try {
            bingoConfig = BingoConfig.load(getConfig());
        } catch (IllegalArgumentException e) {
            for (String line : e.getMessage().split("\n")) {
                getLogger().severe(line);
            }
            getLogger().severe(bingoConfig == null
                    ? "BingoRush stays idle until config.yml is fixed, then run /bingo reload."
                    : "BingoRush keeps the previous config until config.yml is fixed, then run /bingo reload.");
        }
    }
}
