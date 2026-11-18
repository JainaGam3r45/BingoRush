package com.jifercraft.bingo.arena;

import com.jifercraft.bingo.BingoRush;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Free text for the arena editor: the next chat line of a player becomes the
 * answer instead of a message. "cancel" (or the word in the language file)
 * backs out, a minute of silence too, and nothing is saved in either case.
 */
public final class ChatPrompt implements Listener {

    private static final long TIMEOUT_TICKS = 60 * 20L;

    private record Pending(BiPredicate<Player, String> handler, BukkitTask timeout) {
    }

    private final BingoRush plugin;
    // Filled on the main thread, read from the chat thread.
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(BingoRush plugin) {
        this.plugin = plugin;
    }

    /**
     * Waits for the player's next chat line. The handler runs on the main
     * thread and returns false to keep waiting after a bad answer; it is
     * expected to say what was wrong.
     */
    public void ask(Player player, BiPredicate<Player, String> handler) {
        cancel(player.getUniqueId());
        UUID id = player.getUniqueId();
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(id) != null) {
                Player online = Bukkit.getPlayer(id);
                if (online != null) {
                    plugin.messages().send(online, "prompt.timed-out");
                }
            }
        }, TIMEOUT_TICKS);
        pending.put(id, new Pending(handler, timeout));
        plugin.messages().send(player, "prompt.how-to-cancel",
                Placeholder.unparsed("word", cancelWord()));
    }

    public boolean isWaiting(UUID playerId) {
        return pending.containsKey(playerId);
    }

    private void cancel(UUID playerId) {
        Pending old = pending.remove(playerId);
        if (old != null) {
            old.timeout().cancel();
        }
    }

    private String cancelWord() {
        return plugin.messages().plainText("prompt.cancel-word").trim().toLowerCase(Locale.ROOT);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!pending.containsKey(id)) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.originalMessage()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> answer(id, text));
    }

    private void answer(UUID id, String text) {
        Pending current = pending.get(id);
        Player player = Bukkit.getPlayer(id);
        if (current == null || player == null) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("cancel") || lower.equals(cancelWord())) {
            cancel(id);
            plugin.messages().send(player, "prompt.cancelled");
            return;
        }
        if (current.handler().test(player, text)) {
            // The handler may already have asked again; only drop this one.
            if (pending.get(id) == current) {
                cancel(id);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        for (Pending prompt : pending.values()) {
            prompt.timeout().cancel();
        }
        pending.clear();
    }
}
