package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.config.ScoreboardDefinition;
import com.jifercraft.bingo.history.MatchHistory;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
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

    private final BingoRush plugin;
    private final Game game;

    private BossBar bossBar;
    private Objective sidebar;
    private Scoreboard scoreboard;
    private long totalMs;
    private long limitSeconds;
    private final Map<UUID, Scoreboard> previousScoreboards = new HashMap<>();
    private final Set<String> activeEntries = new HashSet<>();
    private final Map<UUID, Scoreboard> lobbyScoreboards = new HashMap<>();
    private final Map<UUID, Scoreboard> previousLobbyScoreboards = new HashMap<>();
    private BukkitTask lobbyTask;

    public Hud(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    /** Shows sidebar, boss bar, start title and start sound for the new round. */
    public void roundStarted(long timeLimitSeconds) {
        limitSeconds = timeLimitSeconds;
        totalMs = timeLimitSeconds * 1000;
        if (playingScoreboardEnabled()) {
            buildPlayingScoreboard();
        } else {
            restoreLobbyScoreboards();
        }
        if (timeLimitSeconds > 0) {
            bossBar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            for (Player player : participants()) {
                bossBar.addViewer(player);
            }
        }
        String subtitleKey = game.round() != null && game.round().mode() == BingoGameMode.ADVANCEMENTS
                ? "round-started-subtitle-advancements"
                : "round-started-subtitle";
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(
                    plugin.messages().render("round-started-title"),
                    plugin.messages().render(subtitleKey),
                    Title.Times.times(TITLE_FADE, TITLE_STAY, TITLE_FADE)));
        }
        play(BingoConfig.SoundId.START, Bukkit.getOnlinePlayers());
        refresh();
    }

    /** Reconstructs scoreboard and prepared boss bar for a restored paused round. */
    public void restorePaused(long timeLimitSeconds) {
        limitSeconds = timeLimitSeconds;
        totalMs = timeLimitSeconds * 1000;
        if (playingScoreboardEnabled()) {
            buildPlayingScoreboard();
        }
        if (timeLimitSeconds > 0) {
            bossBar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        }
        refresh();
    }

    private boolean playingScoreboardEnabled() {
        return plugin.bingoConfig() == null || plugin.bingoConfig().playingScoreboard();
    }

    private void buildPlayingScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        ScoreboardDefinition cfg = plugin.bingoConfig() != null ? plugin.bingoConfig().playingScoreboardConfig() : null;
        Component title = (cfg != null && cfg.title() != null && !cfg.title().isBlank())
                ? plugin.messages().renderTemplate(cfg.title())
                : plugin.messages().render("scoreboard.playing.title");
        sidebar = scoreboard.registerNewObjective("bingo", Criteria.DUMMY, title);
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        sidebar.numberFormat(NumberFormat.blank());
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
        Title title = Title.title(
                plugin.messages().render("round-countdown-title",
                        Placeholder.unparsed("count", String.valueOf(secondsLeft))),
                Component.empty(),
                Title.Times.times(Duration.ZERO, COUNTDOWN_STAY, COUNTDOWN_FADE_OUT));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
        }
        play(BingoConfig.SoundId.COUNTDOWN, Bukkit.getOnlinePlayers());
    }

    /** Pauses the clock and removes the boss bar from screens. */
    public void pause() {
        if (bossBar != null) {
            for (Player player : participants()) {
                bossBar.removeViewer(player);
            }
        }
    }

    /** Restores the boss bar on screen when the round resumes. */
    public void resume() {
        if (bossBar != null) {
            for (Player player : participants()) {
                bossBar.addViewer(player);
            }
            refresh();
        }
    }

    /** Gives late joiners the round scoreboard and active boss bar. */
    public void playerJoined(Player player) {
        if (scoreboard != null) {
            previousScoreboards.put(player.getUniqueId(), player.getScoreboard());
            player.setScoreboard(scoreboard);
        }
        if (bossBar != null && game.round() != null && game.round().state() == Round.State.RUNNING) {
            bossBar.addViewer(player);
        }
    }

    /** Restores previous scoreboard when a player leaves their team. */
    public void playerLeft(Player player) {
        Scoreboard prev = previousScoreboards.remove(player.getUniqueId());
        if (prev != null) {
            player.setScoreboard(prev);
        } else {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        if (bossBar != null) {
            bossBar.removeViewer(player);
        }
    }

    /** A team completed the card or the line. */
    public void won(Team winner) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(
                    plugin.messages().render("round-won-title", Game.teamPlaceholder(winner)),
                    plugin.messages().render("round-won-subtitle"),
                    Title.Times.times(TITLE_FADE, TITLE_STAY, TITLE_FADE)));
        }
        play(BingoConfig.SoundId.WIN, Bukkit.getOnlinePlayers());
    }

    /** The clock hit zero with no completion. */
    public void timeUp() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(
                    plugin.messages().render("round-time-up-title"),
                    Component.empty(),
                    Title.Times.times(TITLE_FADE, TITLE_STAY, TITLE_FADE)));
        }
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
        if (bossBar != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                bossBar.removeViewer(player);
            }
            bossBar = null;
        }
        sidebar = null;
        scoreboard = null;
    }

    private void refresh() {
        if (sidebar == null) {
            return;
        }
        long remainingMs = game.round().remainingMs(limitSeconds);
        String timeStr = limitSeconds <= 0 ? Game.clock(game.round().elapsedMs()) : Game.clock(remainingMs);

        record SidebarLine(String entry, Component component) {
        }
        Component server = plugin.messages().render("scoreboard.server");
        List<SidebarLine> lines = new ArrayList<>();
        ScoreboardDefinition cfg = plugin.bingoConfig() != null ? plugin.bingoConfig().playingScoreboardConfig() : null;
        if (cfg != null && !cfg.lines().isEmpty()) {
            String teamFmt = cfg.teamFormat() != null && !cfg.teamFormat().isBlank()
                    ? cfg.teamFormat()
                    : "<color>❙</color> <team>: <white><count>/<total></white>";
            int lineIdx = 0;
            for (String raw : cfg.lines()) {
                if (raw.trim().equalsIgnoreCase("<teams>") || raw.trim().equalsIgnoreCase("%teams%")) {
                    for (Team team : game.teams().all()) {
                        if (team.members().isEmpty()) {
                            continue;
                        }
                        Component teamLine = plugin.messages().renderTemplate(teamFmt,
                                TagResolver.resolver("color", Tag.styling(team.color())),
                                Game.teamPlaceholder(team),
                                Placeholder.unparsed("count", String.valueOf(game.round().board().count(team.id()))),
                                Placeholder.unparsed("total", String.valueOf(BingoCard.CELLS)));
                        lines.add(new SidebarLine("team-" + team.id(), teamLine));
                    }
                } else {
                    Component lineComp = plugin.messages().renderTemplate(raw,
                            Placeholder.unparsed("time", timeStr),
                            Placeholder.component("server", server));
                    lines.add(new SidebarLine("line_" + (lineIdx++), lineComp));
                }
            }
        } else {
            lines.add(new SidebarLine("sep1", plugin.messages().render("scoreboard.playing.separator")));
            lines.add(new SidebarLine("time", plugin.messages().render("scoreboard.playing.time",
                    Placeholder.unparsed("time", timeStr))));
            lines.add(new SidebarLine("blank1", Component.empty()));
            lines.add(new SidebarLine("teams_header", plugin.messages().render("scoreboard.playing.teams-header")));
            for (Team team : game.teams().all()) {
                if (team.members().isEmpty()) {
                    continue;
                }
                lines.add(new SidebarLine("team-" + team.id(), plugin.messages().render("scoreboard.playing.team-line",
                        TagResolver.resolver("color", Tag.styling(team.color())),
                        Game.teamPlaceholder(team),
                        Placeholder.unparsed("count", String.valueOf(game.round().board().count(team.id()))),
                        Placeholder.unparsed("total", String.valueOf(BingoCard.CELLS)))));
            }
            lines.add(new SidebarLine("blank2", Component.empty()));
            lines.add(new SidebarLine("sep2", plugin.messages().render("scoreboard.playing.separator")));
            lines.add(new SidebarLine("footer", plugin.messages().render("scoreboard.playing.footer",
                    Placeholder.component("server", server))));
        }

        Set<String> currentEntries = new HashSet<>();
        int score = lines.size();
        for (SidebarLine line : lines) {
            currentEntries.add(line.entry());
            Score s = sidebar.getScore(line.entry());
            s.customName(line.component());
            s.setScore(score--);
        }
        for (String old : activeEntries) {
            if (!currentEntries.contains(old)) {
                scoreboard.resetScores(old);
            }
        }
        activeEntries.clear();
        activeEntries.addAll(currentEntries);

        if (bossBar == null) {
            return;
        }
        bossBar.name(plugin.messages().render("bossbar-title",
                Placeholder.unparsed("time", Game.clock(remainingMs))));
        bossBar.progress(totalMs <= 0 ? 0.0f
                : Math.max(0.0f, Math.min(1.0f, (float) remainingMs / (float) totalMs)));
    }

    /** Plays the configured sound to one player, for menu feedback. */
    public void play(BingoConfig.SoundId id, Player player) {
        play(id, List.of(player));
    }

    private void play(BingoConfig.SoundId id, Iterable<? extends Player> to) {
        BingoConfig config = plugin.bingoConfig();
        if (config == null) {
            return;
        }
        Key key = config.sound(id);
        if (key == null && id == BingoConfig.SoundId.LOCKOUT_LOST) {
            key = Key.key("minecraft:block.note_block.bass");
        }
        if (key == null) {
            return;
        }
        org.bukkit.Sound sound = RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.SOUND_EVENT).get(
                        new NamespacedKey(key.namespace(), key.value()));
        if (sound == null) {
            return;
        }
        float pitch = id == BingoConfig.SoundId.LOCKOUT_LOST ? 0.8f : SOUND_PITCH;
        Sound audible = Sound.sound(sound, Sound.Source.MASTER, SOUND_VOLUME, pitch);
        for (Player player : to) {
            player.playSound(audible);
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

    public GameMapDefinition resolveWaitingArena(Player player) {
        String arenaId = game.matchWorldManager() != null ? game.matchWorldManager().arenaIdOf(player.getWorld()) : null;
        if (arenaId != null) {
            return game.getArena(arenaId);
        }
        if (game.arenaManager() != null) {
            for (GameMapDefinition a : game.arenaManager().getAllArenas()) {
                if (a.waitingLobby() != null && a.waitingLobby().getWorld() != null
                        && a.waitingLobby().getWorld().equals(player.getWorld())
                        && a.waitingLobby().distanceSquared(player.getLocation()) < 400) {
                    return a;
                }
            }
        }
        return null;
    }

    private void renderWaitingScoreboard(Player player, GameMapDefinition arena) {
        Scoreboard sb = lobbyScoreboardFor(player);
        ScoreboardDefinition cfg = plugin.bingoConfig() != null ? plugin.bingoConfig().waitingScoreboardConfig() : null;
        Component title = (cfg != null && cfg.title() != null && !cfg.title().isBlank())
                ? plugin.messages().renderTemplate(cfg.title())
                : plugin.messages().render("scoreboard.waiting.title");
        Objective obj = sb.getObjective("bingo_hud");
        if (obj == null) {
            obj = sb.registerNewObjective("bingo_hud", Criteria.DUMMY, title);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            obj.numberFormat(NumberFormat.blank());
        } else {
            obj.displayName(title);
        }

        Team team = game.teams().of(player.getUniqueId());
        Component teamComp = team != null
                ? Component.text(team.name(), team.color())
                : plugin.messages().render("scoreboard.no-team");

        String modeKey = switch (arena.teamSize()) {
            case 1 -> "scoreboard.waiting.modes.solo";
            case 2 -> "scoreboard.waiting.modes.duo";
            case 3 -> "scoreboard.waiting.modes.trio";
            case 4 -> "scoreboard.waiting.modes.squad";
            default -> "scoreboard.waiting.modes.custom";
        };
        Component mode = plugin.messages().render(modeKey,
                Placeholder.unparsed("size", String.valueOf(arena.teamSize())));

        int currentPlayers = game.matchWorldManager() != null ? game.matchWorldManager().playersIn(arena.id()).size() : 0;
        Component server = plugin.messages().render("scoreboard.server");

        BingoGameMode activeGameMode = game.modeVoteManager() != null
                ? game.modeVoteManager().applyVotes(arena).mode()
                : arena.mode();
        Component gameModeComp = plugin.messages().render(
                activeGameMode == BingoGameMode.ITEMS ? "arena-menu.mode-items" : "arena-menu.mode-advancements");

        TagResolver[] placeholders = new TagResolver[] {
                Placeholder.unparsed("arena", arena.name()),
                Placeholder.component("mode", mode),
                Placeholder.component("gamemode", gameModeComp),
                Placeholder.component("game_mode", gameModeComp),
                Placeholder.unparsed("players", String.valueOf(currentPlayers)),
                Placeholder.unparsed("max", String.valueOf(arena.maxPlayers())),
                Placeholder.component("team", teamComp),
                Placeholder.component("status", plugin.messages().render("scoreboard.waiting.state-waiting")),
                Placeholder.component("server", server)
        };

        List<Component> lines = (cfg != null && !cfg.lines().isEmpty())
                ? plugin.messages().renderTemplateLines(cfg.lines(), placeholders)
                : plugin.messages().renderLines("scoreboard.waiting.lines", placeholders);

        applyScoreboardLines(sb, obj, lines);
        showLobbyScoreboard(player, sb);
    }

    private void renderMainLobbyScoreboard(Player player) {
        Scoreboard sb = lobbyScoreboardFor(player);
        ScoreboardDefinition cfg = plugin.bingoConfig() != null ? plugin.bingoConfig().lobbyScoreboardConfig() : null;
        Component title = (cfg != null && cfg.title() != null && !cfg.title().isBlank())
                ? plugin.messages().renderTemplate(cfg.title())
                : plugin.messages().render("scoreboard.lobby.title");
        Objective obj = sb.getObjective("bingo_hud");
        if (obj == null) {
            obj = sb.registerNewObjective("bingo_hud", Criteria.DUMMY, title);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            obj.numberFormat(NumberFormat.blank());
        } else {
            obj.displayName(title);
        }

        MatchHistory.PlayerStats stats = plugin.matchHistory() != null
                ? plugin.matchHistory().getPlayerStats(player.getName())
                : new MatchHistory.PlayerStats(0, 0, 0);

        Component season = plugin.messages().render(seasonKey());
        Component server = plugin.messages().render("scoreboard.server");

        TagResolver[] placeholders = new TagResolver[] {
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("matches", String.valueOf(stats.matches())),
                Placeholder.unparsed("wins", String.valueOf(stats.wins())),
                Placeholder.unparsed("cells", String.valueOf(stats.claims())),
                Placeholder.component("season", season),
                Placeholder.unparsed("online", String.valueOf(Bukkit.getOnlinePlayers().size())),
                Placeholder.component("server", server)
        };

        List<Component> lines = (cfg != null && !cfg.lines().isEmpty())
                ? plugin.messages().renderTemplateLines(cfg.lines(), placeholders)
                : plugin.messages().renderLines("scoreboard.lobby.lines", placeholders);

        applyScoreboardLines(sb, obj, lines);
        showLobbyScoreboard(player, sb);
    }

    private String seasonKey() {
        if (plugin.season() == null || plugin.season().currentPhase() == null) {
            return "scoreboard.season.inactive";
        }
        return switch (plugin.season().currentPhase()) {
            case PRE_SEASON -> "scoreboard.season.pre-season";
            case ACTIVE -> "scoreboard.season.active";
            case WRAP_UP -> "scoreboard.season.wrap-up";
            default -> "scoreboard.season.inactive";
        };
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

    private void showLobbyScoreboard(Player player, Scoreboard sb) {
        if (!player.getScoreboard().equals(sb)) {
            player.setScoreboard(sb);
        }
    }

    private void restoreLobbyScoreboard(Player player) {
        Scoreboard own = lobbyScoreboards.remove(player.getUniqueId());
        Scoreboard previous = previousLobbyScoreboards.remove(player.getUniqueId());
        if (own != null && player.getScoreboard().equals(own)) {
            player.setScoreboard(previous != null ? previous : Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void restoreLobbyScoreboards() {
        for (UUID playerId : new HashSet<>(lobbyScoreboards.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                restoreLobbyScoreboard(player);
            }
        }
        lobbyScoreboards.clear();
        previousLobbyScoreboards.clear();
    }

    private void applyScoreboardLines(Scoreboard sb, Objective obj, List<Component> lines) {
        int score = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String entry = "line_" + i;
            Score s = obj.getScore(entry);
            s.customName(lines.get(i));
            s.setScore(score--);
        }
        for (int i = lines.size(); i < 16; i++) {
            sb.resetScores("line_" + i);
        }
    }
}
