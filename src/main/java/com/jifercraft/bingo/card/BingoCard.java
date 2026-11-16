package com.jifercraft.bingo.card;

import java.util.List;

/**
 * The 5x5 card every team plays against. Cells are stored row-major, so cell
 * 0 is the top left corner and cell 24 the bottom right one.
 */
public final class BingoCard {

    public static final int ROWS = 5;
    public static final int COLUMNS = 5;
    public static final int CELLS = ROWS * COLUMNS;

    private final long seed;
    private final List<String> items;

    public BingoCard(long seed, List<String> items) {
        if (items.size() != CELLS) {
            throw new IllegalArgumentException("A bingo card holds " + CELLS + " items, got " + items.size());
        }
        this.seed = seed;
        this.items = List.copyOf(items);
    }

    public long seed() {
        return seed;
    }

    /** Namespaced item key of one cell, such as "minecraft:diamond". */
    public String item(int cell) {
        return items.get(cell);
    }

    public List<String> items() {
        return items;
    }
}
