package com.jifercraft.bingo.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ItemPoolTest {

    private static List<String> items(int count) {
        return IntStream.range(0, count).mapToObj(i -> "test:item" + i).toList();
    }

    private static List<String> items(int from, int to) {
        return IntStream.range(from, to).mapToObj(i -> "test:item" + i).toList();
    }

    @Test
    void acceptsAPoolThatFillsTheWholeCard() {
        ItemPool pool = new ItemPool(List.of(new ItemTier("easy", 25, items(25))));
        assertEquals(1, pool.tiers().size());
        assertEquals(25, pool.tiers().getFirst().cells());
    }

    @Test
    void rejectsAPoolThatDoesNotFillTheCard() {
        ItemTier shortByOne = new ItemTier("easy", 24, items(24));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ItemPool(List.of(shortByOne)));
        assertTrue(e.getMessage().contains("24"));
    }

    @Test
    void rejectsAnOversizedPool() {
        ItemTier tooBig = new ItemTier("easy", 26, items(26));
        assertThrows(IllegalArgumentException.class, () -> new ItemPool(List.of(tooBig)));
    }

    @Test
    void rejectsDuplicateItemsInOneTier() {
        ItemTier tier = new ItemTier("easy", 25, List.of("minecraft:diamond", "minecraft:diamond"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ItemPool(List.of(tier)));
        assertTrue(e.getMessage().contains("minecraft:diamond"));
    }

    @Test
    void rejectsATierWithTooFewItems() {
        ItemTier tier = new ItemTier("hard", 10, items(5));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ItemPool(List.of(tier)));
        assertTrue(e.getMessage().contains("hard"));
    }

    @Test
    void rejectsDuplicatedTierIds() {
        assertThrows(IllegalArgumentException.class, () -> new ItemPool(List.of(
                new ItemTier("easy", 15, items(15)),
                new ItemTier("easy", 10, items(10)))));
    }

    @Test
    void allowsOverlappingTiersWhileEveryoneCanStillFillTheirCount() {
        // Five items shared between the tiers: the first one takes them, the
        // second still has enough left.
        ItemPool pool = new ItemPool(List.of(
                new ItemTier("easy", 10, items(0, 10)),
                new ItemTier("hard", 15, items(5, 25))));
        assertEquals(2, pool.tiers().size());
    }

    @Test
    void rejectsATierStarvedByAnEarlierTier() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new ItemPool(List.of(
                new ItemTier("easy", 10, items(0, 10)),
                new ItemTier("hard", 15, items(5, 20)))));
        assertTrue(e.getMessage().contains("hard"));
    }
}
