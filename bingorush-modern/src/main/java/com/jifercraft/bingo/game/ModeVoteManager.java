package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.arena.ArenaOption;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.config.Messages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

/**
 * Votes in the waiting room. Any arena setting listed in
 * match-voting.options gets a button; a click moves your vote along the
 * choices, shift-click takes it back. The most voted value wins, and a tie
 * keeps what the arena already had.
 */
public final class ModeVoteManager {

    public static final class VoteMenuHolder implements InventoryHolder {
        private final ModeVoteManager manager;
        private final String arenaId;
        private Inventory inventory;

        VoteMenuHolder(ModeVoteManager manager, String arenaId) {
            this.manager = manager;
            this.arenaId = arenaId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public String arenaId() {
            return arenaId;
        }

        public void handleClick(Player player, int slot, ClickType click) {
            manager.handleClick(player, slot, click, arenaId);
        }
    }

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private final BingoRush plugin;
    // arena id -> setting -> player -> value they voted
    private final Map<String, Map<ArenaOption, Map<UUID, Object>>> votes = new HashMap<>();
    private final Map<UUID, VoteMenuHolder> openMenus = new HashMap<>();

    public ModeVoteManager(BingoRush plugin) {
        this.plugin = plugin;
    }

    private Messages messages() {
        return plugin.messages();
    }

    /** The settings on the ballot, in menu order; seven fit in a row. */
    private List<ArenaOption> ballot() {
        BingoConfig config = plugin.bingoConfig();
        List<ArenaOption> options = config == null ? List.of() : config.votes();
        return options.size() > SLOTS.length ? options.subList(0, SLOTS.length) : options;
    }

    private static List<Object> choices(ArenaOption option) {
        List<Object> choices = new ArrayList<>();
        for (String raw : option.suggestions()) {
            Object value = option.parse(raw);
            if (!choices.contains(value)) {
                choices.add(value);
            }
        }
        return choices;
    }

    private Map<UUID, Object> votesFor(String arenaId, ArenaOption option) {
        return votes.computeIfAbsent(arenaId, id -> new LinkedHashMap<>())
                .computeIfAbsent(option, o -> new HashMap<>());
    }

    public void promptVote(Player player) {
        String arenaId = arenaIdOf(player);
        if (arenaId == null || ballot().isEmpty()) {
            messages().send(player, "vote.none");
            return;
        }
        VoteMenuHolder holder = new VoteMenuHolder(this, arenaId);
        holder.inventory = Bukkit.createInventory(holder, 27, messages().render("vote.title"));
        render(holder.inventory, player, arenaId);
        player.openInventory(holder.inventory);
        openMenus.put(player.getUniqueId(), holder);
    }

    private void render(Inventory inv, Player player, String arenaId) {
        ItemStack filler = ItemType.GRAY_STAINED_GLASS_PANE.createItemStack();
        filler.editMeta(meta -> meta.displayName(Component.empty()));
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
        GameMapDefinition arena = plugin.arenaManager().getArena(arenaId);
        if (arena == null) {
            return;
        }
        List<ArenaOption> ballot = ballot();
        for (int i = 0; i < ballot.size(); i++) {
            ArenaOption option = ballot.get(i);
            Map<UUID, Object> cast = votesFor(arenaId, option);
            Object mine = cast.get(player.getUniqueId());
            Object winning = winner(cast, option, arena.get(option));
            List<Component> lore = new ArrayList<>();
            lore.add(messages().render(mine == null ? "vote.yours-none" : "vote.yours",
                    Placeholder.component("value", value(option, mine))));
            lore.add(Component.empty());
            for (Object choice : choices(option)) {
                long count = cast.values().stream().filter(v -> v.equals(choice)).count();
                lore.add(messages().render(choice.equals(winning) ? "vote.choice-leading" : "vote.choice",
                        Placeholder.component("value", value(option, choice)),
                        Placeholder.unparsed("count", String.valueOf(count))));
            }
            lore.add(Component.empty());
            lore.add(messages().render("vote.how"));
            inv.setItem(SLOTS[i], item(icon(option), messages().render("vote.option",
                    Placeholder.component("option", messages().render("arena-option-name." + option.key()))), lore));
        }
        List<Component> summary = new ArrayList<>();
        for (ArenaOption option : ballot) {
            summary.add(messages().render("vote.summary-line",
                    Placeholder.component("option", messages().render("arena-option-name." + option.key())),
                    Placeholder.component("value", value(option, winner(votesFor(arenaId, option), option, arena.get(option))))));
        }
        inv.setItem(22, item(ItemType.BOOK, messages().render("vote.summary", Placeholder.unparsed("arena", arena.name())),
                summary));
    }

    private Component value(ArenaOption option, Object value) {
        return value == null ? messages().render("vote.nothing") : plugin.arenaMenu().valueComponent(option, value);
    }

    private static ItemStack item(ItemType type, Component name, List<Component> lore) {
        ItemStack stack = type.createItemStack();
        stack.editMeta(meta -> {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            List<Component> lines = new ArrayList<>(lore.size());
            for (Component line : lore) {
                lines.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(lines);
        });
        return stack;
    }

    private static ItemType icon(ArenaOption option) {
        return switch (option) {
            case MODE -> ItemType.DIAMOND_PICKAXE;
            case WIN_MODE -> ItemType.TARGET;
            case TIME_LIMIT -> ItemType.CLOCK;
            case LOCKOUT -> ItemType.SHIELD;
            case LIVES -> ItemType.TOTEM_OF_UNDYING;
            case PVP -> ItemType.IRON_SWORD;
            case KEEP_INVENTORY -> ItemType.CHEST;
            case DAYLIGHT -> ItemType.SUNFLOWER;
            case BORDER -> ItemType.IRON_BARS;
            case COUNTDOWN -> ItemType.REPEATER;
            default -> ItemType.PAPER;
        };
    }

    void handleClick(Player player, int slot, ClickType click, String arenaId) {
        List<ArenaOption> ballot = ballot();
        int index = -1;
        for (int i = 0; i < ballot.size(); i++) {
            if (SLOTS[i] == slot) {
                index = i;
            }
        }
        if (index < 0 || plugin.game().round() != null) {
            return;
        }
        ArenaOption option = ballot.get(index);
        Map<UUID, Object> cast = votesFor(arenaId, option);
        UUID id = player.getUniqueId();
        if (click.isShiftClick()) {
            cast.remove(id);
        } else {
            List<Object> choices = choices(option);
            int current = choices.indexOf(cast.get(id));
            int next = current < 0 ? 0 : Math.floorMod(current + (click.isRightClick() ? -1 : 1), choices.size());
            cast.put(id, choices.get(next));
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);
        refresh(arenaId);
    }

    // Plurality; on a tie the arena's own value wins if it is among the
    // leaders, otherwise the leader listed first.
    private static Object winner(Map<UUID, Object> cast, ArenaOption option, Object fallback) {
        if (cast.isEmpty()) {
            return fallback;
        }
        Map<Object, Integer> counts = new LinkedHashMap<>();
        for (Object choice : choices(option)) {
            counts.put(choice, 0);
        }
        for (Object value : cast.values()) {
            counts.merge(value, 1, Integer::sum);
        }
        int best = counts.values().stream().max(Integer::compare).orElse(0);
        if (Objects.equals(counts.get(fallback), best)) {
            return fallback;
        }
        for (Map.Entry<Object, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == best) {
                return entry.getKey();
            }
        }
        return fallback;
    }

    /** The arena with the winning value of every vote applied. */
    public GameMapDefinition applyVotes(GameMapDefinition arena) {
        if (!plugin.getConfig().getBoolean("match-voting.enabled", true)) {
            return arena;
        }
        GameMapDefinition result = arena;
        for (ArenaOption option : ballot()) {
            result = result.with(option, winner(votesFor(arena.id(), option), option, arena.get(option)));
        }
        return result;
    }

    private void refresh(String arenaId) {
        for (Map.Entry<UUID, VoteMenuHolder> entry : new ArrayList<>(openMenus.entrySet())) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null && entry.getValue().arenaId().equals(arenaId)
                    && viewer.getOpenInventory().getTopInventory().getHolder(false) == entry.getValue()) {
                render(viewer.getOpenInventory().getTopInventory(), viewer, arenaId);
            }
        }
    }

    public void onInventoryClose(UUID playerId) {
        openMenus.remove(playerId);
    }

    public void closeAll() {
        for (UUID id : new ArrayList<>(openMenus.keySet())) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                viewer.closeInventory();
            }
        }
        openMenus.clear();
    }

    public void clearVotes(String arenaId) {
        if (arenaId != null) {
            votes.remove(arenaId);
        }
    }

    public void onPlayerLeave(UUID playerId, String arenaId) {
        openMenus.remove(playerId);
        Map<ArenaOption, Map<UUID, Object>> arenaVotes = votes.get(arenaId);
        if (arenaVotes != null) {
            arenaVotes.values().forEach(cast -> cast.remove(playerId));
            refresh(arenaId);
        }
    }

    private String arenaIdOf(Player player) {
        String arenaId = plugin.matchWorldManager().arenaIdOf(player.getWorld());
        return arenaId != null ? arenaId : plugin.game().selectedMapId();
    }
}
