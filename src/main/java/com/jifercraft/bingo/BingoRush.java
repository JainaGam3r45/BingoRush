package com.jifercraft.bingo;

import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.ConfigUpdater;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.game.BingoCommand;
import com.jifercraft.bingo.game.Game;
import com.jifercraft.bingo.game.RoundListener;
import com.jifercraft.bingo.game.Teams;
import com.jifercraft.bingo.history.MatchHistory;
import com.jifercraft.bingo.updater.UpdateChecker;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
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
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
        } else {
            ConfigUpdater.update(this, "config.yml");
        }
        messages = new Messages(this);
        loadConfigOrLog();
        teams = new Teams();
        if (bingoConfig != null) {
            teams.reset(bingoConfig.teamDefinitions());
        }
        File matchesDir = new File(getDataFolder(), "data/matches");
        matchHistory = new MatchHistory(matchesDir, this);
        game = new Game(this, teams);
        game.loadSavedRoundIfPresent();
        getServer().getPluginManager().registerEvents(new RoundListener(game), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            BingoCommand bingoCmd = new BingoCommand(this, game);
            event.registrar().register(bingoCmd.build(), "Bingo minigame", java.util.List.of("b"));
            event.registrar().register(bingoCmd.buildCardAlias("card"));
        });

        updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        if (bingoConfig == null || bingoConfig.updateNotifierEnabled()) {
            updateChecker.check();
            updateChecker.startPeriodicCheck();
        }

        getLogger().info("BingoRush enabled");
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            updateChecker.stopPeriodicCheck();
        }
        if (game != null) {
            game.shutdown();
        }
        getLogger().info("BingoRush disabled");
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
        if (updateChecker != null) {
            if (bingoConfig == null || bingoConfig.updateNotifierEnabled()) {
                updateChecker.check();
                updateChecker.startPeriodicCheck();
            } else {
                updateChecker.stopPeriodicCheck();
            }
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
