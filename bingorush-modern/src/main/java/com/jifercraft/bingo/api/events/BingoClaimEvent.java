package com.jifercraft.bingo.api.events;

import com.jifercraft.bingo.game.Team;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class BingoClaimEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Team team;
    private final int cell;
    private final String itemOrAdvancement;

    public BingoClaimEvent(Player player, Team team, int cell, String itemOrAdvancement) {
        this.player = player;
        this.team = team;
        this.cell = cell;
        this.itemOrAdvancement = itemOrAdvancement;
    }

    public Player getPlayer() {
        return player;
    }

    public Team getTeam() {
        return team;
    }

    public int getCell() {
        return cell;
    }

    public String getItemOrAdvancement() {
        return itemOrAdvancement;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
