package com.jifercraft.bingo;

import com.jifercraft.bingo.compat.ServerVersion;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.ConfigUpdater;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.game.BingoCommand;
import com.jifercraft.bingo.game.Game;
import com.jifercraft.bingo.game.RoundListener;
import com.jifercraft.bingo.game.Teams;
import com.jifercraft.bingo.history.MatchHistory;
import com.jifercraft.bingo.updater.UpdateChecker;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.PluginCommand;
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
        ServerVersion.detect();
        if (ServerVersion.isPastLegacyRange()) {
            getLogger().warning("This server runs " + ServerVersion.describe()
                    + ". The Legacy jar covers 1.8.8 to 1.21.x; use BingoRush-Modern for this version.");
        }
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
        teams = new Teams();
        File matchesDir = new File(getDataFolder(), "data/matches");
        matchHistory = new MatchHistory(matchesDir, getLogger());
        // The game sets up the advancement hooks, which the config check uses
        // to tell real advancements from typos.
        game = new Game(this, teams);
        loadConfigOrLog();
        if (bingoConfig != null) {
            teams.reset(bingoConfig.teamDefinitions());
        }
        game.loadSavedRoundIfPresent();
        game.display().applyAll(teams.all());
        getServer().getPluginManager().registerEvents(new RoundListener(this, game), this);
        BingoCommand commands = new BingoCommand(this, game);
        for (String name : new String[]{"bingo", "card", "teams", "setup"}) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(commands);
                command.setTabCompleter(commands);
            }
        }

        updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        if (bingoConfig == null || bingoConfig.updateNotifierEnabled()) {
            updateChecker.check(null);
            updateChecker.startPeriodicCheck();
        }

        messages.console("Enabled <hl>v<version></hl> <muted>(<mode>, <teams> teams, Minecraft <server>)</muted>",
                Placeholder.unparsed("version", getDescription().getVersion()),
                Placeholder.unparsed("mode", bingoConfig == null ? "config broken"
                        : bingoConfig.gameMode().name().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("teams", String.valueOf(teams.all().size())),
                Placeholder.unparsed("server", ServerVersion.describe()));
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
            messages.close();
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
     * An inventory title. Before 1.13 titles stop at 32 characters, color
     * codes included, which a gradient title passes easily; those servers get
     * the plain text instead.
     */
    public String inventoryTitle(Component title) {
        String legacy = messages.legacy(title);
        if (ServerVersion.atLeast(13) || legacy.length() <= 32) {
            return legacy;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(title);
        return plain.length() <= 32 ? plain : plain.substring(0, 32);
    }

    /**
     * A sidebar title. Before 1.13 it stops at 32 characters, from 1.13 at
     * 128; a gradient that does not fit falls back to plain text.
     */
    public String objectiveTitle(Component title) {
        int max = ServerVersion.atLeast(13) ? 128 : 32;
        String legacy = messages.legacy(title);
        if (legacy.length() <= max) {
            return legacy;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(title);
        return plain.length() <= max ? plain : plain.substring(0, max);
    }

    /**
     * Rereads both files. A broken config keeps the previous one and prints
     * every problem to the console, so the round in progress is never lost
     * over a typo. The teams are runtime state and survive a reload.
     */
    public void reloadSettings() {
        ConfigUpdater.update(this, "config.yml");
        reloadConfig();
        game.cardsBeforeReload();
        messages.reload();
        game.cardTextChanged();
        loadConfigOrLog();
        game.settingsReloaded();
        if (updateChecker != null) {
            if (bingoConfig == null || bingoConfig.updateNotifierEnabled()) {
                updateChecker.check(null);
                updateChecker.startPeriodicCheck();
            } else {
                updateChecker.stopPeriodicCheck();
            }
        }
    }

    private void keepSpanish(File configFile) {
        try {
            String text = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            Files.write(configFile.toPath(),
                    text.replaceFirst("(?m)^language:.*$", "language: \"es\"").getBytes(StandardCharsets.UTF_8));
            reloadConfig();
            getLogger().info("Moved messages.yml to lang/messages_es.yml and set language: es");
        } catch (IOException e) {
            getLogger().warning("Could not set language: es in config.yml: " + e.getMessage());
        }
    }

    private void loadConfigOrLog() {
        try {
            bingoConfig = BingoConfig.load(getConfig());
            for (String note : bingoConfig.notes()) {
                getLogger().info(note);
            }
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
