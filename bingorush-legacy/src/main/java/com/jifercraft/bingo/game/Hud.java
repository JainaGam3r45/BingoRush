package com.jifercraft.bingo.game;

import com.cryptomorin.xseries.XSound;
import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.compat.ServerVersion;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.Messages;
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

    public Hud(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    private Messages messages() {
        return plugin.messages();
    }

    /** Shows sidebar, boss bar, start title and start sound for the new round. */
    public void roundStarted(long timeLimitSeconds) {
        build(timeLimitSeconds);
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
        build(timeLimitSeconds);
        refresh();
    }

    private void build(long timeLimitSeconds) {
        limitSeconds = timeLimitSeconds;
        totalMs = timeLimitSeconds * 1000;
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        sidebar = scoreboard.registerNewObjective("bingo", "dummy");
        sidebar.setDisplayName(fit(messages().legacy(messages().render("sidebar-title")), 32));
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (Player player : participants()) {
            previousScoreboards.put(player.getUniqueId(), player.getScoreboard());
            player.setScoreboard(scoreboard);
        }
        if (timeLimitSeconds > 0) {
            bossBar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
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
        lines.add(messages().render("sidebar-separator"));
        lines.add(messages().render("sidebar-time", Placeholder.unparsed("time", time)));
        lines.add(Component.empty());
        lines.add(messages().render("sidebar-teams-header"));
        for (Team team : game.teams().all()) {
            lines.add(messages().render("sidebar-team-entry",
                    TagResolver.resolver("color", Tag.styling(team.color())),
                    Game.teamPlaceholder(team),
                    Placeholder.unparsed("count", String.valueOf(game.round().board().count(team.id()))),
                    Placeholder.unparsed("total", String.valueOf(BingoCard.CELLS))));
        }
        lines.add(Component.empty());
        lines.add(messages().render("sidebar-separator"));
        lines.add(messages().render("sidebar-footer"));

        // Scoreboard entries before 1.13 hold 40 characters, color codes included.
        int width = ServerVersion.atLeast(13) ? 120 : 36;
        Set<String> current = new HashSet<>();
        int score = lines.size();
        for (int i = 0; i < lines.size() && i < UNIQUE.length(); i++) {
            String entry = fit(messages().legacy(lines.get(i)), width) + "§" + UNIQUE.charAt(i);
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
}
