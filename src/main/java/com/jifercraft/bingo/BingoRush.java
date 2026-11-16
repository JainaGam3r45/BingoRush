package com.jifercraft.bingo;

import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.game.BingoCommand;
import com.jifercraft.bingo.game.Game;
import com.jifercraft.bingo.game.Teams;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

public final class BingoRush extends JavaPlugin {

    private Messages messages;
    private BingoConfig bingoConfig;
    private Teams teams;
    private Game game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        loadConfigOrLog();
        teams = new Teams();
        if (bingoConfig != null) {
            teams.reset(bingoConfig.teamDefinitions());
        }
        game = new Game(this, teams);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(new BingoCommand(this, game).build()));
        getLogger().info("BingoRush enabled");
    }

    @Override
    public void onDisable() {
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

    /**
     * Rereads both files. A broken config keeps the previous one and prints
     * every problem to the console, so the round in progress is never lost
     * over a typo. The teams are runtime state and survive a reload.
     */
    public void reloadSettings() {
        reloadConfig();
        messages.reload();
        loadConfigOrLog();
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
