package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.arena.ArenaManager;
import com.jifercraft.bingo.world.MatchWorldManager;
import com.jifercraft.bingo.api.events.BingoClaimEvent;
import com.jifercraft.bingo.api.events.BingoRoundEndEvent;
import com.jifercraft.bingo.api.events.BingoRoundStartEvent;
import com.jifercraft.bingo.api.events.BingoTeamJoinEvent;
import com.jifercraft.bingo.api.events.BingoTeamLeaveEvent;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.CardGenerator;
import com.jifercraft.bingo.card.ClaimBoard;
import com.jifercraft.bingo.card.ItemPool;
import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.ChatStyle;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.config.LateJoin;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.history.MatchRecord;
import com.jifercraft.bingo.history.MatchSummary;
import io.papermc.paper.advancement.AdvancementDisplay;
import java.io.File;
import java.time.Duration;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
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
    private final QueuedFileWriter roundWriter;
    private final PendingRestores pendingRestores;
    private final TeamDisplay display;
    // Last time each player was told the card stays, so a held key is not a flood.
    private final Map<UUID, Long> cardNotice = new HashMap<>();
    private final CardAdvancements cardAdvancements;
    private final OwnedAdvancements ownedAdvancements = new OwnedAdvancements();
    // True while advancements are handed back; see awardQuietly.
    private boolean restoringAdvancements;
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
    private SignManager signManager;
    private final NamespacedKey lobbyVoteTag;
    private final NamespacedKey lobbyTeamsTag;
    private final NamespacedKey lobbyLeaveTag;
    private String selectedMapId;
    // The arena the current round is played in; null for the main world.
    private String roundArenaId;
    // The arena as the round plays it: its own settings with the votes applied.
    private GameMapDefinition roundArena;
    // Inventories and game modes of players in a waiting room, on disk, so
    // a crash before the round starts does not keep their items.
    private final PendingRestores waitingBackup;

    public Game(BingoRush plugin, Teams teams) {
        this.plugin = plugin;
        this.teams = teams;
        this.cardTag = new NamespacedKey(plugin, "card-menu");
        this.cardItemTag = new NamespacedKey(plugin, "card-item");
        this.lobbyVoteTag = new NamespacedKey(plugin, "lobby-vote-item");
        this.lobbyTeamsTag = new NamespacedKey(plugin, "lobby-teams-item");
        this.lobbyLeaveTag = new NamespacedKey(plugin, "lobby-leave-item");
        this.cardMenu = new CardMenu(plugin, teams, cardTag);
        this.teamMenu = new TeamMenu(plugin, this, new NamespacedKey(plugin, "team-menu"));
        this.hud = new Hud(plugin, this);
        this.display = new TeamDisplay(plugin);
        this.activeRoundFile = new File(plugin.getDataFolder(), "data/active-round.json");
        this.roundWriter = new QueuedFileWriter(activeRoundFile, plugin.getLogger());
        this.cardAdvancements = new CardAdvancements(plugin);
        this.pendingRestores = new PendingRestores(new File(plugin.getDataFolder(), "data/pending-restores.json"),
                plugin.getLogger());
        this.waitingBackup = new PendingRestores(new File(plugin.getDataFolder(), "data/waiting-room.json"),
                plugin.getLogger());
    }

    private ArenaManager arenaManager;

    public void arenaManager(ArenaManager arenaManager) {
        this.arenaManager = arenaManager;
    }

    public ArenaManager arenaManager() {
        return arenaManager;
    }

    private MatchWorldManager matchWorldManager;
    private ModeVoteManager modeVoteManager;

    public void matchWorldManager(MatchWorldManager matchWorldManager) {
        this.matchWorldManager = matchWorldManager;
    }

    public MatchWorldManager matchWorldManager() {
        return matchWorldManager;
    }

    public void modeVoteManager(ModeVoteManager modeVoteManager) {
        this.modeVoteManager = modeVoteManager;
    }

    public ModeVoteManager modeVoteManager() {
        return modeVoteManager;
    }

    public GameMapDefinition getArena(String mapId) {
        return mapId == null || arenaManager == null ? null : arenaManager.getArena(mapId);
    }

    /** The arena the next round uses: the one picked last, or the first one. */
    public String selectedMapId() {
        if (selectedMapId != null && getArena(selectedMapId) == null) {
            selectedMapId = null;
        }
        if (selectedMapId == null && arenaManager != null && !arenaManager.getAllArenas().isEmpty()) {
            selectedMapId = arenaManager.getAllArenas().getFirst().id();
        }
        return selectedMapId;
    }

    /** The arena the round in progress is played in, or null. */
    public String roundArenaId() {
        return round == null ? null : roundArenaId;
    }

    public void arenaRemoved(String arenaId) {
        if (arenaId.equals(selectedMapId)) {
            selectedMapId = null;
        }
    }

    public boolean isMapSelected(String mapId) {
        if (mapId == null) {
            return false;
        }
        String current = selectedMapId();
        return current != null && current.equalsIgnoreCase(mapId);
    }

    /**
     * Picks the arena for the next round. Refused while a round runs, and
     * while players are already waiting in another arena's room: switching
     * under them would start their round somewhere else.
     */
    public boolean selectMap(String mapId) {
        if (isMapSelected(mapId)) {
            return true;
        }
        if (round != null || !waitingPlayers().isEmpty()) {
            return false;
        }
        this.selectedMapId = mapId;
        if (signManager != null) {
            signManager.updateAll();
        }
        return true;
    }

    /** Players in a waiting room right now: their inventory is saved and no round runs. */
    public List<UUID> waitingPlayers() {
        if (round != null) {
            return List.of();
        }
        List<UUID> waiting = new ArrayList<>();
        for (UUID id : savedInventories.keySet()) {
            if (Bukkit.getPlayer(id) != null) {
                waiting.add(id);
            }
        }
        return waiting;
    }

    public boolean isLobbyVoteItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(lobbyVoteTag, PersistentDataType.BYTE);
    }

    public boolean isLobbyTeamsItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(lobbyTeamsTag, PersistentDataType.BYTE);
    }

    public boolean isLobbyLeaveItem(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(lobbyLeaveTag, PersistentDataType.BYTE);
    }

    public boolean isLobbyItem(ItemStack stack) {
        return isLobbyVoteItem(stack) || isLobbyTeamsItem(stack) || isLobbyLeaveItem(stack);
    }

    public boolean votingEnabled() {
        return plugin.getConfig().getBoolean("match-voting.enabled", true);
    }

    public void giveLobbyWaitingItems(Player player) {
        removeLobbyWaitingItems(player);
        ItemStack voteItem = ItemType.NETHER_STAR.createItemStack();
        voteItem.editMeta(meta -> {
            meta.displayName(messages().render("lobby-item-vote"));
            meta.lore(List.of(messages().render("lobby-item-vote-lore")));
            meta.getPersistentDataContainer().set(lobbyVoteTag, PersistentDataType.BYTE, (byte) 1);
        });
        ItemStack teamsItem = ItemType.OAK_DOOR.createItemStack();
        teamsItem.editMeta(meta -> {
            meta.displayName(messages().render("lobby-item-teams"));
            meta.lore(List.of(messages().render("lobby-item-teams-lore")));
            meta.getPersistentDataContainer().set(lobbyTeamsTag, PersistentDataType.BYTE, (byte) 1);
        });
        ItemStack leaveItem = ItemType.RED_BED.createItemStack();
        leaveItem.editMeta(meta -> {
            meta.displayName(messages().render("lobby-item-leave"));
            meta.lore(List.of(messages().render("lobby-item-leave-lore")));
            meta.getPersistentDataContainer().set(lobbyLeaveTag, PersistentDataType.BYTE, (byte) 1);
        });
        if (votingEnabled()) {
            player.getInventory().setItem(0, voteItem);
        }
        player.getInventory().setItem(4, teamsItem);
        player.getInventory().setItem(8, leaveItem);
    }

    public void enterWaitingLobby(Player player, GameMapDefinition arena) {
        UUID id = player.getUniqueId();
        if (!savedInventories.containsKey(id)) {
            waitingBackup.addInventory(id, player.getInventory().getContents());
            saveAndClearInventory(player);
        } else {
            player.getInventory().clear();
        }
        if (!savedGameModes.containsKey(id)) {
            savedGameModes.put(id, player.getGameMode());
            waitingBackup.addGameMode(id, player.getGameMode());
        }
        player.setGameMode(GameMode.ADVENTURE);
        giveLobbyWaitingItems(player);
    }

    public void leaveWaitingLobby(Player player) {
        Team team = teams.of(player.getUniqueId());
        if (team != null) {
            team.remove(player.getUniqueId());
            display.apply(player, null);
        }
        removeLobbyWaitingItems(player);
        ItemStack[] saved = savedInventories.remove(player.getUniqueId());
        if (saved != null) {
            player.getInventory().clear();
            player.getInventory().setContents(saved);
        }
        GameMode gm = savedGameModes.remove(player.getUniqueId());
        waitingBackup.forget(player.getUniqueId());
        if (gm != null) {
            player.setGameMode(gm);
        } else {
            player.setGameMode(GameMode.SURVIVAL);
        }
        Location lobby = lobbyLocation();
        if (lobby != null) {
            player.teleport(lobby);
        }
        if (signManager != null) {
            signManager.updateAll();
        }
        teamMenu.refreshAll();
        messages().send(player, "team-left");
        String arenaId = matchWorldManager != null ? matchWorldManager.arenaIdOf(player.getWorld()) : null;
        if (arenaId == null) {
            arenaId = selectedMapId;
        }
        if (arenaId != null && modeVoteManager != null) {
            modeVoteManager.onPlayerLeave(player.getUniqueId(), arenaId);
        }
    }

    public void removeLobbyWaitingItems(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (isLobbyItem(contents[i])) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) {
            player.getInventory().setContents(contents);
        }
    }

    public void removeLobbyWaitingItemsFromAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeLobbyWaitingItems(player);
        }
    }

    public SignManager signManager() {
        return signManager;
    }

    public void signManager(SignManager signManager) {
        this.signManager = signManager;
    }

    public int timeLeftSeconds() {
        return round != null ? secondsLeft : 0;
    }

    public String formattedTimeLeft() {
        if (round == null || roundTimeLimit <= 0) {
            return "∞";
        }
        int minutes = secondsLeft / 60;
        int seconds = secondsLeft % 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    public Team leadingTeam() {
        if (round == null) {
            return null;
        }
        Team leading = null;
        int max = -1;
        for (Team team : teams.all()) {
            int score = round.board().count(team.id());
            if (score > max && score > 0) {
                max = score;
                leading = team;
            }
        }
        return leading;
    }

    public int leadingScore() {
        Team leader = leadingTeam();
        if (leader == null || round == null) {
            return 0;
        }
        return round.board().count(leader.id());
    }

    public void setLobby(Player player) {
        Location loc = player.getLocation();
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("lobby.world", loc.getWorld().getName());
        cfg.set("lobby.x", loc.getX());
        cfg.set("lobby.y", loc.getY());
        cfg.set("lobby.z", loc.getZ());
        cfg.set("lobby.yaw", loc.getYaw());
        cfg.set("lobby.pitch", loc.getPitch());
        plugin.saveConfig();
        plugin.reloadSettings();
        messages().send(player, "lobby-set",
                Placeholder.unparsed("world", loc.getWorld().getName()),
                Placeholder.unparsed("x", String.format(Locale.ROOT, "%.1f", loc.getX())),
                Placeholder.unparsed("y", String.format(Locale.ROOT, "%.1f", loc.getY())),
                Placeholder.unparsed("z", String.format(Locale.ROOT, "%.1f", loc.getZ())));
    }

    public Location lobbyLocation() {
        BingoConfig cfg = plugin.bingoConfig();
        return (cfg != null && cfg.hasConfiguredLobby()) ? cfg.lobbyLocation() : null;
    }

    /**
     * True when a connecting player should appear at the lobby:
     * the lobby is configured, and the player is not reconnecting to an
     * active match they participate in.
     */
    public boolean shouldSpawnAtLobby(UUID playerId) {
        if (lobbyLocation() == null) {
            return false;
        }
        if (round != null && teams.of(playerId) != null) {
            // Player disconnected during an active round they are part of;
            // they must return to their place in the match.
            return false;
        }
        return true;
    }

    public void teleportToLobby(Player player) {
        BingoConfig cfg = plugin.bingoConfig();
        if (cfg == null || !cfg.hasConfiguredLobby()) {
            messages().send(player, "lobby-not-set");
            return;
        }
        Location loc = cfg.lobbyLocation();
        if (loc != null) {
            player.teleport(loc);
            messages().send(player, "lobby-teleported");
        } else {
            messages().send(player, "lobby-not-set");
        }
    }

    public void teleportAllToLobby() {
        BingoConfig cfg = plugin.bingoConfig();
        if (cfg == null || !cfg.hasConfiguredLobby()) {
            return;
        }
        Location loc = cfg.lobbyLocation();
        if (loc == null) {
            return;
        }
        for (Team team : teams.all()) {
            for (UUID uuid : team.members()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    player.teleport(loc);
                    messages().send(player, "lobby-teleported");
                }
            }
        }
    }

    public void randomJoin(Player player) {
        Team currentTeam = teams.of(player.getUniqueId());
        if (currentTeam != null) {
            messages().send(player, "team-already-in", teamPlaceholder(currentTeam));
            return;
        }
        BingoConfig config = plugin.bingoConfig();
        int maxPerTeam = cap(config);
        Team best = null;
        int minCount = Integer.MAX_VALUE;
        for (Team team : teams.all()) {
            if (team.size() < maxPerTeam && team.size() < minCount) {
                minCount = team.size();
                best = team;
            }
        }
        if (best == null) {
            messages().send(player, "randomjoin-none-available");
            return;
        }
        joinTargetTeam(player, best);
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

    public Hud hud() {
        return hud;
    }

    public BingoRush plugin() {
        return plugin;
    }

    /** Opens the lobby team menu, where players join or switch teams. */
    public void openTeamMenu(Player player) {
        teamMenu.open(player);
    }

    public void teamMenuClick(Player player, ItemStack stack) {
        teamMenu.click(player, stack);
    }

    private void loadAdvancementTab() {
        if (round == null || roundConfig == null || !roundConfig.advancementTab()) {
            return;
        }
        if (cardAdvancements.load(round, roundConfig)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                cardAdvancements.sync(player, round, teams.of(player.getUniqueId()));
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
                cardAdvancements.sync(member, round, team);
            }
        }
    }

    private void retireAdvancementTab() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            cardAdvancements.clear(player);
        }
        cardAdvancements.retire();
    }

    /** A configured sound for one player, for menu feedback. */
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
    public TeamDisplay display() {
        return display;
    }

    public void startRound(CommandSender caller, BingoGameMode modeOverride, WinMode winModeOverride,
            Boolean lockoutOverride, Long timeLimitOverride, Integer livesOverride, String seedArg) {
        if (round != null) {
            messages().send(caller, "round-already-running");
            return;
        }
        long activeTeams = teams.all().stream().filter(t -> !t.members().isEmpty()).count();
        if (activeTeams < 2) {
            messages().send(caller, "round-need-more-teams");
            return;
        }
        BingoConfig config = plugin.bingoConfig();
        if (config == null) {
            messages().send(caller, "round-config-broken");
            return;
        }
        BingoGameMode mode;
        WinMode winMode;
        boolean lockout;
        GameMapDefinition selectedArena = selectedMapId() != null ? getArena(selectedMapId()) : null;
        if (selectedArena != null && modeVoteManager != null) {
            selectedArena = modeVoteManager.applyVotes(selectedArena);
        }
        if (selectedArena != null) {
            mode = modeOverride != null ? modeOverride : selectedArena.mode();
            winMode = winModeOverride != null ? winModeOverride : selectedArena.winMode();
            lockout = lockoutOverride != null ? lockoutOverride : selectedArena.lockout();
            roundTimeLimit = timeLimitOverride != null ? timeLimitOverride : selectedArena.timeLimit();
        } else {
            mode = modeOverride != null ? modeOverride : config.gameMode();
            winMode = winModeOverride != null ? winModeOverride : config.winMode();
            lockout = lockoutOverride != null ? lockoutOverride : config.lockout();
            roundTimeLimit = timeLimitOverride != null ? timeLimitOverride : config.timeLimitSeconds();
        }
        int lives = livesOverride != null ? livesOverride
                : selectedArena != null ? selectedArena.lives() : config.lives();
        int countdown = selectedArena != null ? selectedArena.countdown() : config.countdownSeconds();
        // The world exists before the countdown starts, so the round never
        // begins on a tick that also has to generate a world.
        if (selectedArena != null && matchWorldManager != null
                && matchWorldManager.getOrCreateArenaWorld(selectedArena) == null) {
            messages().send(caller, "arena.world-unavailable", Placeholder.unparsed("arena", selectedArena.id()));
            return;
        }
        if (modeVoteManager != null) {
            modeVoteManager.closeAll();
        }
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
        roundArenaId = selectedArena != null ? selectedArena.id() : null;
        roundArena = selectedArena;
        round = new Round(seed, mode, card, new ClaimBoard(winMode, lockout), lives);
        if (signManager != null) {
            signManager.updateAll();
        }
        // The lobby menus are gone the moment a round exists, countdown included.
        teamMenu.closeAll();
        // Loading the tab saves every player and rereads their advancements,
        // which can take a moment: better during the countdown than on the
        // tick the round starts, and before any inventory is cleared.
        loadAdvancementTab();
        display.applyAll(teams.all());
        int total = countdown;
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
        Bukkit.getPluginManager().callEvent(new BingoRoundEndEvent(round, null, round.elapsedMs() / 1000L));
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
        messages().console("Next card rerolled, seed <hl><seed></hl>",
                Placeholder.unparsed("seed", String.valueOf(pendingSeed)));
    }

    public void joinTargetTeam(Player player, Team target) {
        if (target == null) {
            return;
        }
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
        if (target.size() >= cap(config)) {
            messages().send(player, "team-full", teamPlaceholder(target));
            return;
        }
        target.add(player.getUniqueId());
        knownNames.put(player.getUniqueId(), player.getName());
        messages().send(player, "team-joined", teamPlaceholder(target));
        Bukkit.getPluginManager().callEvent(new BingoTeamJoinEvent(player, target));
        if (signManager != null) {
            signManager.updateAll();
        }
        teamMenu.refreshAll();
        display.apply(player, target);
        if (current != null) {
            prepareTeamEntry(player, target, current);
        } else {
            giveLobbyWaitingItems(player);
        }
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
        joinTargetTeam(player, target);
    }

    // The player is on the team already; this is what being in a running
    // round needs on top of that. Both /bingo join and /bingo teams join go
    // through here so neither path leaves a player without a book or a HUD.
    private void prepareTeamEntry(Player player, Team target, Round current) {
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
        cardAdvancements.sync(player, current, target);
        revokeOwnedAdvancements(player, target);
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
        display.apply(player, teams.of(player.getUniqueId()));
        if (round != null && round.state() != Round.State.COUNTDOWN) {
            saveActiveRound();
        }
    }

    public void leaveTeam(Player player) {
        if (round == null) {
            leaveWaitingLobby(player);
            return;
        }
        Team team = teams.of(player.getUniqueId());
        if (team == null) {
            messages().send(player, "team-not-in");
            return;
        }
        team.remove(player.getUniqueId());
        messages().send(player, "team-left");
        Bukkit.getPluginManager().callEvent(new BingoTeamLeaveEvent(player, team));
        if (signManager != null) {
            signManager.updateAll();
        }
        teamMenu.refreshAll();
        display.apply(player, null);
        removeLobbyWaitingItems(player);
        if (round != null) {
            hud.playerLeft(player);
            removeCardItems(player);
            cardAdvancements.clear(player);
            Map<String, List<String>> owed = ownedAdvancements.take(player.getUniqueId());
            awardQuietly(() -> OwnedAdvancements.award(player, owed));
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

    /**
     * A new team. Without a color it gets the first palette color nobody
     * uses yet; without a tag, the first letter of its name.
     */
    public void createTeam(CommandSender caller, String name, Integer rgb, String tag) {
        if (tag != null && !validTag(tag)) {
            messages().send(caller, "team-tag-invalid", Placeholder.unparsed("value", tag));
            return;
        }
        TextColor color = rgb != null ? TextColor.color(rgb) : freeColor();
        try {
            Team team = teams.create(name, color, tag);
            messages().send(caller, "team-created", teamPlaceholder(team),
                    Placeholder.unparsed("tag", team.tag()));
            teamMenu.refreshAll();
        } catch (IllegalArgumentException e) {
            messages().send(caller, "team-name-taken", Placeholder.unparsed("name", name));
        }
    }

    // Up to three characters fit next to a name without pushing it off the
    // player list on a small screen.
    private static boolean validTag(String tag) {
        String trimmed = tag.trim();
        return !trimmed.isEmpty() && trimmed.codePointCount(0, trimmed.length()) <= 3;
    }

    private TextColor freeColor() {
        for (TeamPalette.Entry entry : TeamPalette.entries()) {
            if (!colorTaken(entry.rgb())) {
                return TextColor.color(entry.rgb());
            }
        }
        return TextColor.color(TeamPalette.entries().get(teams.all().size() % TeamPalette.entries().size()).rgb());
    }

    private boolean colorTaken(int rgb) {
        for (Team team : teams.all()) {
            if (team.color().value() == rgb) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fills up to the given number of teams with the palette colors and their
     * names in the server's language. Teams that already exist stay as they are.
     */
    public void autoTeams(CommandSender caller, int count) {
        if (round != null) {
            messages().send(caller, "teams-locked");
            return;
        }
        int made = 0;
        for (TeamPalette.Entry entry : TeamPalette.entries()) {
            if (teams.all().size() >= count) {
                break;
            }
            String name = messages().plainText("team-palette." + entry.key());
            if (colorTaken(entry.rgb()) || teams.byName(name) != null) {
                continue;
            }
            teams.create(name, TextColor.color(entry.rgb()), null);
            made++;
        }
        teamMenu.refreshAll();
        display.applyAll(teams.all());
        if (signManager != null) {
            signManager.updateAll();
        }
        messages().send(caller, "team-auto", Placeholder.unparsed("made", String.valueOf(made)),
                Placeholder.unparsed("total", String.valueOf(teams.all().size())));
    }

    public void recolorTeam(CommandSender caller, String name, int rgb) {
        Team team = teams.byName(name);
        if (team == null) {
            messages().send(caller, "team-not-found", Placeholder.unparsed("name", name));
            return;
        }
        team.recolor(TextColor.color(rgb));
        teamMenu.refreshAll();
        display.applyAll(teams.all());
        messages().send(caller, "team-recolored", teamPlaceholder(team));
    }

    public void retagTeam(CommandSender caller, String name, String tag) {
        Team team = teams.byName(name);
        if (team == null) {
            messages().send(caller, "team-not-found", Placeholder.unparsed("name", name));
            return;
        }
        if (!validTag(tag)) {
            messages().send(caller, "team-tag-invalid", Placeholder.unparsed("value", tag));
            return;
        }
        team.retag(tag);
        display.applyAll(teams.all());
        messages().send(caller, "team-retagged", teamPlaceholder(team), Placeholder.unparsed("tag", team.tag()));
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
        Round current = round;
        if (current != null && current.isEliminated(target.getUniqueId())) {
            messages().send(caller, "team-assign-eliminated",
                    Placeholder.unparsed("player", target.getName()));
            if (!caller.equals(target)) {
                messages().send(target, "eliminated-cannot-join");
            }
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
            Bukkit.getPluginManager().callEvent(new BingoTeamLeaveEvent(target, old));
        }
        team.add(target.getUniqueId());
        knownNames.put(target.getUniqueId(), target.getName());
        teamMenu.refreshAll();
        display.apply(target, team);
        messages().send(caller, "team-assigned",
                Placeholder.unparsed("player", target.getName()), teamPlaceholder(team));
        messages().send(target, "team-joined", teamPlaceholder(team));
        Bukkit.getPluginManager().callEvent(new BingoTeamJoinEvent(target, team));
        if (signManager != null) {
            signManager.updateAll();
        }
        if (current != null) {
            prepareTeamEntry(target, team, current);
        } else {
            giveLobbyWaitingItems(target);
        }
    }

    public void clearTeams(CommandSender caller) {
        for (Team team : teams.all()) {
            for (UUID member : team.members()) {
                team.remove(member);
            }
        }
        if (signManager != null) {
            signManager.updateAll();
        }
        teamMenu.refreshAll();
        display.applyAll(teams.all());
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
        knownNames.put(player.getUniqueId(), player.getName());
        if (roundRestoredUnfinished && player.hasPermission("bingo.admin")) {
            messages().send(player, "round-restored-admin");
        }
        if (round != null && cardAdvancements.isLoaded()) {
            cardAdvancements.sync(player, round, teams.of(player.getUniqueId()));
        } else {
            cardAdvancements.clear(player);
        }
        display.apply(player, teams.of(player.getUniqueId()));
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
                } else if (shouldSpawnAtLobby(player.getUniqueId())) {
                    Location lobby = lobbyLocation();
                    if (lobby != null) {
                        player.teleport(lobby);
                    }
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
            if (shouldSpawnAtLobby(player.getUniqueId())) {
                Location lobby = lobbyLocation();
                if (lobby != null) {
                    player.teleport(lobby);
                }
            }
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
            swapSource = contentOf(player.getInventory().getItemInOffHand());
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

    /** Whether the card is in a hand that could hand it to an entity. */
    public boolean holdsCard(Player player, EquipmentSlot hand) {
        return isCardItem(player.getInventory().getItem(hand));
    }

    private CardRules.Content contentOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
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
        player.sendActionBar(messages().render("card-locked"));
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
        ItemStack card = ItemType.BOOK.createItemStack();
        card.editMeta(meta -> {
            meta.displayName(messages().render("card-item-name"));
            meta.lore(List.of(messages().render("card-item-lore")));
            meta.getPersistentDataContainer().set(cardItemTag, PersistentDataType.BYTE, (byte) 1);
        });
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
                        ownedAdvancements.snapshot(), savedMembers(), place());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not save active round state: " + e.getMessage());
            }
        }
        cancelTasks();
        cardMenu.closeAll();
        teamMenu.closeAll();
        hud.hideAll();
        if (round == null) {
            restoreInventories();
        }
        removeCardItemsFromAll();
        retireAdvancementTab();
        display.applyAll(teams.all());
        // Last, so whatever restoreInventories queued above reaches the disk.
        if (round == null) {
            waitingBackup.clear();
        }
        waitingBackup.close();
        pendingRestores.close();
        round = null;
        roundConfig = null;
    }

    /** After /bingo reload: names, order and chat format follow the new settings. */
    public void settingsReloaded() {
        display.clearAll();
        display.applyAll(teams.all());
    }

    /** A player left the server. */
    public void playerQuit(UUID playerId) {
        if (round == null && savedInventories.containsKey(playerId)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                removeLobbyWaitingItems(player);
                player.getInventory().setContents(savedInventories.remove(playerId));
                GameMode mode = savedGameModes.remove(playerId);
                if (mode != null) {
                    player.setGameMode(mode);
                }
                waitingBackup.forget(playerId);
                if (modeVoteManager != null && selectedMapId != null) {
                    modeVoteManager.onPlayerLeave(playerId, selectedMapId);
                }
            }
        }
        menuClosed(playerId);
        cardNotice.remove(playerId);
        display.forget(playerId);
        if (hud != null) {
            hud.playerQuit(playerId);
        }
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
        String arenaId = round != null ? roundArenaId : selectedMapId();
        if (arenaId != null) {
            GameMapDefinition a = getArena(arenaId);
            if (a != null) {
                return a.teamSize();
            }
        }
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
            if (plugin.season() != null) {
                Location where = roundArenaId != null && matchWorldManager != null
                        ? matchWorldManager.getWaitingSpawn(roundArenaId) : null;
                plugin.season().onCountdownSnow(where != null ? where : lobbyLocation());
            }
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
        removeLobbyWaitingItemsFromAll();
        round.beginRun();
        if (roundConfig.clearInventories()) {
            saveAndClearInventories();
            broadcast("round-cleared-inventories");
        }
        giveCardItems();
        for (Team team : teams.all()) {
            for (UUID memberId : team.members()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member != null) {
                    revokeOwnedAdvancements(member, team);
                }
            }
        }
        if (roundConfig.scatterPlayers()) {
            prepareArenaForRound(true);
            scatterTeams();
        } else {
            prepareArenaForRound(false);
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
        Bukkit.getPluginManager().callEvent(new BingoRoundStartEvent(round));
        if (signManager != null) {
            signManager.updateAll();
        }
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
        removeLobbyWaitingItemsFromAll();
        cardMenu.closeAll();
        hud.hideAll();
        restoreInventories();
        restoreGameModes();
        restoreOwnedAdvancements();
        removeCardItemsFromAll();
        retireAdvancementTab();
        roundWriter.delete();
        waitingBackup.clear();
        roundRestoredUnfinished = false;
        round = null;
        roundConfig = null;
        // After round = null, or everyone keeps their in-round prefix.
        display.applyAll(teams.all());
        String playedIn = roundArenaId;
        roundArenaId = null;
        teleportAllToLobby();
        if (playedIn != null) {
            if (modeVoteManager != null) {
                modeVoteManager.clearVotes(playedIn);
            }
            if (matchWorldManager != null) {
                matchWorldManager.notifyPlayerLeft(playedIn);
            }
        }
        if (signManager != null) {
            signManager.updateAll();
        }
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
                    ownedAdvancements.snapshot(), savedMembers(), place());
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not save active round state: " + e.getMessage());
            return;
        }
        roundWriter.write(json);
    }

    private RoundStorage.Place place() {
        if (roundArenaId == null) {
            return null;
        }
        return new RoundStorage.Place(roundArenaId,
                matchWorldManager != null ? matchWorldManager.seedOf(roundArenaId) : null);
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
            cardAdvancements.sync(player, round, team);
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
            this.ownedAdvancements.load(restored.revokedAdvancements());
            this.roundRestoredUnfinished = true;
            restoreRoundWorld(restored.place());

            hud.restorePaused(restored.timeLimitSeconds());
            // The tab is not saved anywhere; the restart wiped it from the server.
            loadAdvancementTab();
            display.applyAll(teams.all());
            restoreOnlineMembers();

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

    // Rounds saved before arenas existed have no place and stay in the main
    // world, where they were played. A saved arena gets its world loaded
    // before anyone can join, so players log back in where they were.
    private void restoreRoundWorld(RoundStorage.Place place) {
        roundArenaId = null;
        if (place == null || place.arena() == null) {
            return;
        }
        GameMapDefinition arena = getArena(place.arena());
        if (arena == null || matchWorldManager == null) {
            plugin.getLogger().warning("The saved round was played in arena '" + place.arena()
                    + "', which no longer exists; it resumes in the main world");
            return;
        }
        if (matchWorldManager.loadForRound(arena, place.worldSeed()) == null) {
            plugin.getLogger().warning("Could not load the world of arena '" + place.arena()
                    + "' for the saved round; it resumes in the main world");
            return;
        }
        // Players who logged out in the Nether or the End come back there.
        matchWorldManager.prepareDimensions(arena);
        roundArenaId = arena.id();
        selectedMapId = arena.id();
    }

    /** Copies what waiting players were owed into the pending restores, after a crash. */
    public void recoverWaitingRoom() {
        for (UUID id : waitingBackup.players()) {
            if (round != null && savedInventories.containsKey(id)) {
                continue;
            }
            pendingRestores.adopt(id, waitingBackup.get(id));
        }
        waitingBackup.clear();
    }

    /** Where a team member stands up after dying, when the round has its own world. */
    public Location respawnLocation(Player player) {
        if (round == null || roundArenaId == null || matchWorldManager == null
                || teams.of(player.getUniqueId()) == null) {
            return null;
        }
        return matchWorldManager.respawnPoint(roundArenaId);
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
        Bukkit.getPluginManager().callEvent(new BingoClaimEvent(claimer, team, cell, itemKey));
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
                    player.sendActionBar(rivalBar);
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
    private boolean checkAdvancementClaim(Player player, Advancement advancement) {
        Round current = round;
        if (current == null || current.state() != Round.State.RUNNING || current.mode() != BingoGameMode.ADVANCEMENTS
                || current.isEliminated(player.getUniqueId())) {
            return false;
        }
        NamespacedKey key = advancement.getKey();
        if (key.getNamespace().equals("minecraft") && key.getKey().startsWith("recipes/")) {
            return false;
        }
        Team team = teams.of(player.getUniqueId());
        if (team == null) {
            return false;
        }
        String keyStr = key.toString();
        boolean claimed = false;
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            if (current.board().isClaimed(cell, team.id())) {
                continue;
            }
            if (keyStr.equalsIgnoreCase(current.card().item(cell))) {
                claimAdvancementCell(current, team, cell, player, advancement);
                claimed = true;
            }
        }
        return claimed;
    }

    /**
     * An advancement for anyone on the server. Team members in a round may
     * claim a cell with it, and their chat line follows round-chat.advancements.
     */
    public void advancementDone(PlayerAdvancementDoneEvent event) {
        if (restoringAdvancements) {
            event.message(null);
            return;
        }
        if (cardAdvancements.isOurs(event.getAdvancement())) {
            return;
        }
        Player player = event.getPlayer();
        Team team = teams.of(player.getUniqueId());
        // Read before the claim: a winning claim ends the round and drops roundConfig.
        boolean inRound = round != null && team != null;
        ChatStyle style = inRound && roundConfig != null ? roundConfig.advancementChat() : ChatStyle.VANILLA;
        boolean claimed = checkAdvancementClaim(player, event.getAdvancement());
        Component vanilla = event.message();
        if (!inRound || vanilla == null || style == ChatStyle.VANILLA) {
            return;
        }
        // A claimed cell already got its own line from the claim.
        if (style == ChatStyle.HIDDEN || claimed) {
            event.message(null);
            return;
        }
        AdvancementDisplay display = event.getAdvancement().getDisplay();
        event.message(messages().render("advancement-message",
                Placeholder.unparsed("player", player.getName()),
                teamPlaceholder(team),
                Placeholder.component("advancement", display != null ? display.displayName() : vanilla)));
    }

    private void claimAdvancementCell(Round current, Team team, int cell, Player claimer, Advancement advancement) {
        if (!current.board().claim(cell, team.id(), System.currentTimeMillis())) {
            return;
        }
        current.recordFirstClaim(cell, team.id(), current.elapsedMs());
        Bukkit.getPluginManager().callEvent(new BingoClaimEvent(claimer, team, cell, advancement.getKey().toString()));
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

        messages().console("<team><body> claimed advancement <hl><item></hl> (cell <cell>), <hl><count></hl> cells, by <player>",
                teamPlaceholder(team), Placeholder.unparsed("item", advancement.getKey().toString()),
                Placeholder.unparsed("cell", String.valueOf(cell)), Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("player", claimer.getName()));
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
        Player player = event.getPlayer();
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
        if (current.teamsStanding(Map.of(team.id(), team.members())).isEmpty()) {
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
        Component vanilla = event.deathMessage();
        if (vanilla == null || style == ChatStyle.VANILLA) {
            return false;
        }
        if (style == ChatStyle.HIDDEN) {
            event.deathMessage(null);
            return false;
        }
        String key = eliminated ? "death-message-eliminated" : counted ? "death-message-lives" : "death-message";
        int left = Math.max(0, current.livesLeft(event.getPlayer().getUniqueId()));
        event.deathMessage(messages().render(key,
                Placeholder.component("message", vanilla),
                teamPlaceholder(team),
                Placeholder.unparsed("lives", String.valueOf(left))));
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
            display.apply(player, teams.of(player.getUniqueId()));
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
            Team winner = teams.byId(standing.getFirst());
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
            TagResolver item = Placeholder.component("item", cellName(current, itemKey));
            TagResolver teamTag = claimerTeam != null ? teamPlaceholder(claimerTeam) : Placeholder.unparsed("team", "?");
            broadcast("breakdown.first-claim", item, teamTag, Placeholder.unparsed("time", clock(first.elapsedMs())));
        } else {
            broadcast("breakdown.first-claim-none");
        }

        broadcast("breakdown.total-time", Placeholder.unparsed("time", clock(current.elapsedMs())));
        broadcast("breakdown.separator");
    }

    // An advancement round keeps advancement keys in the cells, which the
    // item registry does not know; those show the advancement title.
    private Component cellName(Round current, String key) {
        if (current.mode() == BingoGameMode.ADVANCEMENTS) {
            Advancement advancement = roundConfig.advancement(key);
            return advancement != null && advancement.getDisplay() != null
                    ? advancement.getDisplay().title() : Component.text(key);
        }
        ItemType type = roundConfig.itemType(key);
        return type == null ? Component.text(key) : Component.translatable(type);
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
                if (member != null) {
                    saveAndClearInventory(member);
                }
            }
        }
    }

    // putIfAbsent: a player who came through a waiting room already has
    // their real inventory saved, and the one they hold now is empty.
    private void saveAndClearInventory(Player player) {
        savedInventories.putIfAbsent(player.getUniqueId(), player.getInventory().getContents());
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
        ownedAdvancements.revoke(player, current, team);
    }

    private void restoreOwnedAdvancements() {
        for (UUID playerId : ownedAdvancements.players()) {
            Map<String, List<String>> owed = ownedAdvancements.take(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && !player.isDead()) {
                awardQuietly(() -> OwnedAdvancements.award(player, owed));
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

    // The waiting platform is only for the lobby. Before the round starts it
    // goes away and anyone who is not on a team returns to the main lobby.
    private void prepareArenaForRound(boolean scatter) {
        GameMapDefinition arena = getArena(roundArenaId);
        World world = arena != null && matchWorldManager != null ? matchWorldManager.world(arena.id()) : null;
        if (world == null) {
            return;
        }
        matchWorldManager.prepareDimensions(arena);
        matchWorldManager.applyRoundRules(roundArena != null ? roundArena : arena);
        matchWorldManager.clearWaitingPlatform(arena.id());
        Location exit = lobbyLocation();
        if (exit == null) {
            exit = Bukkit.getWorlds().getFirst().getSpawnLocation();
        }
        for (Player player : new ArrayList<>(world.getPlayers())) {
            Team team = teams.of(player.getUniqueId());
            if (team == null) {
                evacuateWaitingPlayer(player, exit);
            } else if (!scatter) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(world.getSpawnLocation());
            }
        }
    }

    private void evacuateWaitingPlayer(Player player, Location exit) {
        UUID playerId = player.getUniqueId();
        ItemStack[] saved = savedInventories.remove(playerId);
        GameMode savedMode = savedGameModes.remove(playerId);
        waitingBackup.forget(playerId);
        removeLobbyWaitingItems(player);
        if (saved != null) {
            player.getInventory().clear();
            player.getInventory().setContents(saved);
        }
        player.setGameMode(savedMode != null ? savedMode : GameMode.SURVIVAL);
        player.teleport(exit);
    }

    // One spot per team around the world spawn, so teams start apart but
    // together, and no member stacks on another.
    private void scatterTeams() {
        World world = roundArenaId != null && matchWorldManager != null ? matchWorldManager.world(roundArenaId) : null;
        if (world == null) {
            world = Bukkit.getWorlds().getFirst();
        }
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
                member.setGameMode(GameMode.SURVIVAL);
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
                if (sender instanceof Player player && !player.isOnline()) {
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
        Bukkit.getPluginManager().callEvent(new BingoRoundEndEvent(current, winner, current.elapsedMs() / 1000L));
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
