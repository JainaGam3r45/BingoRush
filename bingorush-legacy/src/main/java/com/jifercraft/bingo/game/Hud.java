package com.jifercraft.bingo.game;

import com.cryptomorin.xseries.XSound;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.compat.ServerVersion;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.config.Messages;
import com.jifercraft.bingo.config.ScoreboardDefinition;
import com.jifercraft.bingo.history.MatchHistory;
import com.jifercraft.bingo.season.SeasonPhase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Everything the players see beyond chat: the sidebar with the clock and each
 * team's count, the boss bar with the time left, the round titles and the
 * configured sounds. Built per round and torn down at the end, giving every
 * player back the scoreboard they had before.
 */
public final class Hud {

    private static final Duration TITLE_FADE = Duration.ofMillis(300);
    private static final Duration TITLE_STAY = Duration.ofSeconds(3);
    private static final Duration COUNTDOWN_FADE_OUT = Duration.ofMillis(200);
    private static final Duration COUNTDOWN_STAY = Duration.ofMillis(800);
    private static final float SOUND_VOLUME = 1.0f;
    private static final float SOUND_PITCH = 1.0f;
    // Sidebar lines are their own text here, so two equal lines need an
    // invisible color code each to stay apart.
    private static final String UNIQUE = "0123456789abcdef";

    private final BingoRush plugin;
    private final Game game;

    private BossBar bossBar;
    private Objective sidebar;
    private Scoreboard scoreboard;
    private long totalMs;
    private long limitSeconds;
    private final Map<UUID, Scoreboard> previousScoreboards = new HashMap<>();
    private final Set<String> activeEntries = new HashSet<>();
    private final Set<UUID> barViewers = new HashSet<>();
    private final Map<UUID, Scoreboard> lobbyScoreboards = new HashMap<>();
    private final Map<UUID, Scoreboard> previousLobbyScoreboards = new HashMap<>();
    private final Map<UUID, Set<String>> lobbyActiveEntries = new HashMap<>();
    private BukkitTask lobbyTask;

    public Hud(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    private Messages messages() {
        return plugin.messages();
    }

    /** Shows sidebar, boss bar, start title and start sound for the new round. */
    public void roundStarted(long timeLimitSeconds) {
        limitSeconds = timeLimitSeconds;
        totalMs = timeLimitSeconds * 1000;
        if (playingScoreboardEnabled()) {
            buildScoreboard();
        } else {
            restoreLobbyScoreboards();
        }
        if (timeLimitSeconds > 0) {
            bossBar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        }
        for (Player player : participants()) {
            showBar(player);
        }
        String subtitleKey = game.round() != null && game.round().mode() == BingoGameMode.ADVANCEMENTS
                ? "round-started-subtitle-advancements"
                : "round-started-subtitle";
        title(Bukkit.getOnlinePlayers(), Title.title(messages().render("round-started-title"),
                messages().render(subtitleKey), Title.Times.times(TITLE_FADE, TITLE_STAY, TITLE_FADE)));
        play(BingoConfig.SoundId.START, Bukkit.getOnlinePlayers());
        refresh();
    }

    /** Reconstructs scoreboard and prepared boss bar for a restored paused round. */
    public void restorePaused(long timeLimitSeconds) {
        limitSeconds = timeLimitSeconds;
        totalMs = timeLimitSeconds * 1000;
        if (playingScoreboardEnabled()) {
            buildScoreboard();
        }
        if (timeLimitSeconds > 0) {
            bossBar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        }
        refresh();
    }

    private boolean playingScoreboardEnabled() {
        return plugin.bingoConfig() == null || plugin.bingoConfig().playingScoreboard();
    }

    private void buildScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        sidebar = scoreboard.registerNewObjective("bingo", "dummy");
        ScoreboardDefinition cfg = plugin.bingoConfig() != null ? plugin.bingoConfig().playingScoreboardConfig() : null;
        Component title = (cfg != null && cfg.title() != null && !cfg.title().trim().isEmpty())
                ? messages().renderTemplate(cfg.title())
                : messages().render("scoreboard.playing.title");
        sidebar.setDisplayName(plugin.objectiveTitle(title));
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        game.display().registerOnScoreboard(scoreboard, game.teams().all());
        for (Player player : participants()) {
            previousScoreboards.put(player.getUniqueId(), player.getScoreboard());
            player.setScoreboard(scoreboard);
        }
    }

    /** Once a second while the round runs: the clock everywhere it shows. */
    public void clockTick() {
        refresh();
    }

    /** A team claimed something: the sidebar counts move and the team hears it. */
    public void claimHappened(Team team) {
        refresh();
        play(BingoConfig.SoundId.CLAIM, onlineMembers(team));
    }

    /** Plays the alert sound to rival players when a cell is locked in lockout mode. */
    public void lockoutLost(Team claimingTeam) {
        List<Player> rivals = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Team playerTeam = game.teams().of(player.getUniqueId());
            if (playerTeam != null && playerTeam.id() != claimingTeam.id()) {
                rivals.add(player);
            }
        }
        play(BingoConfig.SoundId.LOCKOUT_LOST, rivals);
    }

    /** Countdown title on screen and a pling for everyone. */
    public void countdownTick(int secondsLeft) {
        title(Bukkit.getOnlinePlayers(), Title.title(
                messages().render("round-countdown-title", Placeholder.unparsed("count", String.valueOf(secondsLeft))),
                Component.empty(),
                Title.Times.times(Duration.ZERO, COUNTDOWN_STAY, COUNTDOWN_FADE_OUT)));
        play(BingoConfig.SoundId.COUNTDOWN, Bukkit.getOnlinePlayers());
    }

    /** Pauses the clock and removes the boss bar from screens. */
    public void pause() {
        for (Player player : participants()) {
            hideBar(player);
        }
    }

    /** Restores the boss bar on screen when the round resumes. */
    public void resume() {
        for (Player player : participants()) {
            showBar(player);
        }
        refresh();
    }

    /** Gives late joiners the round scoreboard and active boss bar. */
    public void playerJoined(Player player) {
        if (scoreboard != null) {
            previousScoreboards.put(player.getUniqueId(), player.getScoreboard());
            player.setScoreboard(scoreboard);
        }
        if (game.round() != null && game.round().state() == Round.State.RUNNING) {
            showBar(player);
        }
    }

    /** Restores previous scoreboard when a player leaves their team. */
    public void playerLeft(Player player) {
        Scoreboard previous = previousScoreboards.remove(player.getUniqueId());
        player.setScoreboard(previous != null ? previous : Bukkit.getScoreboardManager().getMainScoreboard());
        hideBar(player);
    }

    /** A team completed the card or the line. */
    public void won(Team winner) {
        title(Bukkit.getOnlinePlayers(), Title.title(
                messages().render("round-won-title", Game.teamPlaceholder(winner)),
                messages().render("round-won-subtitle"),
                Title.Times.times(TITLE_FADE, TITLE_STAY, TITLE_FADE)));
        play(BingoConfig.SoundId.WIN, Bukkit.getOnlinePlayers());
    }

    /** The clock hit zero with no completion. */
    public void timeUp() {
        title(Bukkit.getOnlinePlayers(), Title.title(messages().render("round-time-up-title"), Component.empty(),
                Title.Times.times(TITLE_FADE, TITLE_STAY, TITLE_FADE)));
    }

    /** The round is over: scoreboards back to what they were, bar gone. */
    public void hideAll() {
        for (Map.Entry<UUID, Scoreboard> entry : previousScoreboards.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.setScoreboard(entry.getValue());
            }
        }
        previousScoreboards.clear();
        activeEntries.clear();
        for (UUID viewer : new ArrayList<>(barViewers)) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) {
                hideBar(player);
            }
        }
        barViewers.clear();
        bossBar = null;
        sidebar = null;
        scoreboard = null;
    }

    private void showBar(Player player) {
        if (bossBar != null && barViewers.add(player.getUniqueId())) {
            messages().audience(player).showBossBar(bossBar);
        }
    }

    private void hideBar(Player player) {
        if (bossBar != null && barViewers.remove(player.getUniqueId())) {
            messages().audience(player).hideBossBar(bossBar);
        }
    }

    private void refresh() {
        if (sidebar == null || game.round() == null) {
            return;
        }
        long remainingMs = game.round().remainingMs(limitSeconds);
        String time = limitSeconds <= 0 ? Game.clock(game.round().elapsedMs()) : Game.clock(remainingMs);

        List<Component> lines = new ArrayList<>();
        ScoreboardDefinition cfg = plugin.bingoConfig() != null ? plugin.bingoConfig().playingScoreboardConfig() : null;
        if (cfg != null && !cfg.lines().isEmpty()) {
            String teamFmt = cfg.teamFormat() != null && !cfg.teamFormat().trim().isEmpty()
                    ? cfg.teamFormat()
                    : "<color>❙</color> <team>: <white><count>/<total></white>";
            for (String raw : cfg.lines()) {
                if (raw.trim().equalsIgnoreCase("<teams>") || raw.trim().equalsIgnoreCase("%teams%")) {
                    for (Team team : game.teams().all()) {
                        if (team.members().isEmpty()) {
                            continue;
                        }
                        lines.add(messages().renderTemplate(teamFmt,
                                TagResolver.resolver("color", Tag.styling(team.color())),
                                Game.teamPlaceholder(team),
                                Placeholder.unparsed("count", String.valueOf(game.round().board().count(team.id()))),
                                Placeholder.unparsed("total", String.valueOf(BingoCard.CELLS))));
                    }
                } else {
                    lines.add(messages().renderTemplate(raw,
                            Placeholder.unparsed("time", time),
                            Placeholder.component("server", messages().render("scoreboard.server"))));
                }
            }
        } else {
            lines.add(messages().render("scoreboard.playing.separator"));
            lines.add(messages().render("scoreboard.playing.time", Placeholder.unparsed("time", time)));
            lines.add(Component.empty());
            lines.add(messages().render("scoreboard.playing.teams-header"));
            for (Team team : game.teams().all()) {
                if (team.members().isEmpty()) {
                    continue;
                }
                lines.add(messages().render("scoreboard.playing.team-line",
                        TagResolver.resolver("color", Tag.styling(team.color())),
                        Game.teamPlaceholder(team),
                        Placeholder.unparsed("count", String.valueOf(game.round().board().count(team.id()))),
                        Placeholder.unparsed("total", String.valueOf(BingoCard.CELLS))));
            }
            lines.add(Component.empty());
            lines.add(messages().render("scoreboard.playing.separator"));
            lines.add(messages().render("scoreboard.playing.footer",
                    Placeholder.component("server", messages().render("scoreboard.server"))));
        }

        // Until 1.18 the protocol caps a scoreboard entry at 40 characters,
        // codes included, and a longer one can disconnect the client. A hex
        // color takes 14 of them per change, so those versions get the 16
        // classic colors, and 38 characters plus the 2 of the unique code.
        boolean roomy = ServerVersion.atLeast(18);
        Set<String> current = new HashSet<>();
        int score = lines.size();
        for (int i = 0; i < lines.size() && i < UNIQUE.length(); i++) {
            String text = roomy ? messages().legacy(lines.get(i)) : messages().legacyClassic(lines.get(i));
            String entry = fit(text, roomy ? 120 : 38) + "§" + UNIQUE.charAt(i);
            current.add(entry);
            sidebar.getScore(entry).setScore(score--);
        }
        for (String old : activeEntries) {
            if (!current.contains(old)) {
                scoreboard.resetScores(old);
            }
        }
        activeEntries.clear();
        activeEntries.addAll(current);

        if (bossBar == null) {
            return;
        }
        bossBar.name(messages().render("bossbar-title", Placeholder.unparsed("time", Game.clock(remainingMs))));
        bossBar.progress(totalMs <= 0 ? 0.0f : Math.max(0.0f, Math.min(1.0f, (float) remainingMs / (float) totalMs)));
    }

    // Cuts § text to a length without leaving a dangling color character.
    private static String fit(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        String cut = text.substring(0, max);
        return cut.endsWith("§") ? cut.substring(0, max - 1) : cut;
    }

    private void title(Iterable<? extends Player> players, Title title) {
        for (Player player : players) {
            messages().audience(player).showTitle(title);
        }
    }

    /** Plays the configured sound to one player, for menu feedback. */
    public void play(BingoConfig.SoundId id, Player player) {
        play(id, Collections.singletonList(player));
    }

    private void play(BingoConfig.SoundId id, Iterable<? extends Player> to) {
        BingoConfig config = plugin.bingoConfig();
        if (config == null) {
            return;
        }
        XSound sound = config.sound(id);
        if (sound == null) {
            return;
        }
        float pitch = id == BingoConfig.SoundId.LOCKOUT_LOST ? 0.8f : SOUND_PITCH;
        for (Player player : to) {
            sound.play(player, SOUND_VOLUME, pitch);
        }
    }

    private List<Player> participants() {
        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (game.teams().of(player.getUniqueId()) != null) {
                players.add(player);
            }
        }
        return players;
    }

    private List<Player> onlineMembers(Team team) {
        List<Player> players = new ArrayList<>();
        for (UUID memberId : team.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null) {
                players.add(member);
            }
        }
        return players;
    }

    public void startLobbyTask() {
        if (lobbyTask != null) {
            return;
        }
        lobbyTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickLobbyScoreboards, 20L, 20L);
    }

    public void stopLobbyTask() {
        if (lobbyTask != null) {
            lobbyTask.cancel();
            lobbyTask = null;
        }
        restoreLobbyScoreboards();
    }

    public void playerQuit(UUID playerId) {
        lobbyScoreboards.remove(playerId);
        previousLobbyScoreboards.remove(playerId);
        lobbyActiveEntries.remove(playerId);
        previousScoreboards.remove(playerId);
    }

    private void tickLobbyScoreboards() {
        if (game.round() != null) {
            return;
        }
        BingoConfig config = plugin.bingoConfig();
        if (config == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            GameMapDefinition arena = resolveWaitingArena(player);
            if (arena != null && config.waitingScoreboard()) {
                renderWaitingScoreboard(player, arena);
            } else if (arena == null && config.lobbyScoreboard()) {
                renderMainLobbyScoreboard(player);
            } else {
                restoreLobbyScoreboard(player);
            }
        }
    }

    private GameMapDefinition resolveWaitingArena(Player player) {
        if (game.arenaManager() == null) {
            return null;
        }
        for (GameMapDefinition arena : game.arenaManager().getAllArenas()) {
            if (arena.waitingLobby() != null && arena.waitingLobby().getWorld() != null
                    && arena.waitingLobby().getWorld().equals(player.getWorld())
                    && arena.waitingLobby().distanceSquared(player.getLocation()) < 400) {
                return arena;
            }
        }
        return null;
    }

    private void renderWaitingScoreboard(Player player, GameMapDefinition arena) {
        Scoreboard sb = lobbyScoreboardFor(player);
        ScoreboardDefinition cfg = plugin.bingoConfig() != null ? plugin.bingoConfig().waitingScoreboardConfig() : null;
        Component title = (cfg != null && cfg.title() != null && !cfg.title().trim().isEmpty())
                ? messages().renderTemplate(cfg.title())
                : messages().render("scoreboard.waiting.title");
        Objective obj = sb.getObjective("bingo_hud");
        if (obj == null) {
            obj = sb.registerNewObjective("bingo_hud", "dummy");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        obj.setDisplayName(plugin.objectiveTitle(title));

        Team team = game.teams().of(player.getUniqueId());
        Component teamComp = team != null
                ? Component.text(team.name(), team.color())
                : messages().render("scoreboard.no-team");

        String modeKey;
        switch (arena.teamSize()) {
            case 1:
                modeKey = "scoreboard.waiting.modes.solo";
                break;
            case 2:
                modeKey = "scoreboard.waiting.modes.duo";
                break;
            case 3:
                modeKey = "scoreboard.waiting.modes.trio";
                break;
            case 4:
                modeKey = "scoreboard.waiting.modes.squad";
                break;
            default:
                modeKey = "scoreboard.waiting.modes.custom";
                break;
        }
        Component mode = messages().render(modeKey,
                Placeholder.unparsed("size", String.valueOf(arena.teamSize())));

        int currentPlayers = 0;
        if (arena.waitingLobby() != null && arena.waitingLobby().getWorld() != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getWorld().equals(arena.waitingLobby().getWorld())) {
                    currentPlayers++;
                }
            }
        }

        TagResolver[] placeholders = new TagResolver[] {
                Placeholder.unparsed("arena", arena.name()),
                Placeholder.component("mode", mode),
                Placeholder.unparsed("players", String.valueOf(currentPlayers)),
                Placeholder.unparsed("max", String.valueOf(arena.maxPlayers())),
                Placeholder.component("team", teamComp),
                Placeholder.component("status", messages().render("scoreboard.waiting.state-waiting")),
                Placeholder.component("server", messages().render("scoreboard.server"))
        };

        List<Component> lines = (cfg != null && !cfg.lines().isEmpty())
                ? messages().renderTemplateLines(cfg.lines(), placeholders)
                : messages().renderLines("scoreboard.waiting.lines", placeholders);

        applyLobbyLines(player, sb, obj, lines);
        if (!player.getScoreboard().equals(sb)) {
            player.setScoreboard(sb);
        }
    }

    private void renderMainLobbyScoreboard(Player player) {
        Scoreboard sb = lobbyScoreboardFor(player);
        ScoreboardDefinition cfg = plugin.bingoConfig() != null ? plugin.bingoConfig().lobbyScoreboardConfig() : null;
        Component title = (cfg != null && cfg.title() != null && !cfg.title().trim().isEmpty())
                ? messages().renderTemplate(cfg.title())
                : messages().render("scoreboard.lobby.title");
        Objective obj = sb.getObjective("bingo_hud");
        if (obj == null) {
            obj = sb.registerNewObjective("bingo_hud", "dummy");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        obj.setDisplayName(plugin.objectiveTitle(title));

        MatchHistory.PlayerStats stats = plugin.matchHistory() != null
                ? plugin.matchHistory().getPlayerStats(player.getName())
                : new MatchHistory.PlayerStats(0, 0, 0);

        TagResolver[] placeholders = new TagResolver[] {
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("matches", String.valueOf(stats.matches())),
                Placeholder.unparsed("wins", String.valueOf(stats.wins())),
                Placeholder.unparsed("cells", String.valueOf(stats.claims())),
                Placeholder.component("season", messages().render(seasonKey())),
                Placeholder.unparsed("online", String.valueOf(Bukkit.getOnlinePlayers().size())),
                Placeholder.component("server", messages().render("scoreboard.server"))
        };

        List<Component> lines = (cfg != null && !cfg.lines().isEmpty())
                ? messages().renderTemplateLines(cfg.lines(), placeholders)
                : messages().renderLines("scoreboard.lobby.lines", placeholders);

        applyLobbyLines(player, sb, obj, lines);
        if (!player.getScoreboard().equals(sb)) {
            player.setScoreboard(sb);
        }
    }

    private String seasonKey() {
        if (plugin.season() == null || plugin.season().currentPhase() == null) {
            return "scoreboard.season.inactive";
        }
        SeasonPhase phase = plugin.season().currentPhase();
        switch (phase) {
            case PRE_SEASON:
                return "scoreboard.season.pre-season";
            case ACTIVE:
                return "scoreboard.season.active";
            case WRAP_UP:
                return "scoreboard.season.wrap-up";
            default:
                return "scoreboard.season.inactive";
        }
    }

    private void applyLobbyLines(Player player, Scoreboard sb, Objective obj, List<Component> lines) {
        boolean roomy = ServerVersion.atLeast(18);
        Set<String> current = new HashSet<>();
        int score = Math.min(lines.size(), UNIQUE.length());
        for (int i = 0; i < lines.size() && i < UNIQUE.length(); i++) {
            String text = roomy ? messages().legacy(lines.get(i)) : messages().legacyClassic(lines.get(i));
            String entry = fit(text, roomy ? 120 : 38) + "§" + UNIQUE.charAt(i);
            current.add(entry);
            obj.getScore(entry).setScore(score--);
        }
        Set<String> active = lobbyActiveEntries.get(player.getUniqueId());
        if (active == null) {
            active = new HashSet<>();
            lobbyActiveEntries.put(player.getUniqueId(), active);
        }
        for (String old : active) {
            if (!current.contains(old)) {
                sb.resetScores(old);
            }
        }
        active.clear();
        active.addAll(current);
    }

    private Scoreboard lobbyScoreboardFor(Player player) {
        UUID playerId = player.getUniqueId();
        Scoreboard sb = lobbyScoreboards.get(playerId);
        if (sb == null) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            game.display().registerOnScoreboard(sb, game.teams().all());
            lobbyScoreboards.put(playerId, sb);
            previousLobbyScoreboards.put(playerId, player.getScoreboard());
        }
        return sb;
    }

    private void restoreLobbyScoreboard(Player player) {
        Scoreboard own = lobbyScoreboards.remove(player.getUniqueId());
        Scoreboard previous = previousLobbyScoreboards.remove(player.getUniqueId());
        lobbyActiveEntries.remove(player.getUniqueId());
        if (own != null && player.getScoreboard().equals(own)) {
            player.setScoreboard(previous != null ? previous : Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void restoreLobbyScoreboards() {
        for (UUID playerId : new ArrayList<>(lobbyScoreboards.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                restoreLobbyScoreboard(player);
            }
        }
        lobbyScoreboards.clear();
        previousLobbyScoreboards.clear();
        lobbyActiveEntries.clear();
    }
}
