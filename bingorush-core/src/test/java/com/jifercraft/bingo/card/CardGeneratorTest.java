package com.jifercraft.bingo.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CardGeneratorTest {

    private static List<String> items(int count) {
        return IntStream.range(0, count).mapToObj(i -> "test:item" + i).toList();
    }

    private static List<String> items(int from, int to) {
        return IntStream.range(from, to).mapToObj(i -> "test:item" + i).toList();
    }

    @Test
    void generatesACardWith25Items() {
        BingoCard card = new CardGenerator(new ItemPool(List.of(new ItemTier("easy", 25, items(30))))).generate(7);
        assertEquals(25, card.items().size());
        assertEquals(7, card.seed());
    }

    @Test
    void theSameSeedGivesTheSameCard() {
        ItemPool pool = new ItemPool(List.of(new ItemTier("easy", 25, items(30))));
        BingoCard first = new CardGenerator(pool).generate(123456789L);
        BingoCard second = new CardGenerator(pool).generate(123456789L);
        assertEquals(first.items(), second.items());
    }

    @Test
    void aDifferentSeedGivesADifferentCard() {
        ItemPool pool = new ItemPool(List.of(new ItemTier("easy", 25, items(30))));
        BingoCard first = new CardGenerator(pool).generate(1);
        BingoCard second = new CardGenerator(pool).generate(2);
        assertNotEquals(first.items(), second.items());
    }

    @Test
    void everyTierContributesItsShareOfCells() {
        ItemPool pool = new ItemPool(List.of(
                new ItemTier("easy", 10, items(0, 10)),
                new ItemTier("medium", 8, items(10, 18)),
                new ItemTier("hard", 5, items(18, 23)),
                new ItemTier("epic", 2, items(23, 25))));
        BingoCard card = new CardGenerator(pool).generate(42);
        Map<String, Long> perTier = card.items().stream()
                .collect(Collectors.groupingBy(item -> tierOf(item), Collectors.counting()));
        assertEquals(10, perTier.get("easy"));
        assertEquals(8, perTier.get("medium"));
        assertEquals(5, perTier.get("hard"));
        assertEquals(2, perTier.get("epic"));
    }

    private static String tierOf(String item) {
        int n = Integer.parseInt(item.replace("test:item", ""));
        if (n < 10) return "easy";
        if (n < 18) return "medium";
        if (n < 23) return "hard";
        return "epic";
    }

    @Test
    void theCardNeverRepeatsAnItem() {
        ItemPool pool = new ItemPool(List.of(
                new ItemTier("easy", 10, items(0, 10)),
                new ItemTier("hard", 15, items(5, 25))));
        BingoCard card = new CardGenerator(pool).generate(99);
        assertEquals(25, card.items().stream().distinct().count());
    }

    @Test
    void theCardHoldsExactlyThePoolItems() {
        List<String> pool = IntStream.range(0, 25).mapToObj(i -> "minecraft:pool_item" + i).toList();
        BingoCard card = new CardGenerator(new ItemPool(List.of(new ItemTier("easy", 25, pool)))).generate(3);
        assertEquals(java.util.Set.copyOf(pool), java.util.Set.copyOf(card.items()));
    }

    @Test
    void seedsAcceptNumbersAndText() {
        assertEquals(42, CardGenerator.seedFrom("42"));
        assertEquals(42, CardGenerator.seedFrom(" 42 "));
        assertEquals(CardGenerator.seedFrom("evento-verano"), CardGenerator.seedFrom("evento-verano"));
        assertNotEquals(CardGenerator.seedFrom("evento-verano"), CardGenerator.seedFrom("evento-invierno"));
    }

    @Test
    void generatesAdvancementCardFromAdvancementPool() {
        ItemPool advancementPool = new ItemPool(List.of(
                new ItemTier("easy", 10, List.of(
                        "minecraft:story/mine_stone", "minecraft:story/upgrade_tools",
                        "minecraft:story/smelt_iron", "minecraft:husbandry/breed_an_animal",
                        "minecraft:story/obtain_armor", "minecraft:story/deflect_arrow",
                        "minecraft:husbandry/plant_seed", "minecraft:husbandry/tame_an_animal",
                        "minecraft:adventure/sleep_in_bed", "minecraft:adventure/shoot_arrow")),
                new ItemTier("medium", 8, List.of(
                        "minecraft:nether/find_fortress", "minecraft:story/lava_bucket",
                        "minecraft:story/form_obsidian", "minecraft:story/mine_diamond",
                        "minecraft:story/enchant_item", "minecraft:story/enter_the_nether",
                        "minecraft:nether/obtain_blaze_rod", "minecraft:nether/distract_piglin")),
                new ItemTier("hard", 5, List.of(
                        "minecraft:nether/brew_potion", "minecraft:story/cure_zombie_villager",
                        "minecraft:adventure/sniper_duel", "minecraft:nether/return_to_sender",
                        "minecraft:adventure/hero_of_the_village")),
                new ItemTier("epic", 2, List.of(
                        "minecraft:end/kill_dragon", "minecraft:nether/all_potions"))));

        BingoCard card = new CardGenerator(advancementPool).generate(12345L);
        assertEquals(25, card.items().size());
        assertEquals(25, card.items().stream().distinct().count());
        assertTrue(card.items().contains("minecraft:end/kill_dragon"));
        assertTrue(card.items().contains("minecraft:nether/all_potions"));
    }
}

