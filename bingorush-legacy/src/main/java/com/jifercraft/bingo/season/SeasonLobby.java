package com.jifercraft.bingo.season;

import com.cryptomorin.xseries.XSound;
import com.jifercraft.bingo.BingoRush;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * What the season does for players who are just hanging around the lobby:
 * snow under their feet, a season header in the player list, a countdown to
 * the big day, fireworks on it and on New Year. Runs once a second from
 * {@link Season}, and leaves the player list as it found it.
 */
final class SeasonLobby {

    private final BingoRush plugin;
    private final Season season;
    private final SeasonSettings settings;
    private final Random random = new Random();
    private final Map<UUID, Long> lastBlock = new HashMap<>();
    private final Set<UUID> withHeader = new HashSet<>();
    private int seconds;
    private int lastYear = -1;
    private int newYearShow;

    SeasonLobby(BingoRush plugin, Season season, SeasonSettings settings) {
        this.plugin = plugin;
        this.season = season;
        this.settings = settings;
    }

    void tick(SeasonPhase phase, long now, Location lobby) {
        seconds++;
        LocalDate today = Instant.ofEpochMilli(now).atZone(settings.calendar().zone()).toLocalDate();
        watchNewYear(today, lobby);
        Set<UUID> seen = new HashSet<>();
        if (phase != SeasonPhase.INACTIVE && lobby != null && lobby.getWorld() != null) {
            int radius = Math.max(25, settings.lobbyRadius()) * 2;
            for (Player player : lobby.getWorld().getPlayers()) {
                if (inRound(player) || player.getLocation().distanceSquared(lobby) > (double) radius * radius) {
                    continue;
                }
                seen.add(player.getUniqueId());
                if (phase.isVisualsActive() && settings.lobby().snowTrail()) {
                    trail(player);
                }
                if (settings.lobby().tabHeader() && seconds % 10 == 1) {
                    header(player, phase, today);
                }
                if (settings.lobby().countdown() && seconds % 2 == 0) {
                    countdown(player, now);
                }
            }
            if (isHighlight(today) && phase.isVisualsActive() && seconds % 20 == 0) {
                burst(lobby, 3);
            }
        }
        for (UUID id : new HashSet<>(withHeader)) {
            if (!seen.contains(id)) {
                clearHeader(id);
            }
        }
        lastBlock.keySet().retainAll(seen);
    }

    private boolean inRound(Player player) {
        return plugin.game() != null && plugin.game().round() != null
                && plugin.game().teams().of(player.getUniqueId()) != null;
    }

    // Only when they moved to another block, so standing still leaves no pile.
    private void trail(Player player) {
        Location at = player.getLocation();
        long block = ((long) at.getBlockX() << 38) | ((long) (at.getBlockZ() & 0x3FFFFFF) << 12) | (at.getBlockY() & 0xFFF);
        Long previous = lastBlock.put(player.getUniqueId(), block);
        if (previous != null && previous != block && !player.isFlying()) {
            season.effect(at.add(0, 0.1, 0), null, "SNOW_SHOVEL");
        }
    }

    private void header(Player player, SeasonPhase phase, LocalDate today) {
        String suffix;
        if (phase == SeasonPhase.PRE_SEASON) {
            suffix = "pre-season";
        } else if (phase == SeasonPhase.WRAP_UP) {
            suffix = "wrap-up";
        } else {
            suffix = isHighlight(today) ? "highlight" : "active";
        }
        plugin.messages().audience(player).sendPlayerListHeaderAndFooter(
                plugin.messages().render("season.tab-header-" + suffix),
                plugin.messages().render("season.tab-footer"));
        withHeader.add(player.getUniqueId());
    }

    private void clearHeader(UUID id) {
        withHeader.remove(id);
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            plugin.messages().audience(player).sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
    }

    private void countdown(Player player, long now) {
        long days = settings.daysToHighlight(now);
        // The gift hunt uses the action bar while a player is near a gift.
        if (days < 0 || season.nearGift(player)) {
            return;
        }
        String key = days == 0 ? "season.countdown-today" : days == 1 ? "season.countdown-tomorrow" : "season.countdown";
        plugin.messages().audience(player).sendActionBar(plugin.messages().render(key,
                Placeholder.unparsed("days", String.valueOf(days))));
    }

    private boolean isHighlight(LocalDate today) {
        MonthDay highlight = settings.lobby().highlight();
        return highlight != null && MonthDay.from(today).equals(highlight);
    }

    private void watchNewYear(LocalDate today, Location lobby) {
        int year = today.getYear();
        if (lastYear != -1 && year != lastYear && settings.lobby().newYear()) {
            newYearShow = 10;
            Title title = Title.title(plugin.messages().render("season.new-year-title"),
                    plugin.messages().render("season.new-year-subtitle", Placeholder.unparsed("year", String.valueOf(year))),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1)));
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.messages().audience(player).showTitle(title);
            }
        }
        lastYear = year;
        if (newYearShow > 0 && lobby != null && lobby.getWorld() != null) {
            newYearShow--;
            burst(lobby, 6);
        }
    }

    // Particles, not firework entities: a real rocket hurts whoever stands
    // next to it, and this is a lobby.
    private void burst(Location lobby, int count) {
        int r = Math.max(8, settings.lobbyRadius());
        for (int i = 0; i < count; i++) {
            Location at = lobby.clone().add(random.nextInt(r * 2 + 1) - r, 12 + random.nextInt(8),
                    random.nextInt(r * 2 + 1) - r);
            season.effect(at, null, "FIREWORKS_SPARK");
            XSound.ENTITY_FIREWORK_ROCKET_BLAST.play(at, 1.5f, 0.8f + random.nextFloat() * 0.4f);
        }
    }

    /** The line a player reads on joining while the season is on. */
    void greet(Player player, SeasonPhase phase, long now) {
        if (!settings.lobby().joinGreeting() || phase == SeasonPhase.INACTIVE) {
            return;
        }
        LocalDate today = Instant.ofEpochMilli(now).atZone(settings.calendar().zone()).toLocalDate();
        long days = settings.daysToHighlight(now);
        String key;
        if (phase == SeasonPhase.PRE_SEASON) {
            key = "season.greeting-pre-season";
        } else if (phase == SeasonPhase.WRAP_UP) {
            key = "season.greeting-wrap-up";
        } else if (isHighlight(today)) {
            key = "season.greeting-highlight";
        } else {
            key = days > 1 ? "season.greeting-countdown" : "season.greeting-active";
        }
        plugin.messages().send(player, key, Placeholder.unparsed("days", String.valueOf(days)));
    }

    /** Gives every header back, for the season stopping or switching off. */
    void clear() {
        for (UUID id : new HashSet<>(withHeader)) {
            clearHeader(id);
        }
        lastBlock.clear();
    }

    void forget(UUID playerId) {
        withHeader.remove(playerId);
        lastBlock.remove(playerId);
    }
}
