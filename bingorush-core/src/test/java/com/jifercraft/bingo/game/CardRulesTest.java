package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jifercraft.bingo.game.CardRules.Click;
import com.jifercraft.bingo.game.CardRules.Content;
import com.jifercraft.bingo.game.CardRules.Place;
import org.junit.jupiter.api.Test;

class CardRulesTest {

    private static boolean blocks(Click click, Place place, boolean container,
            Content cursor, Content clicked, Content swap) {
        return CardRules.blocksClick(click, place, container, cursor, clicked, swap);
    }

    // ---- what must work: moving around the own inventory ----

    @Test
    void theCardMovesFreelyInTheOwnInventory() {
        // pick it up, put it down, swap places, with and without a chest open
        assertFalse(blocks(Click.OTHER, Place.BOTTOM, false, Content.NONE, Content.CARD, Content.NONE));
        assertFalse(blocks(Click.OTHER, Place.BOTTOM, false, Content.CARD, Content.NONE, Content.NONE));
        assertFalse(blocks(Click.OTHER, Place.BOTTOM, true, Content.CARD, Content.OTHER, Content.NONE));
        assertFalse(blocks(Click.OTHER, Place.BOTTOM, true, Content.OTHER, Content.CARD, Content.NONE));
    }

    @Test
    void hotbarAndOffHandSwapsInsideTheInventoryAreFine() {
        assertFalse(blocks(Click.SWAP_WITH_HOTBAR, Place.BOTTOM, false, Content.NONE, Content.OTHER, Content.CARD));
        assertFalse(blocks(Click.SWAP_WITH_HOTBAR, Place.BOTTOM, true, Content.NONE, Content.CARD, Content.OTHER));
        assertFalse(blocks(Click.SWAP_WITH_OFFHAND, Place.BOTTOM, false, Content.NONE, Content.OTHER, Content.CARD));
        assertFalse(blocks(Click.SWAP_WITH_OFFHAND, Place.BOTTOM, true, Content.NONE, Content.CARD, Content.OTHER));
    }

    @Test
    void shiftClickBetweenHotbarAndStorageIsFineWhenNothingIsOpen() {
        assertFalse(blocks(Click.MOVE_TO_OTHER_INVENTORY, Place.BOTTOM, false, Content.NONE, Content.CARD, Content.NONE));
    }

    // ---- what must not: leaving the player ----

    @Test
    void theCardCannotBeDropped() {
        assertTrue(blocks(Click.DROP, Place.BOTTOM, false, Content.NONE, Content.CARD, Content.NONE));
        // held on the cursor and clicked outside the window
        assertTrue(blocks(Click.OTHER, Place.OUTSIDE, false, Content.CARD, Content.NONE, Content.NONE));
    }

    @Test
    void theCardCannotGoIntoAChest() {
        assertTrue(blocks(Click.OTHER, Place.TOP, true, Content.CARD, Content.NONE, Content.NONE));
        assertTrue(blocks(Click.OTHER, Place.TOP, true, Content.CARD, Content.OTHER, Content.NONE));
        assertTrue(blocks(Click.MOVE_TO_OTHER_INVENTORY, Place.BOTTOM, true, Content.NONE, Content.CARD, Content.NONE));
    }

    @Test
    void theHotbarKeyCannotCarryItIntoAChest() {
        assertTrue(blocks(Click.SWAP_WITH_HOTBAR, Place.TOP, true, Content.NONE, Content.OTHER, Content.CARD));
        assertTrue(blocks(Click.SWAP_WITH_HOTBAR, Place.TOP, true, Content.NONE, Content.NONE, Content.CARD));
    }

    @Test
    void theOffHandKeyCannotCarryItIntoAChest() {
        assertTrue(blocks(Click.SWAP_WITH_OFFHAND, Place.TOP, true, Content.NONE, Content.OTHER, Content.CARD));
    }

    @Test
    void theCraftingGridIsNotAPlaceToLeaveIt() {
        assertTrue(blocks(Click.OTHER, Place.TOP, false, Content.CARD, Content.NONE, Content.NONE));
        assertTrue(blocks(Click.SWAP_WITH_HOTBAR, Place.TOP, false, Content.NONE, Content.NONE, Content.CARD));
    }

    @Test
    void aStrayCardInAContainerCannotBePickedUp() {
        assertTrue(blocks(Click.OTHER, Place.TOP, true, Content.NONE, Content.CARD, Content.NONE));
        assertTrue(blocks(Click.MOVE_TO_OTHER_INVENTORY, Place.TOP, true, Content.NONE, Content.CARD, Content.NONE));
    }

    @Test
    void aBundleCannotSwallowIt() {
        assertTrue(blocks(Click.OTHER, Place.BOTTOM, false, Content.CARD, Content.BUNDLE, Content.NONE));
        assertTrue(blocks(Click.OTHER, Place.BOTTOM, false, Content.BUNDLE, Content.CARD, Content.NONE));
    }

    // ---- everything that is not the card stays untouched ----

    @Test
    void otherItemsAreNeverBlocked() {
        for (Click click : Click.values()) {
            for (Place place : Place.values()) {
                assertFalse(blocks(click, place, true, Content.OTHER, Content.OTHER, Content.OTHER));
                assertFalse(blocks(click, place, true, Content.BUNDLE, Content.OTHER, Content.NONE));
                assertFalse(blocks(click, place, false, Content.NONE, Content.NONE, Content.NONE));
            }
        }
    }

    @Test
    void dragAcrossAContainerIsBlockedOnlyForTheCard() {
        assertTrue(CardRules.blocksDrag(true, true));
        assertFalse(CardRules.blocksDrag(true, false));
        assertFalse(CardRules.blocksDrag(false, true));
    }

    @Test
    void entitiesThatTakeTheHeldItemAreBlocked() {
        assertTrue(CardRules.blocksEntityUse("ITEM_FRAME"));
        assertTrue(CardRules.blocksEntityUse("glow_item_frame"));
        assertTrue(CardRules.blocksEntityUse("ARMOR_STAND"));
        assertTrue(CardRules.blocksEntityUse("ALLAY"));
        assertFalse(CardRules.blocksEntityUse("VILLAGER"));
        assertFalse(CardRules.blocksEntityUse("COW"));
    }

    @Test
    void clickKindsAreMatchedByName() {
        assertEquals(Click.DROP, Click.of("DROP"));
        assertEquals(Click.DROP, Click.of("CONTROL_DROP"));
        assertEquals(Click.MOVE_TO_OTHER_INVENTORY, Click.of("SHIFT_LEFT"));
        assertEquals(Click.MOVE_TO_OTHER_INVENTORY, Click.of("SHIFT_RIGHT"));
        assertEquals(Click.SWAP_WITH_HOTBAR, Click.of("NUMBER_KEY"));
        assertEquals(Click.SWAP_WITH_OFFHAND, Click.of("SWAP_OFFHAND"));
        assertEquals(Click.OTHER, Click.of("LEFT"));
        assertEquals(Click.OTHER, Click.of("DOUBLE_CLICK"));
    }

    @Test
    void bundlesAreRecognisedByMaterialName() {
        assertTrue(CardRules.isBundle("BUNDLE"));
        assertTrue(CardRules.isBundle("RED_BUNDLE"));
        assertFalse(CardRules.isBundle("BOOK"));
        assertFalse(CardRules.isBundle("SHULKER_BOX"));
    }
}
