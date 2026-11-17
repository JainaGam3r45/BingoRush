package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.Messages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.MenuType;
import org.bukkit.persistence.PersistentDataType;

/**
 * The host panel: game mode, win condition, lockout and time limit as toggle
 * icons plus a start button that launches the round with the shown settings.
 * The draft is rebuilt from config.yml on every open, so the panel never
 * hides what a reload changed, and it closes the moment a round starts.
 */
public final class SetupMenu {

    private record OpenMenu(Inventory top) {
    }

    private final BingoRush plugin;
    private final Game game;
    private final Messages messages;
    private final NamespacedKey tag;
    private final NamespacedKey actionKey;
    private final Map<UUID, OpenMenu> openMenus = new HashMap<>();
    private RoundSettings settings;

    public SetupMenu(BingoRush plugin, Game game, NamespacedKey tag) {
        this.plugin = plugin;
        this.game = game;
        this.messages = plugin.messages();
        this.tag = tag;
        this.actionKey = new NamespacedKey(plugin, "setup-menu-action");
    }

    /** True when the stack is one of ours, so its view must stay read only. */
    public boolean isMenuItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
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
        settings = new RoundSettings(config.gameMode(), config.winMode(), config.lockout(), config.timeLimitSeconds());
        InventoryView view = MenuType.GENERIC_9X3.builder()
                .title(messages.render("setup-menu-title"))
                .build(player);
        view.open();
        openMenus.put(player.getUniqueId(), new OpenMenu(view.getTopInventory()));
        refreshAll();
    }

    /** Redraws every open panel with the current draft, after each toggle. */
    public void refreshAll() {
        Iterator<Map.Entry<UUID, OpenMenu>> iterator = openMenus.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, OpenMenu> entry = iterator.next();
            OpenMenu menu = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || player.getOpenInventory().getTopInventory() != menu.top()) {
                iterator.remove();
                continue;
            }
            render(menu.top());
        }
    }

    /** Closes every open panel, for when a round starts. */
    public void closeAll() {
        List<Player> viewers = new ArrayList<>();
        for (Map.Entry<UUID, OpenMenu> entry : openMenus.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getOpenInventory().getTopInventory() == entry.getValue().top()) {
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

    /** Runs the action written in the clicked item; fillers do nothing. */
    public void click(Player player, ItemStack stack) {
        if (settings == null || stack == null || !stack.hasItemMeta()) {
            return;
        }
        String action = stack.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        switch (action) {
            case "mode" -> settings.cycleMode();
            case "win" -> settings.cycleWinMode();
            case "lockout" -> settings.toggleLockout();
            case "time" -> settings.cycleTimeLimit();
            case "start" -> {
                start(player);
                return;
            }
            default -> {
                return;
            }
        }
        game.playSound(player, BingoConfig.SoundId.MENU_CLICK);
        refreshAll();
    }

    private void start(Player player) {
        game.playSound(player, BingoConfig.SoundId.MENU_CLICK);
        // startRound re-checks the round state and closes this panel on success.
        game.startRound(player, settings.mode(), settings.winMode(),
                settings.lockout(), settings.timeLimitSeconds(), null);
    }

    private void render(Inventory top) {
        for (int slot = 0; slot < top.getSize(); slot++) {
            top.setItem(slot, filler());
        }
        top.setItem(10, toggleItem(modeIcon(), "setup-mode-name", modeValue(), false, "mode"));
        top.setItem(12, toggleItem(winIcon(), "setup-win-name", winValue(), false, "win"));
        top.setItem(14, toggleItem(ItemType.SHIELD, "setup-lockout-name", lockoutValue(), settings.lockout(), "lockout"));
        top.setItem(16, toggleItem(ItemType.CLOCK, "setup-time-name", timeValue(), false, "time"));
        top.setItem(22, startItem());
    }

    private ItemStack toggleItem(ItemType icon, String nameKey, Component value, boolean glint, String action) {
        ItemStack stack = icon.createItemStack();
        stack.editMeta(meta -> {
            meta.displayName(messages.render(nameKey));
            meta.lore(List.of(
                    messages.render("menu-cell-separator"),
                    messages.render("setup-lore-value", Placeholder.component("value", value)),
                    messages.render("setup-lore-click")));
            meta.addItemFlags(ItemFlag.values());
            meta.setEnchantmentGlintOverride(glint);
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        });
        return stack;
    }

    private ItemStack startItem() {
        ItemStack stack = ItemType.EMERALD_BLOCK.createItemStack();
        stack.editMeta(meta -> {
            meta.displayName(messages.render("setup-start-name"));
            List<Component> lore = new ArrayList<>();
            lore.add(messages.render("menu-cell-separator"));
            lore.add(summary("setup-start-mode", modeValue()));
            lore.add(summary("setup-start-win", winValue()));
            lore.add(summary("setup-start-lockout", lockoutValue()));
            lore.add(summary("setup-start-time", timeValue()));
            lore.add(messages.render("menu-cell-separator"));
            lore.add(messages.render("setup-start-hint"));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "start");
        });
        return stack;
    }

    private Component summary(String key, Component value) {
        return messages.render(key, Placeholder.component("value", value));
    }

    private ItemType modeIcon() {
        return settings.mode() == BingoGameMode.ITEMS ? ItemType.DIAMOND_PICKAXE : ItemType.NETHER_STAR;
    }

    private ItemType winIcon() {
        return settings.winMode() == WinMode.LINE ? ItemType.TARGET : ItemType.GOLDEN_CARROT;
    }

    private Component modeValue() {
        return messages.render(settings.mode() == BingoGameMode.ITEMS
                ? "setup-value-items" : "setup-value-advancements");
    }

    private Component winValue() {
        return messages.render(settings.winMode() == WinMode.LINE
                ? "setup-value-line" : "setup-value-full");
    }

    private Component lockoutValue() {
        return messages.render(settings.lockout() ? "setup-value-on" : "setup-value-off");
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

    private ItemStack filler() {
        ItemStack stack = ItemType.GRAY_STAINED_GLASS_PANE.createItemStack();
        stack.editMeta(meta -> {
            meta.displayName(Component.empty());
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }
}
