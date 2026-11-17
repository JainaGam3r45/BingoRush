package com.jifercraft.bingo.game;

import com.jifercraft.bingo.card.BingoCard;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

/**
 * Card advancements a player already had before an advancement round.
 * Minecraft never fires an advancement twice, so a player who owned one
 * could never claim its cell. The criteria are taken away for the round and
 * given back when it ends; the exact criteria are kept so partial progress,
 * such as half the biomes of a travel advancement, comes back as it was.
 */
final class OwnedAdvancements {

    private final Map<UUID, Map<String, Set<String>>> revoked = new HashMap<>();

    /**
     * Takes away the card advancements a player holds, except those for cells
     * their team already claimed this round.
     */
    void revoke(Player player, Round round, Team team) {
        for (int cell = 0; cell < BingoCard.CELLS; cell++) {
            if (team != null && round.board().isClaimed(cell, team.id())) {
                continue;
            }
            String key = round.card().item(cell);
            Advancement advancement = advancement(key);
            if (advancement == null) {
                continue;
            }
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            Collection<String> awarded = new ArrayList<>(progress.getAwardedCriteria());
            if (awarded.isEmpty()) {
                continue;
            }
            revoked.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>())
                    .computeIfAbsent(key, k -> new LinkedHashSet<>())
                    .addAll(awarded);
            for (String criterion : awarded) {
                progress.revokeCriteria(criterion);
            }
        }
    }

    /** Removes and returns what a player is owed, or an empty map. */
    Map<String, List<String>> take(UUID playerId) {
        Map<String, Set<String>> owed = revoked.remove(playerId);
        Map<String, List<String>> copy = new HashMap<>();
        if (owed != null) {
            owed.forEach((key, criteria) -> copy.put(key, new ArrayList<>(criteria)));
        }
        return copy;
    }

    Set<UUID> players() {
        return Set.copyOf(revoked.keySet());
    }

    Map<String, Map<String, List<String>>> snapshot() {
        Map<String, Map<String, List<String>>> copy = new HashMap<>();
        revoked.forEach((id, owed) -> {
            Map<String, List<String>> perKey = new HashMap<>();
            owed.forEach((key, criteria) -> perKey.put(key, new ArrayList<>(criteria)));
            copy.put(id.toString(), perKey);
        });
        return copy;
    }

    void load(Map<UUID, Map<String, List<String>>> saved) {
        revoked.clear();
        saved.forEach((id, owed) -> {
            Map<String, Set<String>> perKey = new HashMap<>();
            owed.forEach((key, criteria) -> perKey.put(key, new LinkedHashSet<>(criteria)));
            revoked.put(id, perKey);
        });
    }

    void clear() {
        revoked.clear();
    }

    /** Hands criteria back. Fires PlayerAdvancementDoneEvent like any other award. */
    static void award(Player player, Map<String, ? extends Collection<String>> owed) {
        owed.forEach((key, criteria) -> {
            Advancement advancement = advancement(key);
            if (advancement == null) {
                return;
            }
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            for (String criterion : criteria) {
                progress.awardCriteria(criterion);
            }
        });
    }

    private static Advancement advancement(String key) {
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        return namespaced == null ? null : Bukkit.getAdvancement(namespaced);
    }
}
