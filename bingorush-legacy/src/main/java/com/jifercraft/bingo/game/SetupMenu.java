package com.jifercraft.bingo.game;

import com.cryptomorin.xseries.XMaterial;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.compat.ServerVersion;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.Messages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * The host panel behind /bingo setup: mode, win condition, lockout, lives and
 * time limit, each a click to cycle, plus a start button that launches the
 * round with the shown settings. One draft for every open panel, so two
 * hosts see each other's changes. Nothing is written back to config.yml.
 */
public final class SetupMenu {

    private final BingoRush plugin;
    private final Game game;
    private final Messages messages;
    private final Map<UUID, Inventory> openMenus = new HashMap<>();
    private RoundSettings settings;

    public SetupMenu(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
        this.messages = plugin.messages();
    }

    public void open(Player player) {
        if (game.round() != null) {
            messages.send(player, "round-already-running");
            return;
        }
        BingoConfig config = plugin.bingoConfig();
        if (config == null) {
            messages.send(player, "round-config-broken");
            return;
        }
        if (settings == null || openMenus.isEmpty()) {
            settings = new RoundSettings(config.gameMode(), config.winMode(), config.lockout(),
                    config.timeLimitSeconds(), config.lives());
        }
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.SETUP);
        Inventory top = Bukkit.createInventory(holder, 27, plugin.inventoryTitle(messages.render("setup-menu-title")));
        holder.attach(top);
        player.openInventory(top);
        openMenus.put(player.getUniqueId(), top);
        refreshAll();
    }

    /** Redraws every open panel with the current draft, after each toggle. */
    public void refreshAll() {
        for (Map.Entry<UUID, Inventory> entry : new ArrayList<>(openMenus.entrySet())) {
            if (Bukkit.getPlayer(entry.getKey()) == null) {
                openMenus.remove(entry.getKey());
                continue;
            }
            render(entry.getValue());
        }
    }

    /** Closes every open panel, for when a round starts. */
    public void closeAll() {
        List<Player> viewers = new ArrayList<>();
        for (UUID playerId : openMenus.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                viewers.add(player);
            }
        }
        openMenus.clear();
        // closeInventory fires the close event, so the map is empty first.
        for (Player player : viewers) {
            player.closeInventory();
        }
    }

    /** Drops the tracking of one viewer, from the close and quit events. */
    public void forget(UUID playerId) {
        openMenus.remove(playerId);
    }

    /** Runs the action behind the clicked slot; fillers do nothing. */
    public void click(Player player, MenuHolder holder, int slot) {
        String action = holder.actionAt(slot);
        if (settings == null || action == null) {
            return;
        }
        switch (action) {
            case "mode":
                // Advancement rounds need the 1.12 advancement system.
                if (ServerVersion.atLeast(12)) {
                    settings.cycleMode();
                }
                break;
            case "win":
                settings.cycleWinMode();
                break;
            case "lockout":
                settings.toggleLockout();
                break;
            case "time":
                settings.cycleTimeLimit();
                break;
            case "lives":
                settings.cycleLives();
                break;
            case "start":
                game.playSound(player, BingoConfig.SoundId.MENU_CLICK);
                // startRound re-checks the round state and closes this panel on success.
                game.startRound(player, settings.mode(), settings.winMode(),
                        settings.lockout(), settings.timeLimitSeconds(), settings.lives(), null);
                return;
            default:
                return;
        }
        game.playSound(player, BingoConfig.SoundId.MENU_CLICK);
        refreshAll();
    }

    private void render(Inventory top) {
        MenuHolder holder = MenuHolder.of(top, MenuHolder.Kind.SETUP);
        if (holder == null) {
            return;
        }
        holder.clearActions();
        for (int slot = 0; slot < top.getSize(); slot++) {
            top.setItem(slot, LegacyItems.item(XMaterial.GRAY_STAINED_GLASS_PANE, " ", null, false));
        }
        boolean items = settings.mode() == BingoGameMode.ITEMS;
        toggle(top, holder, 11, items ? XMaterial.DIAMOND_PICKAXE : XMaterial.NETHER_STAR, "setup-mode-name", modeValue(),
                items ? "setup-mode-desc-items" : "setup-mode-desc-advancements", false, "mode");
        toggle(top, holder, 12, settings.winMode() == WinMode.LINE ? XMaterial.ARROW : XMaterial.GOLDEN_CARROT,
                "setup-win-name", winValue(), winDescription(), false, "win");
        toggle(top, holder, 13, XMaterial.SHIELD.isSupported() ? XMaterial.SHIELD : XMaterial.IRON_CHESTPLATE,
                "setup-lockout-name", lockoutValue(), "setup-lockout-desc", settings.lockout(), "lockout");
        XMaterial livesIcon = settings.lives() == 0 || !XMaterial.TOTEM_OF_UNDYING.isSupported()
                ? XMaterial.GOLDEN_APPLE : XMaterial.TOTEM_OF_UNDYING;
        toggle(top, holder, 14, livesIcon, "setup-lives-name", livesValue(), "setup-lives-desc",
                settings.lives() == 1, "lives");
        toggle(top, holder, 15, XMaterial.CLOCK, "setup-time-name", timeValue(), "setup-time-desc", false, "time");

        List<Component> lore = new ArrayList<>();
        lore.add(messages.render("menu-cell-separator"));
        lore.add(summary("setup-start-mode", modeValue()));
        lore.add(summary("setup-start-win", winValue()));
        lore.add(summary("setup-start-lockout", lockoutValue()));
        lore.add(summary("setup-start-lives", livesValue()));
        lore.add(summary("setup-start-time", timeValue()));
        lore.add(messages.render("menu-cell-separator"));
        lore.add(messages.render("setup-start-hint"));
        top.setItem(22, LegacyItems.item(XMaterial.EMERALD_BLOCK, messages.legacy(messages.render("setup-start-name")),
                messages.legacy(lore), false));
        holder.action(22, "start");
    }

    private void toggle(Inventory top, MenuHolder holder, int slot, XMaterial icon, String nameKey, Component value,
            String descriptionKey, boolean glow, String action) {
        List<Component> lore = new ArrayList<>();
        lore.add(messages.render("menu-cell-separator"));
        lore.add(messages.render("setup-lore-value", Placeholder.component("value", value)));
        lore.add(Component.empty());
        lore.addAll(messages.renderLore(descriptionKey));
        lore.add(messages.render("menu-cell-separator"));
        lore.add(messages.render("setup-lore-click"));
        top.setItem(slot, LegacyItems.item(icon, messages.legacy(messages.render(nameKey)), messages.legacy(lore), glow));
        holder.action(slot, action);
    }

    private Component summary(String key, Component value) {
        return messages.render(key, Placeholder.component("value", value));
    }

    private String winDescription() {
        if (settings.winMode() == WinMode.LINE) {
            return "setup-win-desc-line";
        }
        return settings.lockout() ? "setup-win-desc-full-lockout" : "setup-win-desc-full";
    }

    private Component modeValue() {
        return messages.render(settings.mode() == BingoGameMode.ITEMS ? "setup-value-items" : "setup-value-advancements");
    }

    private Component winValue() {
        return messages.render(settings.winMode() == WinMode.LINE ? "setup-value-line" : "setup-value-full");
    }

    private Component lockoutValue() {
        return messages.render(settings.lockout() ? "setup-value-on" : "setup-value-off");
    }

    private Component livesValue() {
        if (settings.lives() == 0) {
            return messages.render("setup-value-lives-unlimited");
        }
        if (settings.lives() == 1) {
            return messages.render("setup-value-lives-hardcore");
        }
        return messages.render("setup-value-lives", Placeholder.unparsed("lives", String.valueOf(settings.lives())));
    }

    private Component timeValue() {
        long seconds = settings.timeLimitSeconds();
        if (seconds <= 0) {
            return messages.render("setup-time-unlimited");
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        return rest == 0
                ? messages.render("setup-time-minutes", Placeholder.unparsed("minutes", String.valueOf(minutes)))
                : messages.render("setup-time-minutes-seconds",
                        Placeholder.unparsed("minutes", String.valueOf(minutes)),
                        Placeholder.unparsed("seconds", String.valueOf(rest)));
    }
}
