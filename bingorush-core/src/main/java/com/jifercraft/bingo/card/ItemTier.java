package com.jifercraft.bingo.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One difficulty tier of the item pool: its items and how many cells of the
 * card it fills. Items are namespaced keys such as "minecraft:diamond"; the
 * plugin checks them against the item registry when it loads the config, so
 * the generator can trust them.
 */
public final class ItemTier {

    private final String id;
    private final int cells;
    private final List<String> items;

    public ItemTier(String id, int cells, List<String> items) {
        this.id = id;
        this.cells = cells;
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    public String id() {
        return id;
    }

    public int cells() {
        return cells;
    }

    public List<String> items() {
        return items;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ItemTier)) {
            return false;
        }
        ItemTier other = (ItemTier) o;
        return cells == other.cells && id.equals(other.id) && items.equals(other.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, cells, items);
    }
}
