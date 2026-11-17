package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.Messages;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Team colors in the player list and a team format in chat, for players on a
 * team while a round runs. Both are optional, because another plugin may own
 * the tab or the chat. What it changes it remembers, so the end of the round,
 * or a reload that turns a feature off, takes back exactly that. The list
 * order is not available on these versions, so teams are not grouped.
 */
final class TeamDisplay {

    // What was there before, and whether a header and footer went out.
    private static final class Applied {
        private final String previousName;
        private final boolean framed;

        private Applied(String previousName, boolean framed) {
            this.previousName = previousName;
            this.framed = framed;
        }
    }

    private final BingoRush plugin;
    private final Map<UUID, Applied> tabs = new HashMap<>();
    // Chat events arrive on another thread, where Teams is not safe to read.
    // This map is filled on the main thread and is all the chat handler uses.
    private final Map<UUID, Team> chatTeams = new ConcurrentHashMap<>();
    private volatile boolean warnedAboutChatFormat;

    TeamDisplay(BingoRush plugin) {
        this.plugin = plugin;
    }

    void applyAll(Collection<Team> teams) {
        for (Team team : teams) {
            for (UUID memberId : team.members()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member != null) {
                    apply(member, team);
                }
            }
        }
    }

    void apply(Player player, Team team) {
        BingoConfig config = plugin.bingoConfig();
        if (config == null) {
            return;
        }
        if (config.chatEnabled()) {
            chatTeams.put(player.getUniqueId(), team);
        }
        if (config.tabEnabled()) {
            applyTab(player, team);
        }
    }

    private void applyTab(Player player, Team team) {
        Messages messages = plugin.messages();
        TagResolver[] tags = tags(player, team);
        Applied before = tabs.get(player.getUniqueId());
        // A second apply (a reload, a rejoin) keeps what was there first.
        String previousName = before != null ? before.previousName : player.getPlayerListName();

        player.setPlayerListName(messages.legacy(messages.render("tab-format", tags)));

        Component header = messages.render("tab-header", tags);
        Component footer = messages.render("tab-footer", tags);
        boolean framed = !isBlank(header) || !isBlank(footer);
        if (framed) {
            messages.audience(player).sendPlayerListHeaderAndFooter(header, footer);
        }
        tabs.put(player.getUniqueId(), new Applied(previousName, framed));
    }

    void clear(Player player) {
        chatTeams.remove(player.getUniqueId());
        Applied applied = tabs.remove(player.getUniqueId());
        if (applied == null) {
            return;
        }
        player.setPlayerListName(applied.previousName);
        if (applied.framed) {
            plugin.messages().audience(player).sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
    }

    void clearAll() {
        for (UUID playerId : new ArrayList<>(tabs.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                clear(player);
            }
        }
        tabs.clear();
        chatTeams.clear();
    }

    /** A player left the server; the tab state dies with their session. */
    void forget(UUID playerId) {
        tabs.remove(playerId);
        chatTeams.remove(playerId);
    }

    /** Runs on the chat thread. */
    void styleChat(AsyncPlayerChatEvent event) {
        Team team = chatTeams.get(event.getPlayer().getUniqueId());
        if (team == null) {
            return;
        }
        Messages messages = plugin.messages();
        // The player and the message stay as tokens through rendering and
        // become Bukkit's %1$s and %2$s afterwards; ChatFormat escapes the rest.
        String format = ChatFormat.toFormat(messages.legacy(messages.render("chat-format",
                Game.teamPlaceholder(team),
                Placeholder.unparsed("player", ChatFormat.PLAYER_TOKEN),
                Placeholder.unparsed("message", ChatFormat.MESSAGE_TOKEN),
                Placeholder.unparsed("initial", TeamLabel.initial(team.name())),
                TagResolver.resolver("color", Tag.styling(team.color())))));
        if (format == null) {
            // A template without <message> would swallow what players write.
            if (!warnedAboutChatFormat) {
                warnedAboutChatFormat = true;
                plugin.getLogger().warning("chat-format has no <message>, so chat is left as it is until it does");
            }
            return;
        }
        event.setFormat(format);
    }

    private static TagResolver[] tags(Player player, Team team) {
        return new TagResolver[]{
                Game.teamPlaceholder(team),
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("initial", TeamLabel.initial(team.name())),
                TagResolver.resolver("color", Tag.styling(team.color()))
        };
    }

    private static boolean isBlank(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component).trim().isEmpty();
    }
}
