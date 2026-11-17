package com.jifercraft.bingo.game;

import com.cryptomorin.xseries.XMaterial;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.CardGenerator;
import com.jifercraft.bingo.card.ClaimBoard;
import com.jifercraft.bingo.card.ItemPool;
import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.compat.AdvancementSupport;
import com.jifercraft.bingo.compat.ServerVersion;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.ChatStyle;
import com.jifercraft.bingo.config.LateJoin;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.history.MatchHistory;
import com.jifercraft.bingo.history.MatchRecord;
import com.jifercraft.bingo.history.MatchSummary;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
    private final SetupMenu setupMenu;
    private final Hud hud;
    private final File activeRoundFile;
    private final QueuedFileWriter roundWriter;
    private final PendingRestores pendingRestores;
    private final TeamDisplay display;
    // Last time each player was told the card stays, so a held key is not a flood.
    private final Map<UUID, Long> cardNotice = new HashMap<>();
    private final AdvancementSupport advancements;
    // True while advancements are handed back; see awardQuietly.
    private boolean restoringAdvancements;
    private ItemMeta cardReference;
    private final Map<UUID, ItemStack[]> savedInventories = new HashMap<>();
    // Game mode each knocked out player had before turning spectator.
    private final Map<UUID, GameMode> savedGameModes = new HashMap<>();
    // Names seen while players were online, so the match record never has to
    // look up an offline player, which can mean reading their file from disk.
    private final Map<UUID, String> knownNames = new HashMap<>();
    private BukkitTask countdownTask;
    private BukkitTask clockTask;
    private BukkitTask sweepTask;
    private BukkitTask autosaveTask;
    // From /bingo reroll: the seed the next start uses unless one is given.
    private Long pendingSeed;
    private Round round;
    private BingoConfig roundConfig;
    private long roundTimeLimit;
    private int secondsLeft;
    private boolean roundRestoredUnfinished;

    public Game(BingoRush plugin, Teams teams) {
        this.plugin = plugin;
        this.teams = teams;
        this.cardMenu = new CardMenu(plugin, teams);
        this.teamMenu = new TeamMenu(plugin, this);
        this.setupMenu = new SetupMenu(plugin, this);
        this.hud = new Hud(plugin, this);
        this.display = new TeamDisplay(plugin);
        this.activeRoundFile = new File(plugin.getDataFolder(), "data/active-round.json");
        this.roundWriter = new QueuedFileWriter(activeRoundFile, plugin.getLogger());
        this.advancements = AdvancementSupport.load(plugin, this);
        this.pendingRestores = new PendingRestores(new File(plugin.getDataFolder(), "data/pending-restores.json"),
                plugin.getLogger(), advancements);
    }

    public Round round() {
        return round;
    }

    public Teams teams() {
        return teams;
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

    /** Opens the lobby team menu, where players join or switch teams. */
    public void openTeamMenu(Player player) {
        teamMenu.open(player);
    }

    /** Opens the host panel for round settings and launch. */
    public void openSetupMenu(Player player) {
        setupMenu.open(player);
    }

    /** A click inside one of our menus, already cancelled by the listener. */
    public void menuClick(Player player, MenuHolder menu, int slot) {
        if (menu.kind() == MenuHolder.Kind.TEAMS) {
            teamMenu.click(player, menu, slot);
        } else if (menu.kind() == MenuHolder.Kind.SETUP) {
            setupMenu.click(player, menu, slot);
        }
    }

    /** For names in the match record without looking up offline players. */
    public void rememberName(Player player) {
        knownNames.put(player.getUniqueId(), player.getName());
    }

    /** The advancement hooks, a no-op before 1.12. */
    public AdvancementSupport advancements() {
        return advancements;
    }

    private void loadAdvancementTab() {
        if (round == null || roundConfig == null || !roundConfig.advancementTab()) {
            return;
        }
        if (advancements.loadTab(round, roundConfig)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                advancements.syncTab(player, round, teams.of(player.getUniqueId()));
            }
        }
    }

    private void syncAdvancementTab(Team team) {
        if (round == null) {
            return;
        }
        for (UUID memberId : team.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null) {
                advancements.syncTab(member, round, team);
            }
        }
    }

    private void retireAdvancementTab() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            advancements.clearTab(player);
        }
        advancements.retireTab();
    }

    public void playSound(Player player, BingoConfig.SoundId id) {
        hud.play(id, player);
    }

    public void startRound(CommandSender caller, BingoGameMode modeOverride, Boolean lockoutOverride, String seedArg) {
        startRound(caller, modeOverride, null, lockoutOverride, null, null, seedArg);
    }

    /**
     * Starts a round, optionally overriding single settings. The host panel
     * passes all its picks; the command passes null for whatever it did not
     * name, which falls back to config.yml.
     */
    public void startRound(CommandSender caller, BingoGameMode modeOverride, WinMode winModeOverride,
            Boolean lockoutOverride, Long timeLimitOverride, Integer livesOverride, String seedArg) {
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
        if (mode == BingoGameMode.ADVANCEMENTS && !ServerVersion.atLeast(12)) {
            messages().send(caller, "round-advancements-unsupported");
            return;
        }
        WinMode winMode = winModeOverride != null ? winModeOverride : config.winMode();
        boolean lockout = lockoutOverride != null ? lockoutOverride : config.lockout();
        roundTimeLimit = timeLimitOverride != null ? timeLimitOverride : config.timeLimitSeconds();
        int lives = livesOverride != null ? livesOverride : config.lives();
        long seed = seedArg != null && !seedArg.trim().isEmpty()
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
        round = new Round(seed, mode, card, new ClaimBoard(winMode, lockout), lives);
        // The lobby menus are gone the moment a round exists, countdown included.
        teamMenu.closeAll();
        setupMenu.closeAll();
        // Loading the tab saves every player and rereads their advancements,
        // which can take a moment: better during the countdown than on the
        // tick the round starts, and before any inventory is cleared.
        loadAdvancementTab();
        display.applyAll(teams.all());
        int total = config.countdownSeconds();
        if (total > 0) {
            broadcast("round-countdown-chat", Placeholder.unparsed("count", String.valueOf(total)));
            messages().console("Countdown of <hl><seconds>s</hl> started, seed <hl><seed></hl><lockout>",
                    Placeholder.unparsed("seconds", String.valueOf(total)),
                    Placeholder.unparsed("seed", String.valueOf(seed)),
                    Placeholder.unparsed("lockout", lockout ? " [lockout]" : ""));
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
        messages().console("Round stopped by <hl><player></hl>, seed <hl><seed></hl>",
                Placeholder.unparsed("player", caller.getName()),
                Placeholder.unparsed("seed", String.valueOf(round.seed())));
        broadcast("round-stopped");
        endRound();
    }

    public void pauseRound(CommandSender caller) {
        if (round == null) {
            messages().send(caller, "round-not-running");
            return;
        }
        switch (round.state()) {
            case COUNTDOWN:
                messages().send(caller, "round-not-started");
                break;
            case PAUSED:
                messages().send(caller, "round-already-paused");
                break;
            default:
                round.pause();
                cancelClock();
                hud.pause();
                broadcast("round-paused");
                saveActiveRound();
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
        messages().console("Next card rerolled, seed <hl><seed></hl>",
                Placeholder.unparsed("seed", String.valueOf(pendingSeed)));
    }

    public void joinTeam(Player player, String teamName) {
        BingoConfig config = plugin.bingoConfig();
        Round current = round;
        if (current != null && current.isEliminated(player.getUniqueId())) {
            messages().send(player, "eliminated-cannot-join");
            return;
        }
        Team currentTeam = teams.of(player.getUniqueId());
        if (currentTeam != null) {
            messages().send(player, "team-already-in", teamPlaceholder(currentTeam));
            return;
        }
        if (current != null && current.state() != Round.State.COUNTDOWN
                && config != null && config.lateJoin() == LateJoin.SPECTATE) {
            lateJoinClosed(player);
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
        knownNames.put(player.getUniqueId(), player.getName());
        messages().send(player, "team-joined", teamPlaceholder(target));
        teamMenu.refreshAll();
        if (current != null) {
            // Joined during the countdown or a round that allows late play.
            // Someone who was only watching gets their game mode back to play.
            GameMode watching = savedGameModes.remove(player.getUniqueId());
            if (watching != null) {
                player.setGameMode(watching);
            }
            // The others had their inventory emptied when the round started;
            // without this a late joiner could bring a full backpack.
            boolean started = current.state() != Round.State.COUNTDOWN;
            if (started && roundConfig != null && roundConfig.clearInventories()
                    && !savedInventories.containsKey(player.getUniqueId())) {
                saveAndClearInventory(player);
            }
            giveCardItem(player);
            hud.playerJoined(player);
            display.apply(player, target);
            advancements.syncTab(player, current, target);
            revokeOwnedAdvancements(player, target);
        }
    }

    /** A player without a team watches the running round from spectator mode. */
    public void spectate(Player player) {
        if (round == null) {
            messages().send(player, "round-not-running");
            return;
        }
        Team team = teams.of(player.getUniqueId());
        if (team != null) {
            messages().send(player, "spectate-in-team", teamPlaceholder(team));
            return;
        }
        startSpectating(player);
        messages().send(player, "spectate-started");
    }

    // The round is closed to new players. Whoever runs the event is told but
    // never forced into spectator mode.
    private void lateJoinClosed(Player player) {
        if (!player.hasPermission("bingo.admin")) {
            startSpectating(player);
        }
        messages().send(player, "late-join-spectate");
    }

    // The mode they had goes in the same map as the one for knocked out
    // players, so it is saved with the round and given back when it ends,
    // even if they are offline by then.
    private void startSpectating(Player player) {
        savedGameModes.putIfAbsent(player.getUniqueId(), player.getGameMode());
        player.setGameMode(GameMode.SPECTATOR);
        if (round != null && round.state() != Round.State.COUNTDOWN) {
            saveActiveRound();
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
            display.clear(player);
            removeCardItems(player);
            advancements.clearTab(player);
            Map<String, List<String>> owed = advancements.takeOwned(player.getUniqueId());
            awardQuietly(() -> advancements.award(player, owed));
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
                messages().audience(player).sendActionBar(messages().render("compass-no-teammate"));
            }
            return;
        }
        player.setCompassTarget(nearest.getLocation());
        if (sendFeedback) {
            int distance = (int) Math.round(player.getLocation().distance(nearest.getLocation()));
            messages().audience(player).sendActionBar(messages().render("compass-tracking",
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

    // getItemInHand is the main hand on 1.9+ and the only hand on 1.8.
    @SuppressWarnings("deprecation")
    public boolean isHoldingCompass(Player player) {
        ItemStack held = player.getItemInHand();
        return held != null && held.getType() == Material.COMPASS;
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
        knownNames.put(target.getUniqueId(), target.getName());
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
        if (current == null || current.state() != Round.State.RUNNING || current.mode() != BingoGameMode.ITEMS
                || current.isEliminated(player.getUniqueId())) {
            return;
        }
        Team team = teams.of(player.getUniqueId());
        if (team == null) {
            return;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType() == Material.AIR) {
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
        knownNames.put(player.getUniqueId(), player.getName());
        if (roundRestoredUnfinished && player.hasPermission("bingo.admin")) {
            messages().send(player, "round-restored-admin");
        }
        if (round != null && advancements.tabLoaded()) {
            advancements.syncTab(player, round, teams.of(player.getUniqueId()));
        } else {
            advancements.clearTab(player);
        }
        if (round != null) {
            Team team = teams.of(player.getUniqueId());
            if (team != null && round.isEliminated(player.getUniqueId())) {
                hud.playerJoined(player);
                if (!player.isDead()) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
            } else if (team != null) {
                // Knocked out last round and offline when it ended: still a
                // spectator, which would leave them unable to play this one.
                pendingRestores.applyGameMode(player);
                hud.playerJoined(player);
                giveCardItem(player);
            } else {
                restoreInventory(player);
                awardQuietly(() -> pendingRestores.applyTo(player));
                removeCardItems(player);
                // After the pending restores, so a mode they were owed from
                // the last round is not saved as their own.
                BingoConfig config = plugin.bingoConfig();
                if (round.state() != Round.State.COUNTDOWN && config != null
                        && config.lateJoin() == LateJoin.SPECTATE
                        && !player.hasPermission("bingo.admin")
                        && !savedGameModes.containsKey(player.getUniqueId())) {
                    startSpectating(player);
                    messages().send(player, "late-join-spectate");
                }
            }
            if (team != null) {
                revokeOwnedAdvancements(player, team);
                display.apply(player, team);
            }
        } else {
            restoreInventory(player);
            awardQuietly(() -> pendingRestores.applyTo(player));
            // A member who was offline when the round ended, with
            // clear-inventories off, still carries the card.
            removeCardItems(player);
        }
    }

    /** True when the stack is the physical card item. */
    // Old versions have no item data to tag the card with, so it is told
    // apart by its name and lore. From 1.13 the server stores those as
    // components and may rewrite the color codes, so the comparison is
    // against a card read back the same way, not against the text we wrote.
    public boolean isCardItem(ItemStack stack) {
        if (stack == null || stack.getType() != Material.BOOK || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (!meta.hasDisplayName()) {
            return false;
        }
        if (meta.getDisplayName().endsWith(LegacyItems.CARD_MARKER)) {
            return true;
        }
        if (cardReference == null) {
            cardReference = buildCard().getItemMeta();
        }
        return meta.getDisplayName().equals(cardReference.getDisplayName())
                && Objects.equals(meta.getLore(), cardReference.getLore());
    }

    /** The language changed, so the card has another name from now on. */
    public void cardTextChanged() {
        cardReference = null;
    }

    private ItemStack buildCard() {
        return LegacyItems.item(XMaterial.BOOK,
                messages().legacy(messages().render("card-item-name")) + LegacyItems.CARD_MARKER,
                Collections.singletonList(messages().legacy(messages().render("card-item-lore"))), false);
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

    /** A click that would take the card out of its owner's inventory. */
    public boolean blocksCardClick(Player player, InventoryClickEvent event) {
        Inventory top = event.getInventory();
        int raw = event.getRawSlot();
        CardRules.Place place = raw < 0 ? CardRules.Place.OUTSIDE
                : raw < top.getSize() ? CardRules.Place.TOP : CardRules.Place.BOTTOM;
        CardRules.Click click = CardRules.Click.of(event.getClick().name());
        CardRules.Content swapSource = CardRules.Content.NONE;
        if (click == CardRules.Click.SWAP_WITH_HOTBAR) {
            swapSource = contentOf(player.getInventory().getItem(event.getHotbarButton()));
        } else if (click == CardRules.Click.SWAP_WITH_OFFHAND) {
            swapSource = contentOf(player.getInventory().getItem(40));
        }
        return CardRules.blocksClick(click, place, top.getType() != InventoryType.CRAFTING,
                contentOf(event.getCursor()), contentOf(event.getCurrentItem()), swapSource);
    }

    /** Dragging the card over a container would leave pieces of it there. */
    public boolean blocksCardDrag(InventoryDragEvent event) {
        if (!isCardItem(event.getOldCursor())) {
            return false;
        }
        int topSize = event.getInventory().getSize();
        boolean touchesTop = false;
        for (int slot : event.getRawSlots()) {
            touchesTop |= slot < topSize;
        }
        return CardRules.blocksDrag(true, touchesTop);
    }

    /**
     * Whether the card is in a hand. The off-hand has no API before 1.9, but
     * its slot in the inventory is 40 from then on.
     */
    @SuppressWarnings("deprecation")
    public boolean holdsCard(Player player) {
        if (isCardItem(player.getItemInHand())) {
            return true;
        }
        return ServerVersion.atLeast(9) && isCardItem(player.getInventory().getItem(40));
    }

    private CardRules.Content contentOf(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return CardRules.Content.NONE;
        }
        if (isCardItem(stack)) {
            return CardRules.Content.CARD;
        }
        return CardRules.isBundle(stack.getType().name()) ? CardRules.Content.BUNDLE : CardRules.Content.OTHER;
    }

    /** One short action bar line when the card refuses to leave, at most every two seconds. */
    public void cardLocked(Player player) {
        long now = System.currentTimeMillis();
        Long last = cardNotice.get(player.getUniqueId());
        if (last != null && now - last < 2000) {
            return;
        }
        cardNotice.put(player.getUniqueId(), now);
        messages().audience(player).sendActionBar(messages().render("card-locked"));
    }

    // The listeners block the ways out, but creative mode, another plugin or a
    // full inventory at the wrong moment can still lose the card. So every
    // second each player on a team ends up with exactly one, or none when they
    // are knocked out, and one is only given when there is a free slot.
    private void ensureCard(Player player, Round current) {
        if (teams.of(player.getUniqueId()) == null || player.isDead()) {
            return;
        }
        if (current.isEliminated(player.getUniqueId())) {
            removeCardItems(player);
            return;
        }
        ItemStack[] contents = player.getInventory().getContents();
        int found = 0;
        boolean extras = false;
        for (int i = 0; i < contents.length; i++) {
            if (isCardItem(contents[i])) {
                found++;
                if (found > 1) {
                    contents[i] = null;
                    extras = true;
                }
            }
        }
        if (extras) {
            player.getInventory().setContents(contents);
        }
        if (found == 0 && !isCardItem(player.getItemOnCursor()) && player.getInventory().firstEmpty() != -1) {
            giveCardItem(player);
        }
    }

    private void giveCardItem(Player player) {
        removeCardItems(player);
        ItemStack card = buildCard();
        // With a full inventory nothing is dropped: a card on the ground is
        // destroyed on purpose, and the sweep tries again once there is room.
        player.getInventory().addItem(card);
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
        // Drain the queue first, then write the last snapshot here: nothing
        // async can be scheduled once the plugin is disabling.
        roundWriter.close();
        if (round != null) {
            try {
                RoundStorage.save(activeRoundFile, round, roundTimeLimit, savedInventories, savedGameModes,
                        advancements.ownedSnapshot(), savedMembers());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not save active round state: " + e.getMessage());
            }
        }
        cancelTasks();
        cardMenu.closeAll();
        teamMenu.closeAll();
        setupMenu.closeAll();
        hud.hideAll();
        if (round == null) {
            restoreInventories();
        }
        removeCardItemsFromAll();
        retireAdvancementTab();
        advancements.silenceAnnouncements(false);
        display.clearAll();
        // Last, so whatever restoreInventories queued above reaches the disk.
        pendingRestores.close();
        round = null;
        roundConfig = null;
    }

    /** Chat of a team member during a round, off the main thread. */
    public void styleChat(AsyncPlayerChatEvent event) {
        display.styleChat(event);
    }

    /** After /bingo reload: names, order and chat format follow the new settings. */
    public void settingsReloaded() {
        display.clearAll();
        if (round != null) {
            display.applyAll(teams.all());
            if (round.state() != Round.State.COUNTDOWN) {
                // The books were collected before the reload; these carry the new text.
                for (Team team : teams.all()) {
                    for (UUID memberId : team.members()) {
                        Player member = Bukkit.getPlayer(memberId);
                        if (member != null && !member.isDead() && !round.isEliminated(memberId)) {
                            giveCardItem(member);
                        }
                    }
                }
            }
        }
    }

    /**
     * Here the card is recognised by its name and lore, so a reload that
     * changes them would orphan the books in the inventories. They are
     * collected first and handed out again by settingsReloaded.
     */
    public void cardsBeforeReload() {
        removeCardItemsFromAll();
    }

    /** A player left the server. */
    public void playerQuit(UUID playerId) {
        menuClosed(playerId);
        cardNotice.remove(playerId);
        display.forget(playerId);
    }

    /** A player closed one of our menus; stop tracking it everywhere. */
    public void menuClosed(UUID playerId) {
        cardMenu.forget(playerId);
        teamMenu.forget(playerId);
        setupMenu.forget(playerId);
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
        if (roundConfig.advancementChat() != ChatStyle.VANILLA) {
            advancements.silenceAnnouncements(true);
        }
        for (Team team : teams.all()) {
            for (UUID memberId : team.members()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member != null) {
                    revokeOwnedAdvancements(member, team);
                }
            }
        }
        if (roundConfig.scatterPlayers()) {
            scatterTeams();
        }
        broadcast("round-started-chat");
        messages().console("Round started (<hl><mode></hl>), seed <hl><seed></hl>",
                Placeholder.unparsed("mode", round.mode().name().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("seed", String.valueOf(round.seed())));
        hud.roundStarted(roundTimeLimit);
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
        long remaining = current.remainingMs(roundTimeLimit);
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
            messages().console("Round timed out with no claims, seed <hl><seed></hl>",
                    Placeholder.unparsed("seed", String.valueOf(current.seed())));
        } else {
            broadcast("round-time-up-chat",
                    teamPlaceholder(winner),
                    Placeholder.unparsed("count", String.valueOf(current.board().count(winner.id()))));
            messages().console("Round timed out, winner: <team><body>, seed <hl><seed></hl>",
                    teamPlaceholder(winner), Placeholder.unparsed("seed", String.valueOf(current.seed())));
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
        restoreGameModes();
        restoreOwnedAdvancements();
        removeCardItemsFromAll();
        retireAdvancementTab();
        advancements.silenceAnnouncements(false);
        display.clearAll();
        roundWriter.delete();
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
        String json;
        try {
            json = RoundStorage.toJson(round, roundTimeLimit, savedInventories, savedGameModes,
                    advancements.ownedSnapshot(), savedMembers());
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not save active round state: " + e.getMessage());
            return;
        }
        roundWriter.write(json);
    }

    private List<TeamMembership.Member> savedMembers() {
        List<TeamMembership.Member> members = new ArrayList<>();
        for (Team team : teams.all()) {
            for (UUID memberId : team.members()) {
                members.add(new TeamMembership.Member(memberId, team.name(), team.id()));
            }
        }
        return members;
    }

    // Members come back before the HUD is built, so the sidebar and the tab
    // know who is on each team. A team the config did not list was made at
    // runtime, so it is made again from its saved name.
    private void restoreTeamMembers(List<TeamMembership.Member> members) {
        if (members.isEmpty()) {
            return;
        }
        if (teams.all().isEmpty()) {
            Map<Integer, String> namesById = new TreeMap<>();
            for (TeamMembership.Member member : members) {
                namesById.putIfAbsent(member.teamId(), member.teamName());
            }
            for (Map.Entry<Integer, String> entry : namesById.entrySet()) {
                if (teams.byName(entry.getValue()) == null) {
                    teams.createAuto(entry.getValue(), entry.getKey());
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (Team team : teams.all()) {
            names.add(team.name());
        }
        Map<UUID, Integer> placement = TeamMembership.resolve(members, names);
        for (TeamMembership.Member member : members) {
            Integer teamId = placement.get(member.playerId());
            Team team = teamId == null ? null : teams.byId(teamId);
            if (team == null) {
                plugin.getLogger().warning("The saved team '" + member.teamName()
                        + "' for " + member.playerId() + " is gone from the config, so that player stays out");
                continue;
            }
            team.add(member.playerId());
        }
    }

    // Only matters for a reload with players already connected; after a real
    // restart everyone comes back through playerJoined.
    private void restoreOnlineMembers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Team team = teams.of(player.getUniqueId());
            if (team == null) {
                continue;
            }
            if (round.isEliminated(player.getUniqueId())) {
                if (!player.isDead()) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
                removeCardItems(player);
            } else {
                giveCardItem(player);
            }
            revokeOwnedAdvancements(player, team);
            advancements.syncTab(player, round, team);
        }
    }

    public void loadSavedRoundIfPresent() {
        if (!RoundStorage.exists(activeRoundFile)) {
            return;
        }
        try {
            RoundStorage.RestoredRound restored = RoundStorage.load(activeRoundFile);
            restoreTeamMembers(restored.members());
            this.round = restored.round();
            this.roundConfig = plugin.bingoConfig();
            this.roundTimeLimit = restored.timeLimitSeconds();
            this.savedInventories.clear();
            this.savedInventories.putAll(restored.savedInventories());
            this.savedGameModes.clear();
            this.savedGameModes.putAll(restored.savedGameModes());
            this.advancements.loadOwned(restored.revokedAdvancements());
            this.roundRestoredUnfinished = true;

            hud.restorePaused(restored.timeLimitSeconds());
            // The tab is not saved anywhere; the restart wiped it from the server.
            loadAdvancementTab();
            display.applyAll(teams.all());
            restoreOnlineMembers();
            // Shutdown put the announceAdvancements rule back; the round needs it off again.
            if (roundConfig != null && roundConfig.advancementChat() != ChatStyle.VANILLA) {
                advancements.silenceAnnouncements(true);
            }

            messages().console("Found an unfinished round from the last session (seed <hl><seed></hl>),"
                    + " restored paused. Run <hl>/bingo resume</hl> when players are back.",
                    Placeholder.unparsed("seed", String.valueOf(round.seed())));

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
            ensureCard(player, current);
        }
    }

    private void claimCell(Round current, Team team, int cell, Player claimer) {
        if (!current.board().claim(cell, team.id(), System.currentTimeMillis())) {
            return;
        }
        current.recordFirstClaim(cell, team.id(), current.elapsedMs());
        String itemKey = current.card().item(cell);
        TagResolver item = Placeholder.component("item", itemName(itemKey));
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
                messages().audience(member).sendActionBar(bar);
            }
        }
        messages().console("<team><body> claimed <hl><item></hl> (cell <cell>), <hl><count></hl> cells, by <player>",
                teamPlaceholder(team), Placeholder.unparsed("item", itemKey),
                Placeholder.unparsed("cell", String.valueOf(cell)), Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("player", claimer.getName()));
        if (current.board().isLockout()) {
            hud.lockoutLost(team);
            Component rivalBar = messages().render("lockout-cell-lost-actionbar", item, teamPlaceholder(team));
            for (Player player : Bukkit.getOnlinePlayers()) {
                Team playerTeam = teams.of(player.getUniqueId());
                if (playerTeam != null && playerTeam.id() != team.id()) {
                    messages().audience(player).sendActionBar(rivalBar);
                }
            }
        }
        hud.claimHappened(team);
        cardMenu.refresh(current);
        syncAdvancementTab(team);

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
    private boolean checkAdvancementClaim(Player player, String key) {
        Round current = round;
        if (current == null || current.state() != Round.State.RUNNING || current.mode() != BingoGameMode.ADVANCEMENTS
                || current.isEliminated(player.getUniqueId())) {
            return false;
        }
        if (key.startsWith("minecraft:recipes/")) {
            return false;
        }
        Team team = teams.of(player.getUniqueId());
        if (team == null) {
            return false;
        }
        boolean claimed = false;
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            if (current.board().isClaimed(cell, team.id())) {
                continue;
            }
            if (key.equalsIgnoreCase(current.card().item(cell))) {
                claimAdvancementCell(current, team, cell, player, key);
                claimed = true;
            }
        }
        return claimed;
    }

    /**
     * An advancement for anyone on the server, from the 1.12 listener. Team
     * members in a round may claim a cell with it. Spigot cannot change the
     * vanilla line, so during a round the support code turns the
     * announceAdvancements rule off and the custom line comes from here.
     */
    public void advancementDone(Player player, String key, boolean announced) {
        if (restoringAdvancements) {
            return;
        }
        Team team = teams.of(player.getUniqueId());
        // Read before the claim: a winning claim ends the round and drops roundConfig.
        boolean inRound = round != null && team != null;
        ChatStyle style = inRound && roundConfig != null ? roundConfig.advancementChat() : ChatStyle.VANILLA;
        boolean claimed = checkAdvancementClaim(player, key);
        // A claimed cell already got its own line from the claim.
        if (!inRound || !announced || claimed || style != ChatStyle.CUSTOM) {
            return;
        }
        broadcast("advancement-message",
                Placeholder.unparsed("player", player.getName()),
                teamPlaceholder(team),
                Placeholder.component("advancement", advancementName(key)));
    }

    private void claimAdvancementCell(Round current, Team team, int cell, Player claimer, String key) {
        if (!current.board().claim(cell, team.id(), System.currentTimeMillis())) {
            return;
        }
        current.recordFirstClaim(cell, team.id(), current.elapsedMs());
        Component advTitle = advancementName(key);

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
                messages().audience(member).sendActionBar(bar);
                messages().audience(member).showTitle(title);
            }
        }

        messages().console("<team><body> claimed advancement <hl><item></hl> (cell <cell>), <hl><count></hl> cells, by <player>",
                teamPlaceholder(team), Placeholder.unparsed("item", key),
                Placeholder.unparsed("cell", String.valueOf(cell)), Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("player", claimer.getName()));
        if (current.board().isLockout()) {
            hud.lockoutLost(team);
            Component rivalBar = messages().render("lockout-advancement-lost-actionbar", adv, item, teamPlaceholder(team));
            for (Player player : Bukkit.getOnlinePlayers()) {
                Team playerTeam = teams.of(player.getUniqueId());
                if (playerTeam != null && playerTeam.id() != team.id()) {
                    messages().audience(player).sendActionBar(rivalBar);
                }
            }
        }
        hud.claimHappened(team);
        cardMenu.refresh(current);
        syncAdvancementTab(team);

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
     * A team member died. The card item never drops; with limited lives the
     * death is counted, and the last one turns the player into a spectator
     * when they respawn.
     */
    public void playerDied(PlayerDeathEvent event) {
        Round current = round;
        Player player = event.getEntity();
        Team team = teams.of(player.getUniqueId());
        if (current == null || team == null) {
            return;
        }
        // Handed back on respawn; on the ground anyone could pick it up.
        event.getDrops().removeIf(this::isCardItem);
        boolean counted = current.state() == Round.State.RUNNING && current.lives() > 0;
        boolean eliminated = counted && current.recordDeath(player.getUniqueId());
        boolean customLine = styleDeathMessage(event, current, team, counted, eliminated);
        if (!counted) {
            return;
        }
        if (!eliminated) {
            int left = current.livesLeft(player.getUniqueId());
            if (left > 0) {
                messages().send(player, left == 1 ? "lives-left-one" : "lives-left",
                        Placeholder.unparsed("lives", String.valueOf(left)));
            }
            saveActiveRound();
            return;
        }
        savedGameModes.putIfAbsent(player.getUniqueId(), player.getGameMode());
        if (!customLine) {
            // The custom death line already says it; the others do not.
            broadcast("player-eliminated", Placeholder.unparsed("player", player.getName()), teamPlaceholder(team));
        }
        messages().console("<hl><player></hl> is out of lives (<team><body>)",
                Placeholder.unparsed("player", player.getName()), teamPlaceholder(team));
        if (current.teamsStanding(Collections.singletonMap(team.id(), team.members())).isEmpty()) {
            broadcast("team-eliminated", teamPlaceholder(team));
        }
        saveActiveRound();
        // Not from inside the death event: ending the round restores
        // inventories, and the server empties this one right after the event.
        Bukkit.getScheduler().runTask(plugin, () -> checkStanding(current));
    }

    /**
     * Swaps the death line as round-chat.deaths says. True when the custom
     * template went out. A null line means the showDeathMessages game rule is
     * off, and that stays respected.
     */
    private boolean styleDeathMessage(PlayerDeathEvent event, Round current, Team team, boolean counted,
            boolean eliminated) {
        ChatStyle style = roundConfig != null ? roundConfig.deathChat() : ChatStyle.CUSTOM;
        String vanilla = event.getDeathMessage();
        if (vanilla == null || vanilla.isEmpty() || style == ChatStyle.VANILLA) {
            return false;
        }
        if (style == ChatStyle.HIDDEN) {
            event.setDeathMessage(null);
            return false;
        }
        String key = eliminated ? "death-message-eliminated" : counted ? "death-message-lives" : "death-message";
        int left = Math.max(0, current.livesLeft(event.getEntity().getUniqueId()));
        event.setDeathMessage(messages().legacy(messages().render(key,
                Placeholder.component("message", LegacyComponentSerializer.legacySection().deserialize(vanilla)),
                teamPlaceholder(team),
                Placeholder.unparsed("lives", String.valueOf(left)))));
        return true;
    }

    /** Puts knocked out players into spectator and hands the card back to the rest. */
    public void playerRespawned(Player player) {
        Round current = round;
        if (current == null) {
            // The round ended while this player was on the death screen.
            awardQuietly(() -> pendingRestores.applyTo(player));
            return;
        }
        if (teams.of(player.getUniqueId()) == null) {
            return;
        }
        if (current.isEliminated(player.getUniqueId())) {
            player.setGameMode(GameMode.SPECTATOR);
            messages().send(player, "eliminated-spectator");
            return;
        }
        giveCardItem(player);
    }

    // The last team standing wins, but only when there was someone to beat:
    // a host testing alone must not win the moment they die.
    private void checkStanding(Round current) {
        if (round != current || current.state() != Round.State.RUNNING) {
            return;
        }
        Map<Integer, Set<UUID>> membersByTeam = new HashMap<>();
        for (Team team : teams.all()) {
            if (team.size() > 0) {
                membersByTeam.put(team.id(), team.members());
            }
        }
        List<Integer> standing = current.teamsStanding(membersByTeam);
        if (standing.isEmpty()) {
            finishByElimination(current);
        } else if (standing.size() == 1 && membersByTeam.size() > 1) {
            Team winner = teams.byId(standing.get(0));
            if (winner != null) {
                winByLastTeam(current, winner);
            }
        }
    }

    private void winByLastTeam(Round current, Team winner) {
        String time = clock(current.elapsedMs());
        broadcast("round-last-team-chat", teamPlaceholder(winner), Placeholder.unparsed("time", time));
        messages().console("Round won by <team><body>, last team standing, seed <hl><seed></hl>",
                teamPlaceholder(winner), Placeholder.unparsed("seed", String.valueOf(current.seed())));
        broadcastBreakdown(current);
        recordMatchResult(current, winner, "LAST_TEAM");
        hud.won(winner);
        endRound();
    }

    // Everyone is out: the round ends like a timeout, most cells first.
    private void finishByElimination(Round current) {
        Team winner = teams.byId(current.board().leader().orElse(-1));
        broadcast("round-all-eliminated");
        if (winner != null) {
            broadcast("round-all-eliminated-winner", teamPlaceholder(winner),
                    Placeholder.unparsed("count", String.valueOf(current.board().count(winner.id()))));
        }
        messages().console("Every team is out of lives, seed <hl><seed></hl>",
                Placeholder.unparsed("seed", String.valueOf(current.seed())));
        broadcastBreakdown(current);
        recordMatchResult(current, winner, "ELIMINATION");
        hud.timeUp();
        endRound();
    }

    private void winByCompletion(Round current, Team winner) {
        String time = clock(current.elapsedMs());
        broadcast("round-won-chat", teamPlaceholder(winner), Placeholder.unparsed("time", time));
        messages().console("Round won by <team><body> in <hl><time></hl>, seed <hl><seed></hl>",
                teamPlaceholder(winner), Placeholder.unparsed("time", time),
                Placeholder.unparsed("seed", String.valueOf(current.seed())));
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
                .collect(Collectors.toList());
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
                .collect(Collectors.toList());

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
            TagResolver item = Placeholder.component("item", current.mode() == BingoGameMode.ADVANCEMENTS
                    ? advancementName(itemKey) : itemName(itemKey));
            TagResolver teamTag = claimerTeam != null ? teamPlaceholder(claimerTeam) : Placeholder.unparsed("team", "?");
            broadcast("breakdown.first-claim", item, teamTag, Placeholder.unparsed("time", clock(first.elapsedMs())));
        } else {
            broadcast("breakdown.first-claim-none");
        }

        broadcast("breakdown.total-time", Placeholder.unparsed("time", clock(current.elapsedMs())));
        broadcast("breakdown.separator");
    }

    // XMaterial folds the pre-1.13 data values in, so wool colors and log
    // types compare right on every version.
    private boolean carriedMatches(Round current, int cell, ItemStack stack) {
        XMaterial wanted = roundConfig.material(current.card().item(cell));
        return wanted != null && wanted == XMaterial.matchXMaterial(stack);
    }

    /**
     * An item for chat. From 1.13 on translation keys follow the item id, so
     * each player reads it in their own language; older versions get the
     * name spelled out from the id.
     */
    private Component itemName(String itemKey) {
        XMaterial material = roundConfig != null ? roundConfig.material(itemKey) : null;
        String id = itemKey.substring(itemKey.indexOf(':') + 1);
        if (material == null || !ServerVersion.atLeast(13)) {
            return Component.text(LegacyItems.prettyKey(itemKey));
        }
        Material bukkit = material.get();
        boolean block = bukkit != null && bukkit.isBlock();
        return Component.translatable((block ? "block.minecraft." : "item.minecraft.") + id);
    }

    /** A vanilla advancement title for chat, translated on the player's side. */
    public static Component advancementName(String key) {
        if (!key.startsWith("minecraft:")) {
            return Component.text(LegacyItems.prettyKey(key));
        }
        String path = key.substring("minecraft:".length()).replace('/', '.');
        return Component.translatable("advancements." + path + ".title");
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
                if (member != null) {
                    saveAndClearInventory(member);
                }
            }
        }
    }

    private void saveAndClearInventory(Player player) {
        savedInventories.put(player.getUniqueId(), player.getInventory().getContents());
        player.getInventory().clear();
    }

    // Players who are offline right now get theirs on their next login.
    private void restoreInventories() {
        for (Map.Entry<UUID, ItemStack[]> entry : savedInventories.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            // A player on the death screen respawns with an empty inventory
            // unless keepInventory is on, so theirs waits for the respawn.
            if (player != null && !player.isDead()) {
                player.getInventory().setContents(entry.getValue());
            } else {
                pendingRestores.addInventory(entry.getKey(), entry.getValue());
            }
        }
        savedInventories.clear();
    }

    // Only advancement rounds, and only once the round runs: during the
    // countdown nobody can claim anything yet.
    private void revokeOwnedAdvancements(Player player, Team team) {
        Round current = round;
        if (current == null || current.mode() != BingoGameMode.ADVANCEMENTS
                || current.state() == Round.State.COUNTDOWN) {
            return;
        }
        advancements.revokeOwned(player, current, team);
    }

    private void restoreOwnedAdvancements() {
        for (UUID playerId : advancements.ownedPlayers()) {
            Map<String, List<String>> owed = advancements.takeOwned(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && !player.isDead()) {
                awardQuietly(() -> advancements.award(player, owed));
            } else {
                pendingRestores.addAdvancements(playerId, owed);
            }
        }
    }

    // Handing an advancement back fires PlayerAdvancementDoneEvent like any
    // award: without the guard it would claim a cell and print the vanilla
    // line for something the player earned long ago.
    private void awardQuietly(Runnable award) {
        restoringAdvancements = true;
        try {
            award.run();
        } finally {
            restoringAdvancements = false;
        }
    }

    private void restoreGameModes() {
        for (Map.Entry<UUID, GameMode> entry : savedGameModes.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && !player.isDead()) {
                player.setGameMode(entry.getValue());
            } else {
                pendingRestores.addGameMode(entry.getKey(), entry.getValue());
            }
        }
        savedGameModes.clear();
    }

    // One spot per team around the world spawn, so teams start apart but
    // together, and no member stacks on another.
    private void scatterTeams() {
        World world = Bukkit.getWorlds().get(0);
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
        if (query == null || query.trim().isEmpty()) {
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
        MatchHistory history = plugin.matchHistory();
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
            Component winnerComp = Component.text(summary.winnerName(), hexColor(summary.winnerColor()));
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
        MatchSummary summary = plugin.matchHistory().summary(id);
        if (summary == null) {
            messages().send(sender, "history.not-found", Placeholder.unparsed("id", id));
            return;
        }
        plugin.matchHistory().loadMatchAsync(summary.id()).thenAccept(record -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player && !((Player) sender).isOnline()) {
                    return;
                }
                if (record == null) {
                    messages().send(sender, "history.not-found", Placeholder.unparsed("id", id));
                } else {
                    sendMatchDetails(sender, record);
                }
            });
        });
    }

    private void sendMatchDetails(CommandSender sender, MatchRecord record) {
        messages().send(sender, "history.detail-header", Placeholder.unparsed("id", record.id()));
        messages().send(sender, "history.detail-date", Placeholder.unparsed("date", record.date()));
        messages().send(sender, "history.detail-mode",
                Placeholder.unparsed("mode", record.gameMode().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("win_mode", record.winMode()),
                Placeholder.unparsed("lockout", record.lockout() ? " | Lockout" : ""));
        messages().send(sender, "history.detail-duration", Placeholder.unparsed("time", record.durationFormatted()));
        messages().send(sender, "history.detail-seed", Placeholder.unparsed("seed", String.valueOf(record.seed())));

        if (record.winner() != null) {
            Component winnerComp = Component.text(record.winner().name(), hexColor(record.winner().color()));
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
                Component teamComp = Component.text(team.name(), hexColor(team.color()));
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
                itemTag = Placeholder.component("item", advancementName(first.item()));
            } else {
                itemTag = Placeholder.component("item", itemName(first.item()));
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
        MatchSummary summary = plugin.matchHistory().summary(id);
        if (summary == null) {
            messages().send(sender, "history.not-found", Placeholder.unparsed("id", id));
            return;
        }
        messages().send(sender, "history.exported",
                Placeholder.unparsed("id", summary.id()),
                Placeholder.unparsed("file", summary.id() + ".json"));
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
                .collect(Collectors.toList());
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
                .collect(Collectors.toList());

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
            names.add(online != null ? online.getName() : knownNames.getOrDefault(uuid, uuid.toString()));
        }
        return names;
    }

    // Match files can be edited by hand, and fromHexString throws on null.
    private static TextColor hexColor(String hex) {
        TextColor color = hex == null ? null : TextColor.fromHexString(hex);
        return color != null ? color : NamedTextColor.WHITE;
    }

    private Messages messages() {
        return plugin.messages();
    }
}
