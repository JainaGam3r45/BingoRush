package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.config.BingoConfig;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
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

    // Sidebar lines are ordered by score, highest at the top.
    private static final int TIME_SCORE = 1000;
    private static final int FIRST_TEAM_SCORE = 990;
    private static final String TIME_ENTRY = "time";
    private static final Duration TITLE_FADE = Duration.ofMillis(300);
    private static final Duration TITLE_STAY = Duration.ofSeconds(3);
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

    public Hud(BingoRush plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    /** Shows sidebar, boss bar, start title and start sound for the new round. */
    public void roundStarted(long timeLimitSeconds) {
        limitSeconds = timeLimitSeconds;
        totalMs = timeLimitSeconds * 1000;
        if (timeLimitSeconds > 0) {
            scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            sidebar = scoreboard.registerNewObjective("bingo", Criteria.DUMMY,
                    plugin.messages().render("sidebar-title"));
            sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (Player player : participants()) {
                previousScoreboards.put(player.getUniqueId(), player.getScoreboard());
                player.setScoreboard(scoreboard);
            }
            bossBar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            for (Player player : participants()) {
                bossBar.addViewer(player);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(
                    plugin.messages().render("round-started-title"),
                    plugin.messages().render("round-started-subtitle"),
                    Title.Times.times(TITLE_FADE, TITLE_STAY, TITLE_FADE)));
        }
        play(BingoConfig.SoundId.START, Bukkit.getOnlinePlayers());
        refresh();
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

    /** A pling for everyone on every countdown second. */
    public void countdownTick() {
        play(BingoConfig.SoundId.COUNTDOWN, Bukkit.getOnlinePlayers());
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
        if (bossBar != null) {
            for (Player player : participants()) {
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
        Score time = sidebar.getScore(TIME_ENTRY);
        time.customName(Component.text("Tiempo " + Game.clock(remainingMs),
                plugin.messages().color("hl")));
        time.setScore(TIME_SCORE);
        int score = FIRST_TEAM_SCORE;
        for (Team team : game.teams().all()) {
            Score line = sidebar.getScore("team-" + team.id());
            line.customName(Component.text()
                    .append(Component.text(team.name() + " ", team.color()))
                    .append(Component.text(game.round().board().count(team.id()) + "/" + BingoCard.CELLS,
                            plugin.messages().color("muted")))
                    .build());
            line.setScore(score);
            score--;
        }
        if (bossBar == null) {
            return;
        }
        bossBar.name(plugin.messages().render("bossbar-title",
                Placeholder.unparsed("time", Game.clock(remainingMs))));
        bossBar.progress(totalMs <= 0 ? 0.0f
                : Math.max(0.0f, Math.min(1.0f, (float) remainingMs / (float) totalMs)));
    }

    private void play(BingoConfig.SoundId id, Iterable<? extends Player> to) {
        BingoConfig config = plugin.bingoConfig();
        if (config == null) {
            return;
        }
        Key key = config.sound(id);
        if (key == null) {
            return;
        }
        org.bukkit.Sound sound = RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.SOUND_EVENT).get(
                        new NamespacedKey(key.namespace(), key.value()));
        if (sound == null) {
            return;
        }
        Sound audible = Sound.sound(sound, Sound.Source.MASTER, SOUND_VOLUME, SOUND_PITCH);
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
}
