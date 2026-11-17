package com.jifercraft.bingo.compat.v1_12;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.compat.AdvancementSupport;
import com.jifercraft.bingo.compat.ServerVersion;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.game.AtomicFiles;
import com.jifercraft.bingo.game.BingoGameMode;
import com.jifercraft.bingo.game.Game;
import com.jifercraft.bingo.game.Round;
import com.jifercraft.bingo.game.Team;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

/**
 * Advancement features for 1.12 and newer, compiled against the 1.12 API.
 * Loaded by name from AdvancementSupport, so the 1.8 code never touches a
 * class that older servers lack.
 */
public final class Advancements implements AdvancementSupport, Listener {

    private static final String CRITERION = "claimed";
    private static final String ANNOUNCE_RULE = "announceAdvancements";

    private final BingoRush plugin;
    private final Game game;
    private final String namespace;
    // Card advancements players had before the round, criterion by criterion.
    private final Map<UUID, Map<String, Set<String>>> revoked = new HashMap<>();
    private final Map<World, String> announceBefore = new HashMap<>();
    private final File announceFile;
    private Advancement root;
    private Advancement[] cells;
    private final List<NamespacedKey> loadedKeys = new ArrayList<>();

    public Advancements(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
        this.namespace = new NamespacedKey(plugin, "card").getNamespace();
        BingoConfig.advancementExists = key -> advancement(key) != null;
        this.announceFile = new File(plugin.getDataFolder(), "data/announce-rule.txt");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        removeLeftoverTabs();
        restoreAnnouncementsAfterCrash();
    }

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        NamespacedKey key = event.getAdvancement().getKey();
        if (key.getNamespace().equals(namespace)) {
            return;
        }
        // Recipe unlocks and other hidden entries never show in chat.
        boolean announced = !key.getKey().startsWith("recipes/") && !key.getKey().startsWith("root");
        game.advancementDone(event.getPlayer(), key.toString(), announced);
    }

    // ---- owned advancements ----

    @Override
    public void revokeOwned(Player player, Round round, Team team) {
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            if (team != null && round.board().isClaimed(cell, team.id())) {
                continue;
            }
            String key = round.card().item(cell);
            Advancement advancement = advancement(key);
            if (advancement == null) {
                continue;
            }
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            Collection<String> awarded = new ArrayList<>(progress.getAwardedCriteria());
            if (awarded.isEmpty()) {
                continue;
            }
            revoked.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>())
                    .computeIfAbsent(key, k -> new LinkedHashSet<>())
                    .addAll(awarded);
            for (String criterion : awarded) {
                progress.revokeCriteria(criterion);
            }
        }
    }

    @Override
    public Map<String, List<String>> takeOwned(UUID playerId) {
        Map<String, Set<String>> owed = revoked.remove(playerId);
        Map<String, List<String>> copy = new HashMap<>();
        if (owed != null) {
            for (Map.Entry<String, Set<String>> entry : owed.entrySet()) {
                copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return copy;
    }

    @Override
    public Set<UUID> ownedPlayers() {
        return new HashSet<>(revoked.keySet());
    }

    @Override
    public Map<String, Map<String, List<String>>> ownedSnapshot() {
        Map<String, Map<String, List<String>>> copy = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Set<String>>> entry : revoked.entrySet()) {
            Map<String, List<String>> perKey = new HashMap<>();
            for (Map.Entry<String, Set<String>> owed : entry.getValue().entrySet()) {
                perKey.put(owed.getKey(), new ArrayList<>(owed.getValue()));
            }
            copy.put(entry.getKey().toString(), perKey);
        }
        return copy;
    }

    @Override
    public void loadOwned(Map<UUID, Map<String, List<String>>> saved) {
        revoked.clear();
        for (Map.Entry<UUID, Map<String, List<String>>> entry : saved.entrySet()) {
            Map<String, Set<String>> perKey = new HashMap<>();
            for (Map.Entry<String, List<String>> owed : entry.getValue().entrySet()) {
                perKey.put(owed.getKey(), new LinkedHashSet<>(owed.getValue()));
            }
            revoked.put(entry.getKey(), perKey);
        }
    }

    @Override
    public void award(Player player, Map<String, ? extends Collection<String>> owed) {
        for (Map.Entry<String, ? extends Collection<String>> entry : owed.entrySet()) {
            Advancement advancement = advancement(entry.getKey());
            if (advancement == null) {
                continue;
            }
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            for (String criterion : entry.getValue()) {
                progress.awardCriteria(criterion);
            }
        }
    }

    // ---- vanilla chat lines ----

    // The rule is saved with the world, so the values from before the round
    // also go to a file: after a crash mid-round they come back on the next
    // start instead of leaving announcements off for good.
    @Override
    @SuppressWarnings("deprecation")
    public void silenceAnnouncements(boolean silence) {
        if (silence) {
            for (World world : Bukkit.getWorlds()) {
                if (!announceBefore.containsKey(world)) {
                    announceBefore.put(world, world.getGameRuleValue(ANNOUNCE_RULE));
                    world.setGameRuleValue(ANNOUNCE_RULE, "false");
                }
            }
            StringBuilder saved = new StringBuilder();
            for (Map.Entry<World, String> entry : announceBefore.entrySet()) {
                if (entry.getValue() != null) {
                    saved.append(entry.getKey().getName()).append('=').append(entry.getValue()).append(System.lineSeparator());
                }
            }
            AtomicFiles.write(announceFile, saved.toString());
            return;
        }
        for (Map.Entry<World, String> entry : announceBefore.entrySet()) {
            if (entry.getValue() != null) {
                entry.getKey().setGameRuleValue(ANNOUNCE_RULE, entry.getValue());
            }
        }
        announceBefore.clear();
        AtomicFiles.delete(announceFile);
    }

    @SuppressWarnings("deprecation")
    private void restoreAnnouncementsAfterCrash() {
        if (!announceFile.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(announceFile.toPath(), StandardCharsets.UTF_8)) {
                int split = line.indexOf('=');
                World world = split > 0 ? Bukkit.getWorld(line.substring(0, split)) : null;
                if (world != null) {
                    world.setGameRuleValue(ANNOUNCE_RULE, line.substring(split + 1).trim());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read " + announceFile.getName() + ": " + e.getMessage());
        }
        AtomicFiles.delete(announceFile);
    }

    // ---- the card tab ----

    @Override
    public boolean tabLoaded() {
        return root != null;
    }

    /**
     * Registers the tab for a round. On these versions every loaded
     * advancement is also written into the world's data folder, so the tab is
     * removed again when the round ends, and each add makes the server reload
     * every player's advancements from disk; players are saved first so
     * nothing earned since the last autosave is lost.
     */
    @Override
    @SuppressWarnings("deprecation")
    public boolean loadTab(Round round, BingoConfig config) {
        retireTab();
        String prefix = "card_" + Long.toString(System.currentTimeMillis(), 36);
        NamespacedKey rootKey = new NamespacedKey(plugin, prefix + "/root");
        Bukkit.savePlayers();
        try {
            root = load(rootKey, rootJson(round));
            if (root == null) {
                return false;
            }
            cells = new Advancement[BingoCard.CELLS];
            for (int cell = 0; cell < BingoCard.CELLS; cell++) {
                NamespacedKey key = new NamespacedKey(plugin, prefix + "/cell_" + (cell + 1));
                cells[cell] = load(key, cellJson(round, cell, rootKey));
            }
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not add the card to the advancements screen: " + e.getMessage());
            retireTab();
            return false;
        }
    }

    // The icon field was "item" until 1.20.5 and "id" after. The server
    // rejects the wrong one, so the other is tried before giving up.
    @SuppressWarnings("deprecation")
    private Advancement load(NamespacedKey key, JsonObject json) {
        boolean idFirst = ServerVersion.atLeast(20, 5);
        Advancement loaded;
        try {
            loaded = Bukkit.getUnsafe().loadAdvancement(key, shape(json, idFirst).toString());
        } catch (RuntimeException firstFailure) {
            loaded = Bukkit.getUnsafe().loadAdvancement(key, shape(json, !idFirst).toString());
        }
        loadedKeys.add(key);
        return loaded;
    }

    // The background is not checked by the server, only drawn by the client:
    // a texture path until 1.21.5, a texture id after.
    private static JsonObject shape(JsonObject source, boolean iconId) {
        JsonObject json = (JsonObject) new com.google.gson.JsonParser().parse(source.toString());
        JsonObject display = json.getAsJsonObject("display");
        String icon = display.remove("icon").getAsString();
        JsonObject iconJson = new JsonObject();
        iconJson.addProperty(iconId ? "id" : "item", icon);
        display.add("icon", iconJson);
        if (display.has("background")) {
            display.remove("background");
            String background;
            if (ServerVersion.atLeast(21, 5)) {
                background = "minecraft:block/yellow_concrete";
            } else if (ServerVersion.atLeast(13)) {
                background = "minecraft:textures/block/yellow_concrete.png";
            } else {
                background = "minecraft:textures/blocks/concrete_yellow.png";
            }
            display.addProperty("background", background);
        }
        return json;
    }

    @Override
    public void syncTab(Player player, Round round, Team team) {
        if (!tabLoaded()) {
            return;
        }
        set(player, root, team != null);
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            set(player, cells[cell], team != null && round.board().isClaimed(cell, team.id()));
        }
    }

    @Override
    public void clearTab(Player player) {
        if (!tabLoaded()) {
            return;
        }
        set(player, root, false);
        for (Advancement cell : cells) {
            set(player, cell, false);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void retireTab() {
        for (NamespacedKey key : loadedKeys) {
            Bukkit.getUnsafe().removeAdvancement(key);
        }
        loadedKeys.clear();
        root = null;
        cells = null;
    }

    // A crash between load and retire leaves files behind, which load again
    // on the next start; they go before anything else runs.
    @SuppressWarnings("deprecation")
    private void removeLeftoverTabs() {
        List<NamespacedKey> leftovers = new ArrayList<>();
        Iterator<Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            NamespacedKey key = iterator.next().getKey();
            if (key.getNamespace().equals(namespace)) {
                leftovers.add(key);
            }
        }
        for (NamespacedKey key : leftovers) {
            Bukkit.getUnsafe().removeAdvancement(key);
        }
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
        JsonObject display = display(
                plugin.messages().render("card-tab-title"),
                plugin.messages().render("card-tab-description", Placeholder.unparsed("seed", String.valueOf(round.seed()))),
                "minecraft:book");
        // shape() puts in the background path this version understands.
        display.addProperty("background", "");
        return advancement(null, display);
    }

    private JsonObject cellJson(Round round, int cell, NamespacedKey parent) {
        String key = round.card().item(cell);
        Component name;
        Component detail;
        String icon;
        if (round.mode() == BingoGameMode.ADVANCEMENTS) {
            name = Game.advancementName(key);
            detail = Component.translatable("advancements." + key.substring(key.indexOf(':') + 1).replace('/', '.') + ".description");
            icon = "minecraft:paper";
        } else {
            BingoConfig config = plugin.bingoConfig();
            Material material = config != null && config.material(key) != null ? config.material(key).get() : null;
            name = Component.text(prettyKey(key));
            detail = plugin.messages().render("card-tab-cell-item");
            icon = material != null && ServerVersion.atLeast(13) ? key : "minecraft:" + (material != null
                    ? material.name().toLowerCase(Locale.ROOT) : "barrier");
        }
        JsonObject display = display(
                plugin.messages().render("card-tab-cell-title",
                        Placeholder.unparsed("number", String.valueOf(cell + 1)),
                        Placeholder.component("item", name)),
                plugin.messages().render("card-tab-cell-description",
                        Placeholder.component("detail", detail),
                        Placeholder.unparsed("row", String.valueOf(cell / BingoCard.COLUMNS + 1)),
                        Placeholder.unparsed("column", String.valueOf(cell % BingoCard.COLUMNS + 1))),
                icon);
        display.addProperty("frame", "task");
        return advancement(parent, display);
    }

    private static JsonObject display(Component title, Component description, String icon) {
        // Hex colors exist from 1.16; older clients need the 16 named ones.
        GsonComponentSerializer serializer = ServerVersion.atLeast(16)
                ? GsonComponentSerializer.gson() : GsonComponentSerializer.colorDownsamplingGson();
        JsonObject display = new JsonObject();
        display.add("title", new com.google.gson.JsonParser().parse(serializer.serialize(title)));
        display.add("description", new com.google.gson.JsonParser().parse(serializer.serialize(description)));
        display.addProperty("icon", icon);
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
        group.add(new com.google.gson.JsonPrimitive(CRITERION));
        requirements.add(group);
        json.add("requirements", requirements);
        return json;
    }

    @SuppressWarnings("deprecation")
    private static Advancement advancement(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return Bukkit.getAdvancement(new NamespacedKey(key.substring(0, colon), key.substring(colon + 1)));
    }

    private static String prettyKey(String key) {
        String path = key.substring(key.indexOf(':') + 1);
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (!word.isEmpty()) {
                out.append(out.length() > 0 ? " " : "").append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
