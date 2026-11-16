package com.jifercraft.bingo.card;

import java.util.List;

/**
 * One difficulty tier of the item pool: its items and how many cells of the
 * card it fills. Items are namespaced keys such as "minecraft:diamond"; the
 * plugin checks them against the item registry when it loads the config, so
 * the generator can trust them.
 */
public record ItemTier(String id, int cells, List<String> items) {

    public ItemTier {
        items = List.copyOf(items);
    }
}
