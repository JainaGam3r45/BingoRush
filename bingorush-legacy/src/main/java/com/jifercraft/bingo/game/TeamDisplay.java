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
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Team colors in the player list and a team format in chat, for players on a
 * team. Both are optional, because another plugin may own the tab or the chat.
 */
public final class TeamDisplay {

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

    public void applyAll(Collection<Team> teams) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Team team = null;
            for (Team t : teams) {
                if (t.hasMember(player.getUniqueId())) {
                    team = t;
                    break;
                }
            }
            apply(player, team);
        }
    }

    public void apply(Player player, Team team) {
        BingoConfig config = plugin.bingoConfig();
        if (config == null) {
            return;
        }
        if (config.chatEnabled()) {
            if (team != null) {
                chatTeams.put(player.getUniqueId(), team);
            } else {
                chatTeams.remove(player.getUniqueId());
            }
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

        String formatKey = team != null ? "tab-format" : "tab-format-none";
        player.setPlayerListName(messages.legacy(messages.render(formatKey, tags)));

        String headerKey = team != null ? "tab-header" : "tab-header-none";
        String footerKey = team != null ? "tab-footer" : "tab-footer-none";
        Component header = messages.render(headerKey, tags);
        Component footer = messages.render(footerKey, tags);
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
        BingoConfig config = plugin.bingoConfig();
        if (config == null || !config.chatEnabled()) {
            return;
        }
        Team team = chatTeams.get(event.getPlayer().getUniqueId());
        Messages messages = plugin.messages();
        String templateKey = team != null ? "chat-format" : "chat-format-none";
        if (!messages.templateHas(templateKey, "<message>")) {
            if (!warnedAboutChatFormat) {
                warnedAboutChatFormat = true;
                plugin.getLogger().warning(templateKey + " has no <message>, so chat is left as it is until it does");
            }
            return;
        }
        TagResolver teamTag = team != null ? Game.teamPlaceholder(team) : Placeholder.component("team", Component.empty());
        TagResolver teamUpperTag = Placeholder.unparsed("team-upper", team != null ? team.name().toUpperCase() : "");
        TagResolver colorTag = team != null ? TagResolver.resolver("color", Tag.styling(team.color())) : TagResolver.resolver("color", Tag.styling(NamedTextColor.WHITE));
        TagResolver initialTag = Placeholder.unparsed("initial", team != null ? TeamLabel.initial(team.name()) : "");

        String format = ChatFormat.toFormat(messages.legacy(messages.render(templateKey,
                teamTag,
                teamUpperTag,
                Placeholder.unparsed("player", ChatFormat.PLAYER_TOKEN),
                Placeholder.unparsed("message", ChatFormat.MESSAGE_TOKEN),
                initialTag,
                colorTag)));
        if (format != null) {
            event.setFormat(format);
        }
    }

    private static TagResolver[] tags(Player player, Team team) {
        TagResolver teamTag = team != null ? Game.teamPlaceholder(team) : Placeholder.component("team", Component.empty());
        TagResolver teamUpperTag = Placeholder.unparsed("team-upper", team != null ? team.name().toUpperCase() : "");
        TagResolver colorTag = team != null ? TagResolver.resolver("color", Tag.styling(team.color())) : TagResolver.resolver("color", Tag.styling(NamedTextColor.GRAY));
        TagResolver initialTag = Placeholder.unparsed("initial", team != null ? TeamLabel.initial(team.name()) : "");
        return new TagResolver[]{
                teamTag,
                teamUpperTag,
                Placeholder.unparsed("player", player.getName()),
                initialTag,
                colorTag
        };
    }

    private static boolean isBlank(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component).trim().isEmpty();
    }
}
