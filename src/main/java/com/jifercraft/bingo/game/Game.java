package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.CardGenerator;
import com.jifercraft.bingo.card.ClaimBoard;
import com.jifercraft.bingo.card.ItemPool;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.LateJoin;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.history.MatchRecord;
import com.jifercraft.bingo.history.MatchSummary;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns the current round: starting it, the countdown, the clock, pause,
 * stop, and team changes. One round at a time; a null round is the lobby.
 * The config a round started with is kept for that whole round, so a reload
 * in the middle cannot pull the rules under the players' feet.
 */
public final class Game {

    private final BingoRush plugin;
    private final Teams teams;
    private final CardMenu cardMenu;
    private final TeamMenu teamMenu;
    private final NamespacedKey cardTag;
    private final NamespacedKey cardItemTag;
    private final Hud hud;
    private final File activeRoundFile;
    private final Map<UUID, ItemStack[]> savedInventories = new HashMap<>();
    private BukkitTask countdownTask;
    private BukkitTask clockTask;
    private BukkitTask sweepTask;
    private BukkitTask autosaveTask;
    // From /bingo reroll: the seed the next start uses unless one is given.
    private Long pendingSeed;
    private Round round;
    private BingoConfig roundConfig;
    private int secondsLeft;
    private boolean roundRestoredUnfinished;

    public Game(BingoRush plugin, Teams teams) {
        this.plugin = plugin;
        this.teams = teams;
        this.cardTag = new NamespacedKey(plugin, "card-menu");
        this.cardItemTag = new NamespacedKey(plugin, "card-item");
        this.cardMenu = new CardMenu(plugin, teams, cardTag);
        this.teamMenu = new TeamMenu(plugin, this, new NamespacedKey(plugin, "team-menu"));
        this.hud = new Hud(plugin, this);
        this.activeRoundFile = new File(plugin.getDataFolder(), "data/active-round.json");
    }

    public Round round() {
        return round;
    }

    public Teams teams() {
        return teams;
    }

    /** True when the top inventory of a view is one of our card menus. */
    public boolean isCardMenuView(InventoryView view) {
        return cardMenu.isMenuItem(view.getTopInventory().getItem(0));
    }

    /** Opens the round card for a player, if they are on a team. */
    public void openCard(Player player) {
        Round current = round;
        if (current == null) {
            messages().send(player, "round-not-running");
            return;
        }
        Team team = teams.of(player.getUniqueId());
        if (team == null) {
            messages().send(player, "card-not-playing");
            return;
        }
        cardMenu.open(player, current, team, false);
        messages().send(player, "card-opened");
    }

    /** Opens a team card from that team's perspective for spectators and casters. */
    public void openCardSpectator(Player player, String teamName) {
        Round current = round;
        if (current == null) {
            messages().send(player, "round-not-running");
            return;
        }
        Team target = teams.byName(teamName);
        if (target == null) {
            messages().send(player, "team-not-found", Placeholder.unparsed("name", teamName));
            return;
        }
        cardMenu.open(player, current, target, true);
        messages().send(player, "card-opened-spectator", teamPlaceholder(target));
    }

    /** True when the view is the lobby team menu. */
    public boolean isTeamMenuView(InventoryView view) {
        return teamMenu.isMenuItem(view.getTopInventory().getItem(0));
    }

    /** Opens the lobby team menu, where players join or switch teams. */
    public void openTeamMenu(Player player) {
        teamMenu.open(player);
    }

    public void teamMenuClick(Player player, ItemStack stack) {
        teamMenu.click(player, stack);
    }

    /** A configured sound for one player, for menu feedback. */
    public void playSound(Player player, BingoConfig.SoundId id) {
        hud.play(id, player);
    }

    public void startRound(CommandSender caller, String seedArg) {
        startRound(caller, null, null, seedArg);
    }

    public void startRound(CommandSender caller, BingoGameMode modeOverride, String seedArg) {
        startRound(caller, modeOverride, null, seedArg);
    }

    public void startRound(CommandSender caller, BingoGameMode modeOverride, Boolean lockoutOverride, String seedArg) {
        if (round != null) {
            messages().send(caller, "round-already-running");
            return;
        }
        BingoConfig config = plugin.bingoConfig();
        if (config == null) {
            messages().send(caller, "round-config-broken");
            return;
        }
        BingoGameMode mode = modeOverride != null ? modeOverride : config.gameMode();
        boolean lockout = lockoutOverride != null ? lockoutOverride : config.lockout();
        long seed = seedArg != null && !seedArg.isBlank()
                ? CardGenerator.seedFrom(seedArg)
                : nextSeed();
        ItemPool pool = mode == BingoGameMode.ADVANCEMENTS ? config.advancementPool() : config.itemPool();
        if (pool == null) {
            plugin.getLogger().severe("The " + (mode == BingoGameMode.ADVANCEMENTS ? "advancement" : "item")
                    + " pool is not configured");
            messages().send(caller, "round-config-broken");
            return;
        }
        BingoCard card;
        try {
            card = new CardGenerator(pool).generate(seed);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().severe("The card could not be generated: " + e.getMessage());
            messages().send(caller, "round-config-broken");
            return;
        }
        roundConfig = config;
        round = new Round(seed, mode, card, new ClaimBoard(config.winMode(), lockout));
        // The lobby menus are gone the moment a round exists, countdown included.
        teamMenu.closeAll();
        int total = config.countdownSeconds();
        if (total > 0) {
            broadcast("round-countdown-chat", Placeholder.unparsed("count", String.valueOf(total)));
            plugin.getLogger().info("Countdown of " + total + "s started, seed " + seed
                    + (lockout ? " [lockout]" : ""));
            secondsLeft = total;
            hud.countdownTick(secondsLeft);
            countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCountdown, 20, 20);
        } else {
            beginRun();
        }
    }

    public void stopRound(CommandSender caller) {
        if (round == null) {
            messages().send(caller, "round-not-running");
            return;
        }
        plugin.getLogger().info("Round stopped by " + caller.getName() + ", seed " + round.seed());
        broadcast("round-stopped");
        endRound();
    }

    public void pauseRound(CommandSender caller) {
        if (round == null) {
            messages().send(caller, "round-not-running");
            return;
        }
        switch (round.state()) {
            case COUNTDOWN -> messages().send(caller, "round-not-started");
            case PAUSED -> messages().send(caller, "round-already-paused");
            case RUNNING -> {
                round.pause();
                cancelClock();
                hud.pause();
                broadcast("round-paused");
                saveActiveRound();
            }
        }
    }

    public void resumeRound(CommandSender caller) {
        if (round == null) {
            messages().send(caller, "round-not-running");
            return;
        }
        if (round.state() != Round.State.PAUSED) {
            messages().send(caller, "round-not-paused");
            return;
        }
        round.resume();
        hud.resume();
        roundRestoredUnfinished = false;
        if (clockTask == null) {
            clockTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickClock, 20, 20);
        }
        if (sweepTask == null) {
            sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweepClaims, 20, 20);
        }
        if (autosaveTask == null) {
            autosaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::autosave, 600, 600);
        }
        broadcast("round-resumed");
        saveActiveRound();
    }

    public void rerollCard(CommandSender caller) {
        if (round != null) {
            messages().send(caller, "round-already-running");
            return;
        }
        pendingSeed = new Random().nextLong();
        messages().send(caller, "reroll-done", Placeholder.unparsed("seed", Long.toString(pendingSeed)));
        plugin.getLogger().info("Next card rerolled, seed " + pendingSeed);
    }

    public void joinTeam(Player player, String teamName) {
        BingoConfig config = plugin.bingoConfig();
        Round current = round;
        if (current != null && current.state() != Round.State.COUNTDOWN
                && config != null && config.lateJoin() == LateJoin.SPECTATE) {
            messages().send(player, "late-join-spectate");
            return;
        }
        Team currentTeam = teams.of(player.getUniqueId());
        if (currentTeam != null) {
            messages().send(player, "team-already-in", teamPlaceholder(currentTeam));
            return;
        }
        Team target;
        if (teamName != null) {
            target = teams.byName(teamName);
            if (target == null) {
                messages().send(player, "team-not-found", Placeholder.unparsed("name", teamName));
                return;
            }
        } else if (teams.all().isEmpty()) {
            // No configured teams: make one up so a quick game needs no setup.
            String name = messages().plainText("team-auto-name",
                    Placeholder.unparsed("count", String.valueOf(teams.all().size() + 1)));
            target = teams.createAuto(name, teams.all().size());
        } else if (roomyTeams(config) > 1) {
            // More than one team to choose from: let the player pick with a
            // click instead of landing wherever there is space.
            showTeamPicker(player, config);
            return;
        } else {
            target = teams.withRoom(cap(config));
            if (target == null) {
                messages().send(player, "team-all-full");
                return;
            }
        }
        if (target.size() >= cap(config)) {
            messages().send(player, "team-full", teamPlaceholder(target));
            return;
        }
        target.add(player.getUniqueId());
        messages().send(player, "team-joined", teamPlaceholder(target));
        teamMenu.refreshAll();
        if (current != null) {
            // Joined during the countdown or a round that allows late play.
            giveCardItem(player);
            hud.playerJoined(player);
        }
    }

    public void leaveTeam(Player player) {
        Team team = teams.of(player.getUniqueId());
        if (team == null) {
            messages().send(player, "team-not-in");
            return;
        }
        team.remove(player.getUniqueId());
        messages().send(player, "team-left");
        teamMenu.refreshAll();
        if (round != null) {
            hud.playerLeft(player);
            removeCardItems(player);
        }
    }

    public void shareCoordinates(Player player) {
        Team team = teams.of(player.getUniqueId());
        if (team == null) {
            messages().send(player, "team-not-in");
            return;
        }
        Location loc = player.getLocation();
        TagResolver x = Placeholder.unparsed("x", String.valueOf(loc.getBlockX()));
        TagResolver y = Placeholder.unparsed("y", String.valueOf(loc.getBlockY()));
        TagResolver z = Placeholder.unparsed("z", String.valueOf(loc.getBlockZ()));
        TagResolver playerTag = Placeholder.unparsed("player", player.getName());
        TagResolver teamTag = teamPlaceholder(team);
        for (UUID memberId : team.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null) {
                messages().send(member, "team-coords", teamTag, playerTag, x, y, z);
            }
        }
    }

    public void pointCompass(Player player, boolean sendFeedback) {
        Team team = teams.of(player.getUniqueId());
        if (team == null) {
            if (sendFeedback) {
                messages().send(player, "team-not-in");
            }
            return;
        }
        Player nearest = findNearestTeammate(player, team);
        if (nearest == null) {
            if (sendFeedback) {
                player.sendActionBar(messages().render("compass-no-teammate"));
            }
            return;
        }
        player.setCompassTarget(nearest.getLocation());
        if (sendFeedback) {
            int distance = (int) Math.round(player.getLocation().distance(nearest.getLocation()));
            player.sendActionBar(messages().render("compass-tracking",
                    Placeholder.unparsed("player", nearest.getName()),
                    Placeholder.unparsed("distance", String.valueOf(distance))));
        }
    }

    public Player findNearestTeammate(Player player, Team team) {
        Player nearest = null;
        double bestDistanceSq = Double.MAX_VALUE;
        Location loc = player.getLocation();
        World world = loc.getWorld();
        for (UUID memberId : team.members()) {
            if (memberId.equals(player.getUniqueId())) {
                continue;
            }
            Player teammate = Bukkit.getPlayer(memberId);
            if (teammate == null || !teammate.isOnline() || !teammate.getWorld().equals(world)) {
                continue;
            }
            double distSq = loc.distanceSquared(teammate.getLocation());
            if (distSq < bestDistanceSq) {
                bestDistanceSq = distSq;
                nearest = teammate;
            }
        }
        return nearest;
    }

    public boolean isHoldingCompass(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main.getType() == Material.COMPASS) {
            return true;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        return off.getType() == Material.COMPASS;
    }

    public void createTeam(CommandSender caller, String name, TextColor color) {
        try {
            Team team = teams.create(name, color);
            messages().send(caller, "team-created", teamPlaceholder(team));
            teamMenu.refreshAll();
        } catch (IllegalArgumentException e) {
            messages().send(caller, "team-name-taken", Placeholder.unparsed("name", name));
        }
    }

    public void removeTeam(CommandSender caller, String name) {
        if (round != null) {
            messages().send(caller, "teams-locked");
            return;
        }
        Team team = teams.byName(name);
        if (team == null) {
            messages().send(caller, "team-not-found", Placeholder.unparsed("name", name));
            return;
        }
        teams.remove(team);
        messages().send(caller, "team-removed", teamPlaceholder(team));
        teamMenu.refreshAll();
    }

    public void assignPlayer(CommandSender caller, Player target, String teamName) {
        Team team = teams.byName(teamName);
        if (team == null) {
            messages().send(caller, "team-not-found", Placeholder.unparsed("name", teamName));
            return;
        }
        Team old = teams.of(target.getUniqueId());
        if (old == team) {
            messages().send(caller, "team-already-there",
                    Placeholder.unparsed("player", target.getName()), teamPlaceholder(team));
            return;
        }
        if (old != null) {
            old.remove(target.getUniqueId());
        }
        team.add(target.getUniqueId());
        teamMenu.refreshAll();
        messages().send(caller, "team-assigned",
                Placeholder.unparsed("player", target.getName()), teamPlaceholder(team));
        messages().send(target, "team-joined", teamPlaceholder(team));
    }

    public void clearTeams(CommandSender caller) {
        for (Team team : teams.all()) {
            for (UUID member : team.members()) {
                team.remove(member);
            }
        }
        teamMenu.refreshAll();
        messages().send(caller, "team-cleared");
    }

    public void listTeams(CommandSender caller) {
        messages().send(caller, "team-list-header");
        BingoConfig config = plugin.bingoConfig();
        int cap = cap(config);
        for (Team team : teams.all()) {
            messages().send(caller, "team-list-entry",
                    joinButton(team),
                    teamPlaceholder(team),
                    Placeholder.unparsed("count", String.valueOf(team.size())),
                    Placeholder.unparsed("max", String.valueOf(cap)));
        }
    }

    /** The click-to-join list, for /bingo join with more than one open team. */
    private void showTeamPicker(Player player, BingoConfig config) {
        messages().send(player, "team-pick");
        int cap = cap(config);
        for (Team team : teams.all()) {
            if (team.size() >= cap) {
                continue;
            }
            messages().send(player, "team-list-entry",
                    joinButton(team),
                    teamPlaceholder(team),
                    Placeholder.unparsed("count", String.valueOf(team.size())),
                    Placeholder.unparsed("max", String.valueOf(cap)));
        }
    }

    private long roomyTeams(BingoConfig config) {
        int cap = cap(config);
        return teams.all().stream().filter(team -> team.size() < cap).count();
    }

    /**
     * A [Unirse] button that runs /bingo join for that team. The command gets
     * the name quoted, because team names may hold spaces and Brigadier needs
     * quotes to parse them as one argument.
     */
    private TagResolver joinButton(Team team) {
        Component button = messages().render("team-join-button")
                .hoverEvent(messages().render("team-join-hover", teamPlaceholder(team)))
                .clickEvent(ClickEvent.runCommand("/bingo join " + quoted(team.name())));
        return Placeholder.component("join", button);
    }

    private static String quoted(String name) {
        return '"' + name.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Claims every cell the player's team can claim from what they carry.
     * Called from gameplay events and from the periodic sweep; claiming twice
     * is harmless because the board ignores repeats.
     */
    public void checkClaims(Player player) {
        Round current = round;
        if (current == null || current.state() != Round.State.RUNNING || current.mode() != BingoGameMode.ITEMS) {
            return;
        }
        Team team = teams.of(player.getUniqueId());
        if (team == null) {
            return;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            for (int cell = 0; cell < BingoCard.CELLS; cell++) {
                if (current.board().isClaimed(cell, team.id())) {
                    continue;
                }
                if (carriedMatches(current, cell, stack)) {
                    claimCell(current, team, cell, player);
                }
            }
        }
    }

    /** Gives back the items of a player who left during a round. */
    public void restoreInventory(Player player) {
        ItemStack[] contents = savedInventories.remove(player.getUniqueId());
        if (contents != null) {
            player.getInventory().setContents(contents);
        }
    }

    /**
     * Handles a connecting player. Notifies admins of restored rounds, ensures
     * participating team members have their card item and HUD, or returns items
     * to players without an active round.
     */
    public void playerJoined(Player player) {
        if (roundRestoredUnfinished && player.hasPermission("bingo.admin")) {
            messages().send(player, "round-restored-admin");
        }
        if (round != null) {
            Team team = teams.of(player.getUniqueId());
            if (team != null) {
                hud.playerJoined(player);
                giveCardItem(player);
            } else {
                restoreInventory(player);
            }
        } else {
            restoreInventory(player);
        }
    }

    /** True when the stack is the physical card item. */
    public boolean isCardItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(cardItemTag, PersistentDataType.BYTE);
    }

    /** One card item per member of every team, after their inventories cleared. */
    private void giveCardItems() {
        for (Team team : teams.all()) {
            for (UUID memberId : team.members()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member != null) {
                    giveCardItem(member);
                }
            }
        }
    }

    private void giveCardItem(Player player) {
        removeCardItems(player);
        ItemStack card = ItemType.BOOK.createItemStack();
        card.editMeta(meta -> {
            meta.displayName(messages().render("card-item-name"));
            meta.lore(List.of(messages().render("card-item-lore")));
            meta.getPersistentDataContainer().set(cardItemTag, PersistentDataType.BYTE, (byte) 1);
        });
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(card);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    private void removeCardItems(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (isCardItem(contents[i])) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) {
            player.getInventory().setContents(contents);
        }
    }

    /** Card items go away with the round, even for players without a snapshot. */
    private void removeCardItemsFromAll() {
        for (Team team : teams.all()) {
            for (UUID memberId : team.members()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member != null) {
                    removeCardItems(member);
                }
            }
        }
    }

    /** Undoes everything a running round did, for the plugin shutting down. */
    public void shutdown() {
        if (round != null) {
            saveActiveRound();
        }
        cancelTasks();
        cardMenu.closeAll();
        teamMenu.closeAll();
        hud.hideAll();
        if (round == null) {
            restoreInventories();
        }
        removeCardItemsFromAll();
        round = null;
        roundConfig = null;
    }

    /** A player closed one of our menus; stop tracking it everywhere. */
    public void menuClosed(UUID playerId) {
        cardMenu.forget(playerId);
        teamMenu.forget(playerId);
    }

    private long nextSeed() {
        if (pendingSeed != null) {
            long seed = pendingSeed;
            pendingSeed = null;
            return seed;
        }
        return new Random().nextLong();
    }

    private int cap(BingoConfig config) {
        return config == null ? Integer.MAX_VALUE : config.teamSize();
    }

    /** The configured players-per-team cap, for menus that show it. */
    public int teamSizeCap() {
        return cap(plugin.bingoConfig());
    }

    private void tickCountdown() {
        if (round == null) {
            cancelCountdown();
            return;
        }
        secondsLeft--;
        if (secondsLeft <= 0) {
            cancelCountdown();
            beginRun();
        } else {
            hud.countdownTick(secondsLeft);
        }
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void beginRun() {
        if (round == null) {
            return;
        }
        round.beginRun();
        if (roundConfig.clearInventories()) {
            saveAndClearInventories();
            broadcast("round-cleared-inventories");
        }
        giveCardItems();
        if (roundConfig.scatterPlayers()) {
            scatterTeams();
        }
        broadcast("round-started-chat");
        plugin.getLogger().info("Round started (" + round.mode().name().toLowerCase(Locale.ROOT)
                + "), seed " + round.seed());
        hud.roundStarted(roundConfig.timeLimitSeconds());
        clockTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickClock, 20, 20);
        // The events catch most claims, but /give and friends fire nothing,
        // so every second the plugin looks at every team member's inventory.
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweepClaims, 20, 20);
        autosaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::autosave, 600, 600);
        saveActiveRound();
    }

    private void tickClock() {
        Round current = round;
        if (current == null || current.state() != Round.State.RUNNING) {
            return;
        }
        hud.clockTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isHoldingCompass(player)) {
                pointCompass(player, false);
            }
        }
        long remaining = current.remainingMs(roundConfig.timeLimitSeconds());
        if (remaining <= 0) {
            finishByTimeout();
        }
    }

    private void finishByTimeout() {
        Round current = round;
        if (current == null) {
            return;
        }
        Team winner = teams.byId(current.board().leader().orElse(-1));
        if (winner == null) {
            broadcast("round-no-claims");
            plugin.getLogger().info("Round timed out with no claims, seed " + current.seed());
        } else {
            broadcast("round-time-up-chat",
                    teamPlaceholder(winner),
                    Placeholder.unparsed("count", String.valueOf(current.board().count(winner.id()))));
            plugin.getLogger().info("Round timed out, winner: " + winner.name() + ", seed " + current.seed());
        }
        broadcastBreakdown(current);
        recordMatchResult(current, winner, "TIMEOUT");
        hud.timeUp();
        endRound();
    }

    /** The round is over: everything back to the lobby. */
    private void endRound() {
        cancelTasks();
        cardMenu.closeAll();
        hud.hideAll();
        restoreInventories();
        removeCardItemsFromAll();
        RoundStorage.delete(activeRoundFile);
        roundRestoredUnfinished = false;
        round = null;
        roundConfig = null;
    }

    private void cancelTasks() {
        cancelCountdown();
        cancelClock();
        cancelSweep();
        cancelAutosave();
    }

    private void cancelAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
    }

    private void autosave() {
        if (round != null && round.state() == Round.State.RUNNING) {
            saveActiveRound();
        }
    }

    public void saveActiveRound() {
        if (round == null) {
            return;
        }
        long limit = roundConfig != null ? roundConfig.timeLimitSeconds() : 0;
        try {
            RoundStorage.save(activeRoundFile, round, limit, savedInventories);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save active round state: " + e.getMessage());
        }
    }

    public void loadSavedRoundIfPresent() {
        if (!RoundStorage.exists(activeRoundFile)) {
            return;
        }
        try {
            RoundStorage.RestoredRound restored = RoundStorage.load(activeRoundFile);
            this.round = restored.round();
            this.roundConfig = plugin.bingoConfig();
            this.savedInventories.clear();
            this.savedInventories.putAll(restored.savedInventories());
            this.roundRestoredUnfinished = true;

            hud.restorePaused(restored.timeLimitSeconds());

            plugin.getLogger().info("Found an unfinished round from previous session (seed "
                    + round.seed() + "). Restored in PAUSED state.");

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("bingo.admin")) {
                    messages().send(player, "round-restored-admin");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to restore saved round from "
                    + activeRoundFile.getPath() + ": " + e.getMessage());
        }
    }

    public File activeRoundFile() {
        return activeRoundFile;
    }

    public boolean isRoundRestoredUnfinished() {
        return roundRestoredUnfinished;
    }

    private void cancelSweep() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    private void cancelClock() {
        if (clockTask != null) {
            clockTask.cancel();
            clockTask = null;
        }
    }

    private void sweepClaims() {
        Round current = round;
        if (current == null || current.state() != Round.State.RUNNING) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            checkClaims(player);
        }
    }

    private void claimCell(Round current, Team team, int cell, Player claimer) {
        if (!current.board().claim(cell, team.id(), System.currentTimeMillis())) {
            return;
        }
        current.recordFirstClaim(cell, team.id(), current.elapsedMs());
        String itemKey = current.card().item(cell);
        ItemType type = roundConfig.itemType(itemKey);
        // ItemType is a Translatable, so each player sees the item name in
        // their own language, and the console log below has the raw key.
        TagResolver item = Placeholder.component("item", type == null
                ? Component.text(itemKey)
                : Component.translatable(type));
        int count = current.board().count(team.id());
        broadcast("claim", item, teamPlaceholder(team),
                Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("total", String.valueOf(BingoCard.CELLS)));
        Component bar = messages().render("claim-actionbar", item,
                Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("total", String.valueOf(BingoCard.CELLS)));
        for (UUID memberId : team.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null) {
                member.sendActionBar(bar);
            }
        }
        plugin.getLogger().info("Team " + team.name() + " claimed " + itemKey
                + " (cell " + cell + "), " + count + " cells, by " + claimer.getName());
        if (current.board().isLockout()) {
            hud.lockoutLost(team);
            Component rivalBar = messages().render("lockout-cell-lost-actionbar", item, teamPlaceholder(team));
            for (Player player : Bukkit.getOnlinePlayers()) {
                Team playerTeam = teams.of(player.getUniqueId());
                if (playerTeam != null && playerTeam.id() != team.id()) {
                    player.sendActionBar(rivalBar);
                }
            }
        }
        hud.claimHappened(team);
        cardMenu.refresh(current);

        int winnerId = current.board().completedWinner().orElse(-1);
        if (winnerId != -1) {
            Team winner = teams.byId(winnerId);
            if (winner != null) {
                winByCompletion(current, winner);
            }
        } else {
            saveActiveRound();
        }
    }

    /**
     * Checks if the completed advancement belongs to the active card and awards
     * the cell if the player's team has not claimed it yet.
     */
    public void checkAdvancementClaim(Player player, Advancement advancement) {
        Round current = round;
        if (current == null || current.state() != Round.State.RUNNING || current.mode() != BingoGameMode.ADVANCEMENTS) {
            return;
        }
        NamespacedKey key = advancement.getKey();
        if (key.getNamespace().equals("minecraft") && key.getKey().startsWith("recipes/")) {
            return;
        }
        Team team = teams.of(player.getUniqueId());
        if (team == null) {
            return;
        }
        String keyStr = key.toString();
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            if (current.board().isClaimed(cell, team.id())) {
                continue;
            }
            if (keyStr.equalsIgnoreCase(current.card().item(cell))) {
                claimAdvancementCell(current, team, cell, player, advancement);
            }
        }
    }

    private void claimAdvancementCell(Round current, Team team, int cell, Player claimer, Advancement advancement) {
        if (!current.board().claim(cell, team.id(), System.currentTimeMillis())) {
            return;
        }
        current.recordFirstClaim(cell, team.id(), current.elapsedMs());
        Component advTitle = advancement.getDisplay() != null
                ? advancement.getDisplay().title()
                : Component.text(advancement.getKey().toString());

        TagResolver adv = Placeholder.component("advancement", advTitle);
        TagResolver item = Placeholder.component("item", advTitle);
        int count = current.board().count(team.id());

        broadcast("claim-advancement", adv, item, teamPlaceholder(team),
                Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("total", String.valueOf(BingoCard.CELLS)));

        Component bar = messages().render("claim-advancement-actionbar", adv, item,
                Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("total", String.valueOf(BingoCard.CELLS)));
        Title title = Title.title(
                messages().render("claim-advancement-title", adv, item),
                messages().render("claim-advancement-subtitle", adv, item, teamPlaceholder(team),
                        Placeholder.unparsed("count", String.valueOf(count)),
                        Placeholder.unparsed("total", String.valueOf(BingoCard.CELLS))),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(300)));

        for (UUID memberId : team.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null) {
                member.sendActionBar(bar);
                member.showTitle(title);
            }
        }

        plugin.getLogger().info("Team " + team.name() + " claimed advancement " + advancement.getKey()
                + " (cell " + cell + "), " + count + " cells, by " + claimer.getName());
        if (current.board().isLockout()) {
            hud.lockoutLost(team);
            Component rivalBar = messages().render("lockout-advancement-lost-actionbar", adv, item, teamPlaceholder(team));
            for (Player player : Bukkit.getOnlinePlayers()) {
                Team playerTeam = teams.of(player.getUniqueId());
                if (playerTeam != null && playerTeam.id() != team.id()) {
                    player.sendActionBar(rivalBar);
                }
            }
        }
        hud.claimHappened(team);
        cardMenu.refresh(current);

        int winnerId = current.board().completedWinner().orElse(-1);
        if (winnerId != -1) {
            Team winner = teams.byId(winnerId);
            if (winner != null) {
                winByCompletion(current, winner);
            }
        } else {
            saveActiveRound();
        }
    }

    private void winByCompletion(Round current, Team winner) {
        String time = clock(current.elapsedMs());
        broadcast("round-won-chat", teamPlaceholder(winner), Placeholder.unparsed("time", time));
        plugin.getLogger().info("Round won by " + winner.name() + " in " + time + ", seed " + current.seed());
        broadcastBreakdown(current);
        recordMatchResult(current, winner, "COMPLETION");
        hud.won(winner);
        endRound();
    }

    private void broadcastBreakdown(Round current) {
        broadcast("breakdown.header");
        broadcast("breakdown.separator");

        List<Team> participants = teams.all().stream()
                .filter(t -> t.size() > 0 || current.board().count(t.id()) > 0)
                .toList();
        if (participants.isEmpty()) {
            participants = teams.all();
        }

        List<Team> ranked = participants.stream()
                .sorted((a, b) -> {
                    int countA = current.board().count(a.id());
                    int countB = current.board().count(b.id());
                    if (countA != countB) {
                        return Integer.compare(countB, countA);
                    }
                    long timeA = current.board().lastClaimTime(a.id());
                    long timeB = current.board().lastClaimTime(b.id());
                    return Long.compare(timeA, timeB);
                })
                .toList();

        int pos = 1;
        for (Team team : ranked) {
            int count = current.board().count(team.id());
            String key = count == 1 ? "breakdown.ranking-entry-singular" : "breakdown.ranking-entry";
            broadcast(key,
                    Placeholder.unparsed("pos", String.valueOf(pos)),
                    teamPlaceholder(team),
                    Placeholder.unparsed("count", String.valueOf(count)));
            pos++;
        }

        Round.FirstClaim first = current.firstClaim();
        if (first != null) {
            Team claimerTeam = teams.byId(first.teamId());
            String itemKey = current.card().item(first.cell());
            ItemType type = roundConfig.itemType(itemKey);
            TagResolver item = Placeholder.component("item", type == null
                    ? Component.text(itemKey)
                    : Component.translatable(type));
            TagResolver teamTag = claimerTeam != null ? teamPlaceholder(claimerTeam) : Placeholder.unparsed("team", "?");
            broadcast("breakdown.first-claim", item, teamTag, Placeholder.unparsed("time", clock(first.elapsedMs())));
        } else {
            broadcast("breakdown.first-claim-none");
        }

        broadcast("breakdown.total-time", Placeholder.unparsed("time", clock(current.elapsedMs())));
        broadcast("breakdown.separator");
    }

    /** Registry item types are singletons, so identity compare is enough. */
    private boolean carriedMatches(Round current, int cell, ItemStack stack) {
        ItemType wanted = roundConfig.itemType(current.card().item(cell));
        return wanted != null && wanted == stack.getType().asItemType();
    }

    /** The clock as m:ss, for chat lines and the HUD. */
    static String clock(long ms) {
        long seconds = ms / 1000;
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private void saveAndClearInventories() {
        for (Team team : teams.all()) {
            for (UUID memberId : team.members()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member == null) {
                    continue;
                }
                savedInventories.put(memberId, member.getInventory().getContents());
                member.getInventory().clear();
            }
        }
    }

    private void restoreInventories() {
        for (Map.Entry<UUID, ItemStack[]> entry : savedInventories.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.getInventory().setContents(entry.getValue());
            }
        }
        savedInventories.clear();
    }

    // One spot per team around the world spawn, so teams start apart but
    // together, and no member stacks on another.
    private void scatterTeams() {
        World world = Bukkit.getWorlds().getFirst();
        Location spawn = world.getSpawnLocation();
        Random random = new Random();
        for (Team team : teams.all()) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.sqrt(random.nextDouble()) * roundConfig.scatterRadius();
            int x = spawn.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = spawn.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            Location spot = world.getHighestBlockAt(x, z).getLocation().add(0.5, 1, 0.5);
            int offset = 0;
            for (UUID memberId : team.members()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member == null) {
                    continue;
                }
                member.teleport(spot.clone().add(offset % 3, 0, offset / 3));
                offset++;
            }
        }
    }

    private void broadcast(String key, TagResolver... placeholders) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            messages().send(player, key, placeholders);
        }
        messages().send(Bukkit.getConsoleSender(), key, placeholders);
    }

    static TagResolver teamPlaceholder(Team team) {
        return Placeholder.component("team", Component.text(team.name(), team.color()));
    }

    public void showHistoryOrDetails(CommandSender sender, String query) {
        if (query == null || query.isBlank()) {
            showHistory(sender, 1);
            return;
        }
        if (plugin.matchHistory().summary(query) != null) {
            showMatchDetails(sender, query);
            return;
        }
        try {
            int page = Integer.parseInt(query);
            showHistory(sender, page);
            return;
        } catch (NumberFormatException ignored) {
        }
        showMatchDetails(sender, query);
    }

    public void showHistory(CommandSender sender, int page) {
        BingoConfig config = plugin.bingoConfig();
        if (config != null && !config.historyEnabled()) {
            messages().send(sender, "history.disabled");
            return;
        }
        var history = plugin.matchHistory();
        if (history.summaries().isEmpty()) {
            messages().send(sender, "history.empty");
            return;
        }

        int pageSize = 5;
        int totalPages = history.totalPages(pageSize);
        if (page < 1 || page > totalPages) {
            messages().send(sender, "history.invalid-page",
                    Placeholder.unparsed("total", String.valueOf(totalPages)));
            return;
        }

        messages().send(sender, "history.header",
                Placeholder.unparsed("page", String.valueOf(page)),
                Placeholder.unparsed("total", String.valueOf(totalPages)));

        for (MatchSummary summary : history.page(page, pageSize)) {
            TextColor color = TextColor.fromHexString(summary.winnerColor());
            Component winnerComp = Component.text(summary.winnerName(), color != null ? color : TextColor.color(0xFFFFFF));
            Component viewBtn = messages().render("history.view-button")
                    .hoverEvent(messages().render("history.view-hover", Placeholder.unparsed("id", summary.id())))
                    .clickEvent(ClickEvent.runCommand("/bingo history " + summary.id()));

            messages().send(sender, "history.entry",
                    Placeholder.unparsed("id", summary.id()),
                    Placeholder.unparsed("date", summary.date()),
                    Placeholder.component("winner", winnerComp),
                    Placeholder.unparsed("time", summary.duration()),
                    Placeholder.component("view", viewBtn));
        }

        if (totalPages > 1) {
            Component prevComp = page > 1
                    ? messages().render("history.prev-button")
                            .hoverEvent(messages().render("history.prev-hover", Placeholder.unparsed("page", String.valueOf(page - 1))))
                            .clickEvent(ClickEvent.runCommand("/bingo history " + (page - 1)))
                    : Component.empty();

            Component nextComp = page < totalPages
                    ? messages().render("history.next-button")
                            .hoverEvent(messages().render("history.next-hover", Placeholder.unparsed("page", String.valueOf(page + 1))))
                            .clickEvent(ClickEvent.runCommand("/bingo history " + (page + 1)))
                    : Component.empty();

            messages().send(sender, "history.pagination",
                    Placeholder.component("prev", prevComp),
                    Placeholder.component("next", nextComp));
        }
    }

    public void showMatchDetails(CommandSender sender, String id) {
        BingoConfig config = plugin.bingoConfig();
        if (config != null && !config.historyEnabled()) {
            messages().send(sender, "history.disabled");
            return;
        }
        MatchRecord record = plugin.matchHistory().loadMatch(id);
        if (record == null) {
            messages().send(sender, "history.not-found", Placeholder.unparsed("id", id));
            return;
        }

        messages().send(sender, "history.detail-header", Placeholder.unparsed("id", record.id()));
        messages().send(sender, "history.detail-date", Placeholder.unparsed("date", record.date()));
        messages().send(sender, "history.detail-mode",
                Placeholder.unparsed("mode", record.gameMode().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("win_mode", record.winMode()),
                Placeholder.unparsed("lockout", record.lockout() ? " | Lockout" : ""));
        messages().send(sender, "history.detail-duration", Placeholder.unparsed("time", record.durationFormatted()));
        messages().send(sender, "history.detail-seed", Placeholder.unparsed("seed", String.valueOf(record.seed())));

        if (record.winner() != null) {
            TextColor color = TextColor.fromHexString(record.winner().color());
            Component winnerComp = Component.text(record.winner().name(), color != null ? color : TextColor.color(0xFFFFFF));
            messages().send(sender, "history.detail-winner",
                    Placeholder.component("winner", winnerComp),
                    Placeholder.unparsed("count", String.valueOf(record.winner().claims())));
        } else {
            messages().send(sender, "history.detail-winner-none");
        }

        if (record.ranking() != null && !record.ranking().isEmpty()) {
            messages().send(sender, "history.detail-ranking-header");
            int pos = 1;
            for (MatchRecord.TeamResult team : record.ranking()) {
                TextColor color = TextColor.fromHexString(team.color());
                Component teamComp = Component.text(team.name(), color != null ? color : TextColor.color(0xFFFFFF));
                messages().send(sender, "history.detail-ranking-entry",
                        Placeholder.unparsed("pos", String.valueOf(pos)),
                        Placeholder.component("team", teamComp),
                        Placeholder.unparsed("count", String.valueOf(team.claims())));
                pos++;
            }
        }

        if (record.firstClaim() != null) {
            MatchRecord.FirstClaim first = record.firstClaim();
            TagResolver itemTag;
            if ("ADVANCEMENTS".equalsIgnoreCase(record.gameMode())) {
                NamespacedKey key = NamespacedKey.fromString(first.item());
                Advancement adv = key != null ? Bukkit.getAdvancement(key) : null;
                Component title = adv != null && adv.getDisplay() != null
                        ? adv.getDisplay().title()
                        : Component.text(first.item());
                itemTag = Placeholder.component("item", title);
            } else {
                ItemType type = roundConfig != null ? roundConfig.itemType(first.item()) : null;
                itemTag = Placeholder.component("item", type != null ? Component.translatable(type) : Component.text(first.item()));
            }
            messages().send(sender, "history.detail-first-claim",
                    itemTag,
                    Placeholder.unparsed("team", first.teamName()),
                    Placeholder.unparsed("time", first.timeFormatted()));
        } else {
            messages().send(sender, "history.detail-first-claim-none");
        }

        messages().send(sender, "history.detail-export-hint",
                Placeholder.unparsed("file", record.id() + ".json"));
    }

    public void exportMatch(CommandSender sender, String id) {
        BingoConfig config = plugin.bingoConfig();
        if (config != null && !config.historyEnabled()) {
            messages().send(sender, "history.disabled");
            return;
        }
        File file = new File(plugin.matchHistory().directory(), id + ".json");
        if (!file.exists()) {
            messages().send(sender, "history.not-found", Placeholder.unparsed("id", id));
            return;
        }
        messages().send(sender, "history.exported",
                Placeholder.unparsed("id", id),
                Placeholder.unparsed("file", id + ".json"));
    }

    private void recordMatchResult(Round current, Team winner, String reason) {
        BingoConfig config = plugin.bingoConfig();
        if (config != null && !config.historyEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        String id = MatchRecord.generateId(now);
        String date = MatchRecord.formatDate(now);

        List<Team> participants = teams.all().stream()
                .filter(t -> t.size() > 0 || current.board().count(t.id()) > 0)
                .toList();
        if (participants.isEmpty()) {
            participants = teams.all();
        }

        List<Team> ranked = participants.stream()
                .sorted((a, b) -> {
                    int countA = current.board().count(a.id());
                    int countB = current.board().count(b.id());
                    if (countA != countB) {
                        return Integer.compare(countB, countA);
                    }
                    long timeA = current.board().lastClaimTime(a.id());
                    long timeB = current.board().lastClaimTime(b.id());
                    return Long.compare(timeA, timeB);
                })
                .toList();

        List<MatchRecord.TeamResult> rankingRecords = new ArrayList<>();
        for (Team team : ranked) {
            rankingRecords.add(new MatchRecord.TeamResult(
                    team.id(),
                    team.name(),
                    team.color().asHexString(),
                    current.board().count(team.id()),
                    teamPlayerNames(team)
            ));
        }

        MatchRecord.TeamResult winnerRecord = null;
        if (winner != null) {
            winnerRecord = new MatchRecord.TeamResult(
                    winner.id(),
                    winner.name(),
                    winner.color().asHexString(),
                    current.board().count(winner.id()),
                    teamPlayerNames(winner)
            );
        }

        MatchRecord.FirstClaim firstRecord = null;
        Round.FirstClaim first = current.firstClaim();
        if (first != null) {
            Team claimerTeam = teams.byId(first.teamId());
            String itemKey = current.card().item(first.cell());
            firstRecord = new MatchRecord.FirstClaim(
                    first.cell(),
                    itemKey,
                    first.teamId(),
                    claimerTeam != null ? claimerTeam.name() : "?",
                    first.elapsedMs(),
                    clock(first.elapsedMs())
            );
        }

        MatchRecord record = new MatchRecord(
                id,
                now,
                date,
                current.seed(),
                current.mode().name(),
                current.board().winMode().name(),
                current.board().isLockout(),
                current.elapsedMs(),
                clock(current.elapsedMs()),
                reason,
                winnerRecord,
                rankingRecords,
                firstRecord,
                current.card().items()
        );

        int maxSaved = config != null ? config.historyMaxSavedMatches() : 50;
        plugin.matchHistory().recordMatch(record, maxSaved);
    }

    private List<String> teamPlayerNames(Team team) {
        List<String> names = new ArrayList<>();
        for (UUID uuid : team.members()) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                names.add(online.getName());
            } else {
                String name = Bukkit.getOfflinePlayer(uuid).getName();
                names.add(name != null ? name : uuid.toString());
            }
        }
        return names;
    }

    private Messages messages() {
        return plugin.messages();
    }
}
