package com.jifercraft.bingo.game;

import java.util.Locale;

/**
 * Where the card book may go. It moves freely inside its owner's inventory
 * (storage, hotbar, off-hand) and never leaves it: not dropped, not put in a
 * chest, the crafting grid, a bundle, an item frame or an armor stand. The
 * decisions are plain values, so the platform listeners only translate an
 * event into them and the rules are tested without a server.
 */
public final class CardRules {

    /** The kinds of click that matter for the card; every other click is OTHER. */
    public enum Click {
        DROP,
        MOVE_TO_OTHER_INVENTORY,
        SWAP_WITH_HOTBAR,
        SWAP_WITH_OFFHAND,
        OTHER;

        /** By name, because SWAP_OFFHAND does not exist before 1.16. */
        public static Click of(String clickTypeName) {
            switch (clickTypeName.toUpperCase(Locale.ROOT)) {
                case "DROP":
                case "CONTROL_DROP":
                    return DROP;
                case "SHIFT_LEFT":
                case "SHIFT_RIGHT":
                    return MOVE_TO_OTHER_INVENTORY;
                case "NUMBER_KEY":
                    return SWAP_WITH_HOTBAR;
                case "SWAP_OFFHAND":
                    return SWAP_WITH_OFFHAND;
                default:
                    return OTHER;
            }
        }
    }

    /** Where a click landed. The player's own 2x2 crafting grid counts as TOP. */
    public enum Place {
        OUTSIDE,
        TOP,
        BOTTOM
    }

    /** What sits in a slot or on the cursor, as far as the card cares. */
    public enum Content {
        NONE,
        CARD,
        BUNDLE,
        OTHER
    }

    private CardRules() {
    }

    /**
     * @param containerOpen a chest, furnace, anvil or similar is open, so moving to the
     *        other inventory would leave the one of the player
     * @param swapSource what the hotbar key or the off-hand key would swap in, when the click is one
     */
    public static boolean blocksClick(Click click, Place place, boolean containerOpen,
            Content cursor, Content clicked, Content swapSource) {
        if (cursor == Content.CARD) {
            // Outside the window is a drop; the top inventory is a container or the crafting grid.
            if (place != Place.BOTTOM) {
                return true;
            }
            if (clicked == Content.BUNDLE) {
                return true;
            }
        }
        if (clicked == Content.CARD) {
            // A stray card in a container stays there; the owner has theirs.
            if (place != Place.BOTTOM) {
                return true;
            }
            if (click == Click.DROP) {
                return true;
            }
            if (click == Click.MOVE_TO_OTHER_INVENTORY && containerOpen) {
                return true;
            }
            if (cursor == Content.BUNDLE) {
                return true;
            }
        }
        // The hotbar or off-hand key swaps with the hovered slot: with the card
        // on the other end and a container slot under the mouse, it would leave.
        return swapSource == Content.CARD && place == Place.TOP
                && (click == Click.SWAP_WITH_HOTBAR || click == Click.SWAP_WITH_OFFHAND);
    }

    /** Dragging the card across any slot of the top inventory would drop pieces of it there. */
    public static boolean blocksDrag(boolean cursorIsCard, boolean touchesTop) {
        return cursorIsCard && touchesTop;
    }

    /** Entities that take the item a player holds when right-clicked. */
    public static boolean blocksEntityUse(String entityTypeName) {
        switch (entityTypeName.toUpperCase(Locale.ROOT)) {
            case "ITEM_FRAME":
            case "GLOW_ITEM_FRAME":
            case "ARMOR_STAND":
            case "ALLAY":
                return true;
            default:
                return false;
        }
    }

    /** Bundles take any item by click and carry it away; by name, as they do not exist before 1.17. */
    public static boolean isBundle(String materialName) {
        return materialName.toUpperCase(Locale.ROOT).endsWith("BUNDLE");
    }
}
