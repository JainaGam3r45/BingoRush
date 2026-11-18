package com.jifercraft.bingo.arena;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.game.BingoGameMode;
import com.jifercraft.bingo.world.MatchWorldManager;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
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
    private static final int[] BORDERS = {0, 500, 1000, 2000, 5000};
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

    public void openArenaList(Player player) {
        ListHolder holder = new ListHolder();
        holder.inventory = Bukkit.createInventory(holder, 54, messages().render("arena-menu.list-title"));
        renderList(holder.inventory);
        player.openInventory(holder.inventory);
    }

    private void renderList(Inventory inv) {
        inv.clear();
        ItemStack filler = filler();
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, filler);
            }
        }
        List<GameMapDefinition> all = arenaManager.getAllArenas();
        for (int i = 0; i < ARENA_SLOTS.length && i < all.size(); i++) {
            inv.setItem(ARENA_SLOTS[i], arenaItem(all.get(i)));
        }
        inv.setItem(45, item(Material.BARRIER, messages().render("arena-menu.close"), List.of()));
        inv.setItem(49, item(Material.EMERALD_BLOCK, messages().render("arena-menu.create-name"),
                messages().renderLore("arena-menu.create-lore")));
        inv.setItem(53, item(Material.BOOK, messages().render("arena-menu.info-name"),
                messages().renderLore("arena-menu.info-lore",
                        Placeholder.unparsed("count", String.valueOf(all.size())),
                        Placeholder.unparsed("max", String.valueOf(ARENA_SLOTS.length)))));
    }

    private ItemStack arenaItem(GameMapDefinition a) {
        List<Component> lore = new ArrayList<>();
        lore.add(messages().render("arena-menu.item-id", Placeholder.unparsed("arena", a.id())));
        lore.add(messages().render("arena-menu.item-team", Placeholder.component("team", teamComponent(a.teamSize()))));
        lore.add(messages().render("arena-menu.item-capacity",
                Placeholder.unparsed("players", String.valueOf(a.maxPlayers())),
                Placeholder.unparsed("teams", String.valueOf(a.maxTeams()))));
        lore.add(messages().render("arena-menu.item-time", Placeholder.component("time", timeComponent(a.timeLimit()))));
        lore.add(messages().render("arena-menu.item-game-mode", Placeholder.component("mode", modeComponent(a.mode()))));
        lore.add(messages().render("arena-menu.item-win", Placeholder.component("win", winComponent(a.winMode()))));
        lore.add(messages().render("arena-menu.item-lockout", Placeholder.component("lockout", lockoutComponent(a.lockout()))));
        lore.add(Component.empty());
        lore.add(messages().render("arena-menu.item-edit"));
        lore.add(messages().render("arena-menu.item-teleport"));
        lore.add(messages().render("arena-menu.item-delete"));
        Material icon = a.teamSize() == 1 ? Material.IRON_SWORD : Material.GOLDEN_CHESTPLATE;
        return item(icon, messages().render("arena-menu.item-name", Placeholder.unparsed("arena", a.name())), lore);
    }

    public void openArenaEdit(Player player, String arenaId) {
        GameMapDefinition arena = arenaManager.getArena(arenaId);
        if (arena == null) {
            messages().send(player, "arena.not-found", Placeholder.unparsed("arena", arenaId));
            return;
        }
        EditHolder holder = new EditHolder(arena.id());
        holder.inventory = Bukkit.createInventory(holder, 36, messages().render("arena-menu.edit-title",
                Placeholder.unparsed("arena", arena.name())));
        renderEdit(holder.inventory, arena);
        player.openInventory(holder.inventory);
    }

    private void renderEdit(Inventory inv, GameMapDefinition a) {
        inv.clear();
        ItemStack filler = filler();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
        inv.setItem(10, item(Material.NAME_TAG, messages().render("arena-menu.name-label",
                Placeholder.unparsed("arena", a.name())),
                messages().renderLore("arena-menu.name-lore", Placeholder.unparsed("id", a.id()))));
        inv.setItem(11, item(Material.WHEAT_SEEDS, messages().render("arena-menu.seed-label",
                Placeholder.unparsed("seed", a.seed())), messages().renderLore("arena-menu.seed-lore")));
        inv.setItem(12, item(Material.DIAMOND, messages().render("arena-menu.game-mode-label",
                Placeholder.component("mode", modeComponent(a.mode()))), messages().renderLore("arena-menu.game-mode-lore")));
        inv.setItem(13, item(Material.NETHER_STAR, messages().render("arena-menu.win-label",
                Placeholder.component("win", winComponent(a.winMode()))), messages().renderLore("arena-menu.win-lore")));
        inv.setItem(14, item(Material.IRON_DOOR, messages().render("arena-menu.lockout-label",
                Placeholder.component("lockout", lockoutComponent(a.lockout()))), messages().renderLore("arena-menu.lockout-lore")));
        inv.setItem(15, item(Material.CLOCK, messages().render("arena-menu.time-label",
                Placeholder.component("time", timeComponent(a.timeLimit()))), messages().renderLore("arena-menu.time-lore")));
        inv.setItem(16, item(Material.TOTEM_OF_UNDYING, messages().render("arena-menu.lives-label",
                Placeholder.component("lives", livesComponent(a.lives()))), messages().renderLore("arena-menu.lives-lore")));
        inv.setItem(19, item(Material.GOLDEN_CHESTPLATE, messages().render("arena-menu.team-label",
                Placeholder.component("team", teamComponent(a.teamSize()))), messages().renderLore("arena-menu.team-lore")));
        inv.setItem(20, item(Material.IRON_HELMET, messages().render("arena-menu.capacity-label",
                Placeholder.unparsed("teams", String.valueOf(a.maxTeams())),
                Placeholder.unparsed("players", String.valueOf(a.maxPlayers()))),
                messages().renderLore("arena-menu.capacity-lore")));
        inv.setItem(21, item(Material.REPEATER, messages().render("arena-menu.countdown-label",
                Placeholder.unparsed("seconds", String.valueOf(a.countdown()))), messages().renderLore("arena-menu.countdown-lore")));
        inv.setItem(22, item(Material.IRON_BARS, messages().render("arena-menu.border-label",
                Placeholder.component("border", borderComponent(a.border()))), messages().renderLore("arena-menu.border-lore")));
        inv.setItem(23, item(Material.BOOKSHELF, messages().render("arena-menu.preset-label"),
                messages().renderLore("arena-menu.preset-lore",
                        Placeholder.unparsed("presets", String.join(", ", arenaManager.presetNames())))));
        boolean fixedLobby = a.waitingLobby() != null && a.waitingLobby().getWorld() != null;
        String lobbyText = fixedLobby
                ? String.format(Locale.ROOT, "%s (%.1f, %.1f, %.1f)", a.waitingLobby().getWorld().getKey(),
                        a.waitingLobby().getX(), a.waitingLobby().getY(), a.waitingLobby().getZ())
                : messages().plainText("arena-menu.location-dynamic");
        inv.setItem(24, item(Material.ENDER_PEARL, messages().render("arena-menu.waiting-label",
                Placeholder.unparsed("location", lobbyText)), messages().renderLore("arena-menu.waiting-lore")));
        String structure = switch (plugin.matchWorldManager().getStructureStatus(a.id())) {
            case "custom" -> a.id() + ".json";
            case "default" -> "default.json";
            default -> messages().plainText("arena-menu.structure-builtin");
        };
        inv.setItem(25, item(Material.STRUCTURE_BLOCK, messages().render("arena-menu.structure-label",
                Placeholder.unparsed("name", structure)),
                messages().renderLore("arena-menu.structure-lore", Placeholder.unparsed("arena", a.id()))));
        inv.setItem(27, item(Material.ARROW, messages().render("arena-menu.back"), List.of()));
        inv.setItem(28, item(Material.IRON_SWORD, messages().render("arena-menu.setting-label",
                Placeholder.component("option", messages().render("arena-option-name.pvp")),
                Placeholder.component("value", valueComponent(ArenaOption.PVP, a.pvp()))),
                messages().renderLore("arena-menu.pvp-lore")));
        inv.setItem(29, item(Material.CHEST, messages().render("arena-menu.setting-label",
                Placeholder.component("option", messages().render("arena-option-name.keep-inventory")),
                Placeholder.component("value", valueComponent(ArenaOption.KEEP_INVENTORY, a.keepInventory()))),
                messages().renderLore("arena-menu.keep-inventory-lore")));
        inv.setItem(30, item(Material.SUNFLOWER, messages().render("arena-menu.setting-label",
                Placeholder.component("option", messages().render("arena-option-name.daylight")),
                Placeholder.component("value", valueComponent(ArenaOption.DAYLIGHT, a.daylight()))),
                messages().renderLore("arena-menu.daylight-lore")));
        boolean selected = plugin.game() != null && plugin.game().isMapSelected(a.id());
        inv.setItem(31, item(selected ? Material.LIME_DYE : Material.GRAY_DYE,
                messages().render(selected ? "arena-menu.selected-name" : "arena-menu.select-name"),
                messages().renderLore("arena-menu.select-lore")));
        inv.setItem(35, item(Material.LAVA_BUCKET, messages().render("arena-menu.delete-name"),
                messages().renderLore("arena-menu.delete-lore")));
    }

    /** How a setting's value reads in menus and votes. */
    public Component valueComponent(ArenaOption option, Object value) {
        return switch (option) {
            case MODE -> modeComponent((BingoGameMode) value);
            case WIN_MODE -> winComponent((WinMode) value);
            case LOCKOUT -> lockoutComponent((Boolean) value);
            case TIME_LIMIT -> timeComponent((Long) value);
            case LIVES -> livesComponent((Integer) value);
            case BORDER -> borderComponent((Integer) value);
            case TEAM_SIZE -> teamComponent((Integer) value);
            case PVP, KEEP_INVENTORY -> messages().render((Boolean) value ? "arena-menu.state-on" : "arena-menu.state-off");
            case DAYLIGHT -> messages().render("arena-menu.daylight-" + value);
            case COUNTDOWN -> messages().render("arena-menu.seconds", Placeholder.unparsed("seconds", String.valueOf(value)));
            default -> Component.text(option.format(value));
        };
    }

    private Component teamComponent(int teamSize) {
        String key = switch (teamSize) {
            case 1 -> "arena-menu.team-solo";
            case 2 -> "arena-menu.team-duo";
            case 3 -> "arena-menu.team-trio";
            case 4 -> "arena-menu.team-squad";
            default -> "arena-menu.team-custom";
        };
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

    private Component borderComponent(int border) {
        if (border <= 0) {
            return messages().render("arena-menu.border-off");
        }
        return messages().render("arena-menu.border-blocks", Placeholder.unparsed("blocks", String.valueOf(border)));
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

    private ItemStack filler() {
        ItemStack stack = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return stack;
    }

    // Components straight on the stack: no ItemMeta round trip, no italic
    // name to undo, and the tooltip shows exactly the lines we put in.
    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.CUSTOM_NAME, name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        List<Component> lines = new ArrayList<>(lore.size());
        for (Component line : lore) {
            lines.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .hiddenComponents(Set.of(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.ENCHANTMENTS))
                .build());
        return stack;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (!(holder instanceof ListHolder) && !(holder instanceof EditHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        if (!player.hasPermission(PERMISSION)) {
            player.closeInventory();
            return;
        }
        if (holder instanceof EditHolder edit) {
            handleEditClick(player, edit.arenaId, event.getSlot(), event.getClick());
        } else {
            handleListClick(player, event.getSlot(), event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
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
            prompt.ask(player, (who, text) -> {
                ArenaManager.Result result = arenaManager.create(text.toLowerCase(Locale.ROOT), null);
                if (!result.ok()) {
                    sendProblem(who, result.problem(), text);
                    return false;
                }
                who.playSound(who.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
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
                player.closeInventory();
                visit(player, arena);
            } else {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openArenaEdit(player, arena.id());
            }
            return;
        }
    }

    private void visit(Player player, GameMapDefinition arena) {
        if (plugin.game() != null && arena.id().equals(plugin.game().roundArenaId())) {
            sendProblem(player, ArenaManager.Problem.IN_USE, arena.id());
            return;
        }
        if (arena.waitingLobby() != null && arena.waitingLobby().getWorld() != null) {
            player.teleport(arena.waitingLobby());
        } else if (!plugin.matchWorldManager().sendToWaitingLobby(player, arena, true)) {
            messages().send(player, "arena.world-unavailable", Placeholder.unparsed("arena", arena.id()));
            return;
        }
        messages().send(player, "arena-menu.teleported", Placeholder.unparsed("arena", arena.name()));
    }

    private void delete(Player player, GameMapDefinition arena) {
        ArenaManager.Result result = arenaManager.delete(arena.id());
        if (!result.ok()) {
            sendProblem(player, result.problem(), arena.id());
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 1f, 1f);
        messages().send(player, "arena.deleted", Placeholder.unparsed("arena", arena.id()));
    }

    private void handleEditClick(Player player, String arenaId, int slot, ClickType click) {
        GameMapDefinition a = arenaManager.getArena(arenaId);
        if (a == null) {
            player.closeInventory();
            return;
        }
        switch (slot) {
            case 10 -> askValue(player, a, ArenaOption.NAME, "arena-menu.ask-name");
            case 11 -> askValue(player, a, ArenaOption.SEED, "arena-menu.ask-seed");
            case 12 -> change(player, a, ArenaOption.MODE,
                    a.mode() == BingoGameMode.ITEMS ? BingoGameMode.ADVANCEMENTS : BingoGameMode.ITEMS);
            case 13 -> change(player, a, ArenaOption.WIN_MODE, a.winMode() == WinMode.LINE ? WinMode.FULL_CARD : WinMode.LINE);
            case 14 -> change(player, a, ArenaOption.LOCKOUT, !a.lockout());
            case 15 -> change(player, a, ArenaOption.TIME_LIMIT, next(TIMES, a.timeLimit(), click.isRightClick()));
            case 16 -> change(player, a, ArenaOption.LIVES, next(LIVES, a.lives(), click.isRightClick()));
            case 19 -> change(player, a, ArenaOption.TEAM_SIZE, next(TEAM_SIZES, a.teamSize(), click.isRightClick()));
            case 20 -> {
                int limit = arenaManager.configuredTeams();
                int teams = Math.max(2, Math.min(limit, a.maxTeams() + (click.isRightClick() ? -1 : 1)));
                change(player, a, ArenaOption.MAX_TEAMS, teams);
            }
            case 21 -> change(player, a, ArenaOption.COUNTDOWN, next(COUNTDOWNS, a.countdown(), click.isRightClick()));
            case 22 -> change(player, a, ArenaOption.BORDER, next(BORDERS, a.border(), click.isRightClick()));
            case 23 -> askPreset(player, a);
            case 24 -> {
                Location here = click.isRightClick() ? null : player.getLocation();
                ArenaManager.Result result = arenaManager.setWaitingLobby(a.id(), here);
                if (!result.ok()) {
                    sendProblem(player, result.problem(), a.id());
                    return;
                }
                messages().send(player, here == null ? "arena-menu.waiting-reset" : "arena-menu.waiting-set");
                refresh(player, result.arena());
            }
            case 25 -> captureStructure(player, a, click);
            case 27 -> openArenaList(player);
            case 28 -> change(player, a, ArenaOption.PVP, !a.pvp());
            case 29 -> change(player, a, ArenaOption.KEEP_INVENTORY, !a.keepInventory());
            case 30 -> change(player, a, ArenaOption.DAYLIGHT, switch (a.daylight()) {
                case "cycle" -> click.isRightClick() ? "night" : "day";
                case "day" -> click.isRightClick() ? "cycle" : "night";
                default -> click.isRightClick() ? "day" : "cycle";
            });
            case 31 -> {
                if (plugin.game() != null && !plugin.game().selectMap(a.id())) {
                    messages().send(player, "arena.select-busy");
                    return;
                }
                messages().send(player, "arena.selected", Placeholder.unparsed("arena", a.id()));
                refresh(player, a);
            }
            case 35 -> {
                if (!click.isShiftClick()) {
                    messages().send(player, "arena-menu.delete-confirm");
                    return;
                }
                delete(player, a);
                openArenaList(player);
            }
            default -> {
            }
        }
    }

    private void change(Player player, GameMapDefinition arena, ArenaOption option, Object value) {
        ArenaManager.Result result = arenaManager.set(arena.id(), option, value);
        if (!result.ok()) {
            sendProblem(player, result.problem(), arena.id());
            return;
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
        refresh(player, result.arena());
    }

    private void refresh(Player player, GameMapDefinition arena) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder(false) instanceof EditHolder) {
            renderEdit(top, arena);
        }
    }

    private void askValue(Player player, GameMapDefinition arena, ArenaOption option, String questionKey) {
        player.closeInventory();
        messages().send(player, questionKey, Placeholder.unparsed("arena", arena.id()));
        prompt.ask(player, (who, text) -> {
            Object value;
            try {
                value = option.parse(text);
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
                    Placeholder.component("option", plugin.messages().render("arena-option-name." + option.key())),
                    Placeholder.component("value", valueComponent(option, value)));
            if (option == ArenaOption.SEED && plugin.matchWorldManager().world(arena.id()) != null) {
                messages().send(who, "arena.seed-after-reset", Placeholder.unparsed("arena", arena.id()));
            }
            openArenaEdit(who, arena.id());
            return true;
        });
    }

    private void askPreset(Player player, GameMapDefinition arena) {
        player.closeInventory();
        messages().send(player, "arena-menu.ask-preset",
                Placeholder.unparsed("presets", String.join(", ", arenaManager.presetNames())));
        prompt.ask(player, (who, text) -> {
            ArenaManager.Result result = arenaManager.applyPreset(arena.id(), text);
            if (!result.ok()) {
                sendProblem(who, result.problem(), text);
                return result.problem() != ArenaManager.Problem.UNKNOWN_PRESET;
            }
            messages().send(who, "arena.preset-applied", Placeholder.unparsed("arena", arena.id()),
                    Placeholder.unparsed("preset", text.toLowerCase(Locale.ROOT)));
            openArenaEdit(who, arena.id());
            return true;
        });
    }

    private void captureStructure(Player player, GameMapDefinition arena, ClickType click) {
        if (click.isRightClick()) {
            List<String> names = plugin.matchWorldManager().listStructures();
            messages().send(player, names.isEmpty() ? "structure.none" : "structure.list",
                    Placeholder.unparsed("names", String.join(", ", names)));
            return;
        }
        sendCapture(player, plugin.matchWorldManager().saveStructure(player.getUniqueId(), arena.id()), arena.id());
    }

    /** Shared with /bingo structure save, so both say the same thing. */
    public void sendCapture(Player player, MatchWorldManager.CaptureResult result, String name) {
        switch (result) {
            case SAVED -> {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                messages().send(player, "structure.saved", Placeholder.unparsed("name", name));
            }
            case MISSING_CORNERS -> messages().send(player, "structure.need-corners");
            case DIFFERENT_WORLDS -> messages().send(player, "structure.different-worlds");
            case TOO_BIG -> messages().send(player, "structure.too-big",
                    Placeholder.unparsed("max", String.valueOf(MatchWorldManager.maxStructureBlocks())));
            case BAD_NAME -> messages().send(player, "structure.bad-name", Placeholder.unparsed("name", name));
        }
    }

    public void sendProblem(CommandSender to, ArenaManager.Problem problem, String value) {
        messages().send(to, problem.messageKey(), Placeholder.unparsed("arena", value),
                Placeholder.unparsed("value", value),
                Placeholder.unparsed("teams", String.valueOf(arenaManager.configuredTeams())),
                Placeholder.unparsed("presets", String.join(", ", arenaManager.presetNames())));
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
