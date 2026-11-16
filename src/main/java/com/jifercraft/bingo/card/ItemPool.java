package com.jifercraft.bingo.card;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The configured item pool, checked so the generator can trust it. Every
 * failure here is a mistake in config.yml, so the messages name the tier or
 * item to fix and the config loader reports them without a stack trace.
 */
public final class ItemPool {

    private final List<ItemTier> tiers;
    // Item key to the id of the tier that gets to claim it. Items keep the
    // first tier that lists them, matching the rule in the constructor.
    private final Map<String, String> tierByItem = new HashMap<>();

    public ItemPool(List<ItemTier> tiers) {
        List<ItemTier> copy = List.copyOf(tiers);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("The item pool has no tiers");
        }
        int total = 0;
        Set<String> tierIds = new HashSet<>();
        // Items keep the first tier that lists them, so an admin who moves an
        // item between tiers cannot silently shrink the later tier.
        Set<String> claimedByEarlierTiers = new HashSet<>();
        for (ItemTier tier : copy) {
            if (tier.id() == null || tier.id().isBlank()) {
                throw new IllegalArgumentException("A tier of the item pool has no id");
            }
            if (!tierIds.add(tier.id())) {
                throw new IllegalArgumentException("The tier '" + tier.id() + "' appears twice in the item pool");
            }
            if (tier.cells() < 0) {
                throw new IllegalArgumentException("The tier '" + tier.id() + "' wants " + tier.cells() + " cells");
            }
            Set<String> inTier = new HashSet<>();
            for (String item : tier.items()) {
                if (!inTier.add(item)) {
                    throw new IllegalArgumentException("The item '" + item + "' is listed twice in the tier '" + tier.id() + "'");
                }
            }
            if (tier.items().size() < tier.cells()) {
                throw new IllegalArgumentException("The tier '" + tier.id() + "' fills " + tier.cells()
                        + " cells but only has " + tier.items().size() + " items");
            }
            long available = tier.items().stream().filter(item -> !claimedByEarlierTiers.contains(item)).count();
            if (available < tier.cells()) {
                throw new IllegalArgumentException("The tier '" + tier.id() + "' fills " + tier.cells()
                        + " cells but " + (tier.items().size() - available)
                        + " of its items already belong to an earlier tier");
            }
            claimedByEarlierTiers.addAll(tier.items());
            for (String item : tier.items()) {
                tierByItem.putIfAbsent(item, tier.id());
            }
            total += tier.cells();
        }
        if (total != BingoCard.CELLS) {
            throw new IllegalArgumentException("The tiers fill " + total + " cells but a card holds " + BingoCard.CELLS);
        }
        this.tiers = copy;
    }

    public List<ItemTier> tiers() {
        return tiers;
    }

    /** The tier id an item belongs to, or null when no tier lists it. */
    public String tierOf(String item) {
        return tierByItem.get(item);
    }
}
