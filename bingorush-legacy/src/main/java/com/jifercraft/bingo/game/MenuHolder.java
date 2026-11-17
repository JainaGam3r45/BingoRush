package com.jifercraft.bingo.game;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Owner of every menu inventory the plugin opens. Old versions have no
 * persistent data on items, so the holder is how a click is recognized as
 * ours, and it remembers what each slot does.
 */
final class MenuHolder implements InventoryHolder {

    enum Kind {
        CARD,
        TEAMS,
        SETUP
    }

    private final Kind kind;
    private final Map<Integer, String> actions = new HashMap<>();
    private final Map<Integer, Integer> teamIds = new HashMap<>();
    private Inventory inventory;

    MenuHolder(Kind kind) {
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void clearActions() {
        actions.clear();
        teamIds.clear();
    }

    void action(int slot, String action) {
        actions.put(slot, action);
    }

    void team(int slot, int teamId) {
        actions.put(slot, "team");
        teamIds.put(slot, teamId);
    }

    String actionAt(int slot) {
        return actions.get(slot);
    }

    Integer teamAt(int slot) {
        return teamIds.get(slot);
    }

    static MenuHolder of(Inventory inventory, Kind kind) {
        if (inventory != null && inventory.getHolder() instanceof MenuHolder) {
            MenuHolder holder = (MenuHolder) inventory.getHolder();
            if (kind == null || holder.kind == kind) {
                return holder;
            }
        }
        return null;
    }
}
