package com.jifercraft.bingo.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.Messages;
import io.papermc.paper.advancement.AdvancementDisplay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemType;

/**
 * The round card as its own tab in the advancements screen. A root that
 * every participant gets, so the tab shows up, and one child per cell that
 * is marked done for the members of the team that claimed it.
 *
 * <p>Advancements can be added while the server runs but not taken out
 * without reloading every datapack, so each round gets fresh keys and the old
 * ones stay loaded, hidden, until the next restart. Hidden because nobody
 * keeps their root: a tab whose root is not done is not drawn.
 */
final class CardAdvancements {

    private static final String CRITERION = "claimed";
    private static final String BACKGROUND = "minecraft:block/yellow_concrete";

    private final BingoRush plugin;
    private final List<Advancement> stale = new ArrayList<>();
    private Advancement root;
    private Advancement[] cells;

    CardAdvancements(BingoRush plugin) {
        this.plugin = plugin;
    }

    boolean isOurs(Advancement advancement) {
        return advancement.getKey().getNamespace().equals(new NamespacedKey(plugin, "card").getNamespace());
    }

    boolean isLoaded() {
        return root != null;
    }

    /** Registers the tab for a round. False, with a warning, when the server refused it. */
    boolean load(Round round, BingoConfig config) {
        retire();
        String prefix = "card_" + Long.toString(System.currentTimeMillis(), 36);
        NamespacedKey rootKey = new NamespacedKey(plugin, prefix + "/root");
        Map<Key, String> definitions = new LinkedHashMap<>();
        definitions.put(rootKey, rootJson(round).toString());
        NamespacedKey[] cellKeys = new NamespacedKey[BingoCard.CELLS];
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            cellKeys[cell] = new NamespacedKey(plugin, prefix + "/cell_" + (cell + 1));
            definitions.put(cellKeys[cell], cellJson(round, config, cell, rootKey).toString());
        }
        try {
            // Loading makes every online player reread their advancements from
            // disk, so anything earned since the last autosave has to be
            // written first or it is lost.
            Bukkit.savePlayers();
            @SuppressWarnings("deprecation")
            List<Advancement> loaded = Bukkit.getUnsafe().loadAdvancements(definitions, false);
            if (loaded.size() != definitions.size()) {
                plugin.getLogger().warning("The advancements screen tab was only partly loaded ("
                        + loaded.size() + " of " + definitions.size() + "), it stays off this round");
                return false;
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not add the card to the advancements screen: " + e.getMessage());
            return false;
        }
        root = Bukkit.getAdvancement(rootKey);
        cells = new Advancement[BingoCard.CELLS];
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            cells[cell] = Bukkit.getAdvancement(cellKeys[cell]);
        }
        return root != null;
    }

    /** Matches what the player sees to their team's claims; no team means no tab. */
    void sync(Player player, Round round, Team team) {
        if (!isLoaded()) {
            return;
        }
        set(player, root, team != null);
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            set(player, cells[cell], team != null && round.board().isClaimed(cell, team.id()));
        }
    }

    /** Takes away anything a player still holds from this or an earlier round. */
    void clear(Player player) {
        for (Advancement advancement : stale) {
            set(player, advancement, false);
        }
        if (isLoaded()) {
            set(player, root, false);
            for (Advancement cell : cells) {
                set(player, cell, false);
            }
        }
    }

    /** The round is over: the current tab joins the hidden leftovers. */
    void retire() {
        if (!isLoaded()) {
            return;
        }
        stale.add(root);
        stale.addAll(List.of(cells));
        root = null;
        cells = null;
    }

    private static void set(Player player, Advancement advancement, boolean done) {
        if (advancement == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (done && !progress.isDone()) {
            progress.awardCriteria(CRITERION);
        } else if (!done && !progress.getAwardedCriteria().isEmpty()) {
            progress.revokeCriteria(CRITERION);
        }
    }

    private JsonObject rootJson(Round round) {
        Messages messages = plugin.messages();
        JsonObject display = display(
                messages.render("card-tab-title"),
                messages.render("card-tab-description",
                        Placeholder.unparsed("seed", String.valueOf(round.seed()))),
                "minecraft:book");
        display.addProperty("background", BACKGROUND);
        return advancement(null, display);
    }

    private JsonObject cellJson(Round round, BingoConfig config, int cell, NamespacedKey parent) {
        Messages messages = plugin.messages();
        String key = round.card().item(cell);
        Component name;
        Component detail;
        String icon;
        if (round.mode() == BingoGameMode.ADVANCEMENTS) {
            Advancement target = config != null ? config.advancement(key) : null;
            AdvancementDisplay targetDisplay = target != null ? target.getDisplay() : null;
            name = targetDisplay != null ? targetDisplay.title() : Component.text(key);
            detail = targetDisplay != null ? targetDisplay.description() : Component.empty();
            icon = targetDisplay != null ? targetDisplay.icon().getType().getKey().toString() : "minecraft:paper";
        } else {
            ItemType type = config != null ? config.itemType(key) : null;
            name = type != null ? Component.translatable(type) : Component.text(key);
            detail = messages.render("card-tab-cell-item");
            icon = type != null ? type.getKey().toString() : "minecraft:barrier";
        }
        int row = cell / BingoCard.COLUMNS + 1;
        int column = cell % BingoCard.COLUMNS + 1;
        JsonObject display = display(
                messages.render("card-tab-cell-title",
                        Placeholder.unparsed("number", String.valueOf(cell + 1)),
                        Placeholder.component("item", name)),
                messages.render("card-tab-cell-description",
                        Placeholder.component("detail", detail),
                        Placeholder.unparsed("row", String.valueOf(row)),
                        Placeholder.unparsed("column", String.valueOf(column))),
                icon);
        display.addProperty("frame", "task");
        return advancement(parent, display);
    }

    private static JsonObject display(Component title, Component description, String icon) {
        JsonObject display = new JsonObject();
        display.add("title", GsonComponentSerializer.gson().serializeToTree(title));
        display.add("description", GsonComponentSerializer.gson().serializeToTree(description));
        JsonObject iconJson = new JsonObject();
        iconJson.addProperty("id", icon);
        display.add("icon", iconJson);
        // The plugin already announces claims with its own lines and sounds.
        display.addProperty("show_toast", false);
        display.addProperty("announce_to_chat", false);
        return display;
    }

    private static JsonObject advancement(NamespacedKey parent, JsonObject display) {
        JsonObject json = new JsonObject();
        if (parent != null) {
            json.addProperty("parent", parent.toString());
        }
        JsonObject criteria = new JsonObject();
        JsonObject impossible = new JsonObject();
        impossible.addProperty("trigger", "minecraft:impossible");
        criteria.add(CRITERION, impossible);
        json.add("criteria", criteria);
        json.add("display", display);
        JsonArray requirements = new JsonArray();
        JsonArray group = new JsonArray();
        group.add(CRITERION);
        requirements.add(group);
        json.add("requirements", requirements);
        return json;
    }
}
