package com.jifercraft.bingo;

import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.Messages;
import org.bukkit.plugin.java.JavaPlugin;

public final class BingoRush extends JavaPlugin {

    private Messages messages;
    private BingoConfig bingoConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        reloadSettings();
        getLogger().info("BingoRush enabled");
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
     * over a typo.
     */
    public void reloadSettings() {
        reloadConfig();
        messages.reload();
        try {
            bingoConfig = BingoConfig.load(getConfig());
        } catch (IllegalArgumentException e) {
            bingoConfig = null;
            for (String line : e.getMessage().split("\n")) {
                getLogger().severe(line);
            }
            getLogger().severe("BingoRush stays idle until config.yml is fixed, then run /bingo recargar.");
        }
    }
}
