package com.jifercraft.bingo.game;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.config.Messages;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
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

/**
 * Team colors in the player list and a team format in chat, for players on a
 * team. Both are optional, because another plugin may own the tab or the chat.
 */
public final class TeamDisplay {

    // What was written for one player, and what was there before it.
    private record Applied(Component previousName, int order, int previousOrder, boolean framed) {
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
        Component previousName = before != null ? before.previousName() : player.playerListName();
        int previousOrder = before != null ? before.previousOrder() : player.getPlayerListOrder();

        String formatKey = team != null ? "tab-format" : "tab-format-none";
        player.playerListName(messages.render(formatKey, tags));
        // Players with the same order are listed together, so each team forms a block.
        int order = team != null ? team.id() + 1 : 999;
        player.setPlayerListOrder(order);

        String headerKey = team != null ? "tab-header" : "tab-header-none";
        String footerKey = team != null ? "tab-footer" : "tab-footer-none";
        Component header = messages.render(headerKey, tags);
        Component footer = messages.render(footerKey, tags);
        boolean framed = !isBlank(header) || !isBlank(footer);
        if (framed) {
            player.sendPlayerListHeaderAndFooter(header, footer);
        }
        tabs.put(player.getUniqueId(), new Applied(previousName, order, previousOrder, framed));
    }

    void clear(Player player) {
        chatTeams.remove(player.getUniqueId());
        Applied applied = tabs.remove(player.getUniqueId());
        if (applied == null) {
            return;
        }
        // The name goes back unconditionally: comparing it with what was set is
        // unreliable, because the server hands back a converted copy.
        player.playerListName(applied.previousName());
        if (player.getPlayerListOrder() == applied.order()) {
            player.setPlayerListOrder(applied.previousOrder());
        }
        if (applied.framed()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
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
    void styleChat(AsyncChatEvent event) {
        BingoConfig config = plugin.bingoConfig();
        if (config == null || !config.chatEnabled()) {
            return;
        }
        Team team = chatTeams.get(event.getPlayer().getUniqueId());
        Messages messages = plugin.messages();
        String templateKey = team != null ? "chat-format" : "chat-format-none";
        // A template without <message> would swallow what players write.
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

        event.renderer(ChatRenderer.viewerUnaware((source, name, message) -> messages.render(templateKey,
                teamTag,
                teamUpperTag,
                Placeholder.component("player", name),
                Placeholder.component("message", message),
                initialTag,
                colorTag)));
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
        return PlainTextComponentSerializer.plainText().serialize(component).isBlank();
    }
}
