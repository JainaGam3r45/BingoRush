package com.jifercraft.bingo.arena;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XSound;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.game.BingoGameMode;
import com.jifercraft.bingo.game.LegacyItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * /bingo arena: the list of arenas and an editor for each. Toggles and
 * cycles apply on click; names, seeds, new ids and presets are typed in chat.
 * Nothing is written until a value passes the same checks /bingo arena set
 * uses, so backing out of a prompt leaves no half-made arena behind.
 */
public final class ArenaMenu implements Listener {

    public static final String PERMISSION = "bingo.admin.arena";

    private static final class ListHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class EditHolder implements InventoryHolder {
        private final String arenaId;
        private Inventory inventory;

        private EditHolder(String arenaId) {
            this.arenaId = arenaId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int[] ARENA_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final long[] TIMES = {0, 600, 900, 1200, 1800, 2700, 3600};
    private static final int[] LIVES = {0, 1, 2, 3, 5};
    private static final int[] COUNTDOWNS = {0, 5, 10, 15, 30};
    private static final int[] TEAM_SIZES = {1, 2, 3, 4};

    private final BingoRush plugin;
    private final ArenaManager arenaManager;
    private final ChatPrompt prompt;

    public ArenaMenu(BingoRush plugin, ArenaManager arenaManager, ChatPrompt prompt) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.prompt = prompt;
    }

    private Messages messages() {
        return plugin.messages();
    }

    private String text(String key, TagResolver... placeholders) {
        return messages().legacy(messages().render(key, placeholders));
    }

    private String text(Component component) {
        return messages().legacy(component);
    }

    private List<String> lore(String key, TagResolver... placeholders) {
        return messages().legacy(messages().renderLore(key, placeholders));
    }

    public void openArenaList(Player player) {
        ListHolder holder = new ListHolder();
        holder.inventory = Bukkit.createInventory(holder, 54, text("arena-menu.list-title"));
        renderList(holder.inventory);
        player.openInventory(holder.inventory);
    }

    private void renderList(Inventory inv) {
        inv.clear();
        ItemStack filler = LegacyItems.item(XMaterial.GRAY_STAINED_GLASS_PANE, " ", null, false);
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, filler);
            }
        }
        List<GameMapDefinition> all = arenaManager.getAllArenas();
        for (int i = 0; i < ARENA_SLOTS.length && i < all.size(); i++) {
            inv.setItem(ARENA_SLOTS[i], arenaItem(all.get(i)));
        }
        inv.setItem(45, LegacyItems.item(XMaterial.BARRIER, text("arena-menu.close"), null, false));
        inv.setItem(49, LegacyItems.item(XMaterial.EMERALD_BLOCK, text("arena-menu.create-name"),
                lore("arena-menu.create-lore"), false));
        inv.setItem(53, LegacyItems.item(XMaterial.BOOK, text("arena-menu.info-name",
                Placeholder.unparsed("count", String.valueOf(all.size())),
                Placeholder.unparsed("max", String.valueOf(ARENA_SLOTS.length))),
                lore("arena-menu.info-lore"), false));
    }

    private ItemStack arenaItem(GameMapDefinition a) {
        List<String> lore = new ArrayList<>();
        lore.add(text("arena-menu.item-id", Placeholder.unparsed("arena", a.id())));
        lore.add(text("arena-menu.item-team", Placeholder.component("team", teamComponent(a.teamSize()))));
        lore.add(text("arena-menu.item-capacity",
                Placeholder.unparsed("players", String.valueOf(a.maxPlayers())),
                Placeholder.unparsed("teams", String.valueOf(a.maxTeams()))));
        lore.add(text("arena-menu.item-time", Placeholder.component("time", timeComponent(a.timeLimit()))));
        lore.add(text("arena-menu.item-game-mode", Placeholder.component("mode", modeComponent(a.mode()))));
        lore.add(text("arena-menu.item-win", Placeholder.component("win", winComponent(a.winMode()))));
        lore.add(text("arena-menu.item-lockout", Placeholder.component("lockout", lockoutComponent(a.lockout()))));
        lore.add("");
        lore.add(text("arena-menu.item-edit"));
        lore.add(text("arena-menu.item-teleport"));
        lore.add(text("arena-menu.item-delete"));
        XMaterial icon = a.teamSize() == 1 ? XMaterial.IRON_SWORD : XMaterial.GOLDEN_CHESTPLATE;
        return LegacyItems.item(icon, text("arena-menu.item-name", Placeholder.unparsed("arena", a.name())), lore, false);
    }

    public void openArenaEdit(Player player, String arenaId) {
        GameMapDefinition arena = arenaManager.getArena(arenaId);
        if (arena == null) {
            messages().send(player, "arena.not-found", Placeholder.unparsed("arena", arenaId));
            return;
        }
        EditHolder holder = new EditHolder(arena.id());
        holder.inventory = Bukkit.createInventory(holder, 36, text("arena-menu.edit-title",
                Placeholder.unparsed("arena", arena.name())));
        renderEdit(holder.inventory, arena);
        player.openInventory(holder.inventory);
    }

    private void renderEdit(Inventory inv, GameMapDefinition a) {
        inv.clear();
        ItemStack filler = LegacyItems.item(XMaterial.GRAY_STAINED_GLASS_PANE, " ", null, false);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
        inv.setItem(10, LegacyItems.item(XMaterial.NAME_TAG, text("arena-menu.name-label",
                Placeholder.unparsed("arena", a.name())), lore("arena-menu.name-lore", Placeholder.unparsed("id", a.id())), false));
        inv.setItem(11, LegacyItems.item(XMaterial.WHEAT_SEEDS, text("arena-menu.seed-label",
                Placeholder.unparsed("seed", a.seed())), lore("arena-menu.seed-lore"), false));
        inv.setItem(12, LegacyItems.item(XMaterial.DIAMOND, text("arena-menu.game-mode-label",
                Placeholder.component("mode", modeComponent(a.mode()))), lore("arena-menu.game-mode-lore"), false));
        inv.setItem(13, LegacyItems.item(XMaterial.NETHER_STAR, text("arena-menu.win-label",
                Placeholder.component("win", winComponent(a.winMode()))), lore("arena-menu.win-lore"), false));
        inv.setItem(14, LegacyItems.item(XMaterial.IRON_DOOR, text("arena-menu.lockout-label",
                Placeholder.component("lockout", lockoutComponent(a.lockout()))), lore("arena-menu.lockout-lore"), false));
        inv.setItem(15, LegacyItems.item(XMaterial.CLOCK, text("arena-menu.time-label",
                Placeholder.component("time", timeComponent(a.timeLimit()))), lore("arena-menu.time-lore"), false));
        inv.setItem(16, LegacyItems.item(XMaterial.GOLDEN_APPLE, text("arena-menu.lives-label",
                Placeholder.component("lives", livesComponent(a.lives()))), lore("arena-menu.lives-lore"), false));
        inv.setItem(19, LegacyItems.item(XMaterial.GOLDEN_CHESTPLATE, text("arena-menu.team-label",
                Placeholder.component("team", teamComponent(a.teamSize()))), lore("arena-menu.team-lore"), false));
        inv.setItem(20, LegacyItems.item(XMaterial.IRON_HELMET, text("arena-menu.capacity-label",
                Placeholder.unparsed("teams", String.valueOf(a.maxTeams())),
                Placeholder.unparsed("players", String.valueOf(a.maxPlayers()))), lore("arena-menu.capacity-lore"), false));
        inv.setItem(21, LegacyItems.item(XMaterial.REPEATER, text("arena-menu.countdown-label",
                Placeholder.unparsed("seconds", String.valueOf(a.countdown()))), lore("arena-menu.countdown-lore"), false));
        inv.setItem(23, LegacyItems.item(XMaterial.BOOKSHELF, text("arena-menu.preset-label"),
                lore("arena-menu.preset-lore", Placeholder.unparsed("presets", joinPresets())), false));
        String lobbyText = a.waitingLobby() != null && a.waitingLobby().getWorld() != null
                ? String.format(Locale.ROOT, "%s (%.1f, %.1f, %.1f)", a.waitingLobby().getWorld().getName(),
                        a.waitingLobby().getX(), a.waitingLobby().getY(), a.waitingLobby().getZ())
                : messages().plainText("arena-menu.location-unset");
        inv.setItem(24, LegacyItems.item(XMaterial.ENDER_PEARL, text("arena-menu.waiting-label",
                Placeholder.unparsed("location", lobbyText)), lore("arena-menu.waiting-lore"), false));
        inv.setItem(27, LegacyItems.item(XMaterial.ARROW, text("arena-menu.back"), null, false));
        boolean selected = plugin.game() != null && plugin.game().isMapSelected(a.id());
        inv.setItem(31, LegacyItems.item(selected ? XMaterial.LIME_DYE : XMaterial.GRAY_DYE,
                text(selected ? "arena-menu.selected-name" : "arena-menu.select-name"),
                lore("arena-menu.select-lore"), selected));
        inv.setItem(35, LegacyItems.item(XMaterial.LAVA_BUCKET, text("arena-menu.delete-name"),
                lore("arena-menu.delete-lore"), false));
    }

    private String joinPresets() {
        StringBuilder names = new StringBuilder();
        for (String name : arenaManager.presetNames()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(name);
        }
        return names.toString();
    }

    public Component valueComponent(ArenaOption option, Object value) {
        switch (option) {
            case MODE:
                return modeComponent((BingoGameMode) value);
            case WIN_MODE:
                return winComponent((WinMode) value);
            case LOCKOUT:
                return lockoutComponent((Boolean) value);
            case TIME_LIMIT:
                return timeComponent((Long) value);
            case LIVES:
                return livesComponent((Integer) value);
            case TEAM_SIZE:
                return teamComponent((Integer) value);
            case COUNTDOWN:
                return messages().render("arena-menu.seconds", Placeholder.unparsed("seconds", String.valueOf(value)));
            default:
                return Component.text(option.format(value));
        }
    }

    private Component teamComponent(int teamSize) {
        String key;
        switch (teamSize) {
            case 1:
                key = "arena-menu.team-solo";
                break;
            case 2:
                key = "arena-menu.team-duo";
                break;
            case 3:
                key = "arena-menu.team-trio";
                break;
            case 4:
                key = "arena-menu.team-squad";
                break;
            default:
                key = "arena-menu.team-custom";
        }
        return messages().render(key, Placeholder.unparsed("size", String.valueOf(teamSize)));
    }

    private Component timeComponent(long seconds) {
        if (seconds <= 0) {
            return messages().render("arena-menu.time-unlimited");
        }
        return messages().render("arena-menu.time-minutes", Placeholder.unparsed("minutes", String.valueOf(seconds / 60)));
    }

    private Component livesComponent(int lives) {
        if (lives <= 0) {
            return messages().render("arena-menu.lives-unlimited");
        }
        return messages().render(lives == 1 ? "arena-menu.lives-hardcore" : "arena-menu.lives-count",
                Placeholder.unparsed("lives", String.valueOf(lives)));
    }

    private Component modeComponent(BingoGameMode mode) {
        return messages().render(mode == BingoGameMode.ITEMS ? "arena-menu.mode-items" : "arena-menu.mode-advancements");
    }

    private Component winComponent(WinMode winMode) {
        return messages().render(winMode == WinMode.LINE ? "arena-menu.win-line" : "arena-menu.win-full");
    }

    private Component lockoutComponent(boolean lockout) {
        return messages().render(lockout ? "arena-menu.lockout-on" : "arena-menu.lockout-off");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof ListHolder) && !(holder instanceof EditHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player) || event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!player.hasPermission(PERMISSION)) {
            player.closeInventory();
            return;
        }
        if (holder instanceof EditHolder) {
            handleEditClick(player, ((EditHolder) holder).arenaId, event.getSlot(), event.getClick());
        } else {
            handleListClick(player, event.getSlot(), event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ListHolder || holder instanceof EditHolder) {
            event.setCancelled(true);
        }
    }

    private void handleListClick(Player player, int slot, ClickType click) {
        if (slot == 45) {
            player.closeInventory();
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            messages().send(player, "arena-menu.ask-id");
            prompt.ask(player, (who, typed) -> {
                ArenaManager.Result result = arenaManager.create(typed.toLowerCase(Locale.ROOT), null);
                if (!result.ok()) {
                    sendProblem(who, result.problem(), typed);
                    return false;
                }
                XSound.ENTITY_PLAYER_LEVELUP.play(who, 1f, 1.2f);
                messages().send(who, "arena.created", Placeholder.unparsed("arena", result.arena().id()));
                openArenaEdit(who, result.arena().id());
                return true;
            });
            return;
        }
        List<GameMapDefinition> all = arenaManager.getAllArenas();
        for (int i = 0; i < ARENA_SLOTS.length && i < all.size(); i++) {
            if (ARENA_SLOTS[i] != slot) {
                continue;
            }
            GameMapDefinition arena = all.get(i);
            if (click == ClickType.SHIFT_RIGHT) {
                delete(player, arena);
                renderList(player.getOpenInventory().getTopInventory());
            } else if (click.isRightClick()) {
                if (arena.waitingLobby() == null || arena.waitingLobby().getWorld() == null) {
                    messages().send(player, "arena-menu.waiting-missing");
                    return;
                }
                player.closeInventory();
                player.teleport(arena.waitingLobby());
                messages().send(player, "arena-menu.teleported", Placeholder.unparsed("arena", arena.name()));
            } else {
                XSound.UI_BUTTON_CLICK.play(player, 1f, 1f);
                openArenaEdit(player, arena.id());
            }
            return;
        }
    }

    private void delete(Player player, GameMapDefinition arena) {
        ArenaManager.Result result = arenaManager.delete(arena.id());
        if (!result.ok()) {
            sendProblem(player, result.problem(), arena.id());
            return;
        }
        XSound.BLOCK_LAVA_EXTINGUISH.play(player, 1f, 1f);
        messages().send(player, "arena.deleted", Placeholder.unparsed("arena", arena.id()));
    }

    private void handleEditClick(Player player, String arenaId, int slot, ClickType click) {
        GameMapDefinition a = arenaManager.getArena(arenaId);
        if (a == null) {
            player.closeInventory();
            return;
        }
        boolean back = click.isRightClick();
        switch (slot) {
            case 10:
                askValue(player, a, ArenaOption.NAME, "arena-menu.ask-name");
                break;
            case 11:
                askValue(player, a, ArenaOption.SEED, "arena-menu.ask-seed");
                break;
            case 12:
                change(player, a, ArenaOption.MODE,
                        a.mode() == BingoGameMode.ITEMS ? BingoGameMode.ADVANCEMENTS : BingoGameMode.ITEMS);
                break;
            case 13:
                change(player, a, ArenaOption.WIN_MODE, a.winMode() == WinMode.LINE ? WinMode.FULL_CARD : WinMode.LINE);
                break;
            case 14:
                change(player, a, ArenaOption.LOCKOUT, !a.lockout());
                break;
            case 15:
                change(player, a, ArenaOption.TIME_LIMIT, next(TIMES, a.timeLimit(), back));
                break;
            case 16:
                change(player, a, ArenaOption.LIVES, next(LIVES, a.lives(), back));
                break;
            case 19:
                change(player, a, ArenaOption.TEAM_SIZE, next(TEAM_SIZES, a.teamSize(), back));
                break;
            case 20:
                int limit = arenaManager.configuredTeams();
                change(player, a, ArenaOption.MAX_TEAMS, Math.max(2, Math.min(limit, a.maxTeams() + (back ? -1 : 1))));
                break;
            case 21:
                change(player, a, ArenaOption.COUNTDOWN, next(COUNTDOWNS, a.countdown(), back));
                break;
            case 23:
                askPreset(player, a);
                break;
            case 24:
                Location here = back ? null : player.getLocation();
                ArenaManager.Result result = arenaManager.setWaitingLobby(a.id(), here);
                if (!result.ok()) {
                    sendProblem(player, result.problem(), a.id());
                    return;
                }
                messages().send(player, here == null ? "arena-menu.waiting-cleared" : "arena-menu.waiting-set");
                refresh(player, result.arena());
                break;
            case 27:
                openArenaList(player);
                break;
            case 31:
                if (plugin.game() != null && !plugin.game().selectMap(a.id())) {
                    messages().send(player, "arena.select-busy");
                    return;
                }
                messages().send(player, "arena.selected", Placeholder.unparsed("arena", a.id()));
                refresh(player, a);
                break;
            case 35:
                if (!click.isShiftClick()) {
                    messages().send(player, "arena-menu.delete-confirm");
                    return;
                }
                delete(player, a);
                openArenaList(player);
                break;
            default:
                break;
        }
    }

    private void change(Player player, GameMapDefinition arena, ArenaOption option, Object value) {
        ArenaManager.Result result = arenaManager.set(arena.id(), option, value);
        if (!result.ok()) {
            sendProblem(player, result.problem(), arena.id());
            return;
        }
        XSound.UI_BUTTON_CLICK.play(player, 1f, 1.2f);
        refresh(player, result.arena());
    }

    private void refresh(Player player, GameMapDefinition arena) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof EditHolder) {
            renderEdit(top, arena);
        }
    }

    private void askValue(Player player, final GameMapDefinition arena, final ArenaOption option, String questionKey) {
        player.closeInventory();
        messages().send(player, questionKey, Placeholder.unparsed("arena", arena.id()));
        prompt.ask(player, (who, typed) -> {
            Object value;
            try {
                value = option.parse(typed);
            } catch (InvalidArenaValue e) {
                sendInvalid(who, e);
                return false;
            }
            ArenaManager.Result result = arenaManager.set(arena.id(), option, value);
            if (!result.ok()) {
                sendProblem(who, result.problem(), arena.id());
                return true;
            }
            messages().send(who, "arena.updated", Placeholder.unparsed("arena", arena.id()),
                    Placeholder.component("option", messages().render("arena-option-name." + option.key())),
                    Placeholder.component("value", valueComponent(option, value)));
            openArenaEdit(who, arena.id());
            return true;
        });
    }

    private void askPreset(Player player, final GameMapDefinition arena) {
        player.closeInventory();
        messages().send(player, "arena-menu.ask-preset", Placeholder.unparsed("presets", joinPresets()));
        prompt.ask(player, (who, typed) -> {
            ArenaManager.Result result = arenaManager.applyPreset(arena.id(), typed);
            if (!result.ok()) {
                sendProblem(who, result.problem(), typed);
                return result.problem() != ArenaManager.Problem.UNKNOWN_PRESET;
            }
            messages().send(who, "arena.preset-applied", Placeholder.unparsed("arena", arena.id()),
                    Placeholder.unparsed("preset", typed.toLowerCase(Locale.ROOT)));
            openArenaEdit(who, arena.id());
            return true;
        });
    }

    public void sendProblem(CommandSender to, ArenaManager.Problem problem, String value) {
        messages().send(to, problem.messageKey(), Placeholder.unparsed("arena", value),
                Placeholder.unparsed("value", value),
                Placeholder.unparsed("teams", String.valueOf(arenaManager.configuredTeams())),
                Placeholder.unparsed("presets", joinPresets()));
    }

    public void sendInvalid(CommandSender to, InvalidArenaValue e) {
        messages().send(to, "arena-option." + e.option().key(), Placeholder.unparsed("value", e.value()));
    }

    private static long next(long[] values, long current, boolean back) {
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                index = i;
                break;
            }
        }
        return values[Math.floorMod(index + (back ? -1 : 1), values.length)];
    }

    private static int next(int[] values, int current, boolean back) {
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                index = i;
                break;
            }
        }
        return values[Math.floorMod(index + (back ? -1 : 1), values.length)];
    }
}
