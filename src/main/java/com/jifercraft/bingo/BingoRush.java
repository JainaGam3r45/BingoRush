package com.jifercraft.bingo;

import org.bukkit.plugin.java.JavaPlugin;

public final class BingoRush extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("BingoRush enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("BingoRush disabled");
    }
}
