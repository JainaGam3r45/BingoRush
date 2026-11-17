package com.jifercraft.bingo.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Picks items from the pool and shuffles them into a card. The same pool and
 * seed always produce the same card, and that holds across restarts too
 * because java.util.Random's algorithm is specified by the JDK.
 */
public final class CardGenerator {

    private final ItemPool pool;

    public CardGenerator(ItemPool pool) {
        this.pool = pool;
    }

    public BingoCard generate(long seed) {
        Random random = new Random(seed);
        Set<String> used = new HashSet<>();
        List<String> picks = new ArrayList<>(BingoCard.CELLS);
        for (ItemTier tier : pool.tiers()) {
            List<String> bag = new ArrayList<>(tier.items());
            Collections.shuffle(bag, random);
            int taken = 0;
            for (String item : bag) {
                if (taken == tier.cells()) {
                    break;
                }
                if (used.add(item)) {
                    picks.add(item);
                    taken++;
                }
            }
        }
        Collections.shuffle(picks, random);
        return new BingoCard(seed, picks);
    }

    /**
     * Turns whatever the admin typed into a seed. Numbers are used as they
     * are, other text is hashed with String.hashCode, which the JDK keeps
     * stable, so "evento-verano" replays the same card every time.
     */
    public static long seedFrom(String text) {
        String trimmed = text.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return trimmed.hashCode();
        }
    }
}
