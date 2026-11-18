package com.jifercraft.bingo.api.events;

import com.jifercraft.bingo.game.Round;
import com.jifercraft.bingo.game.Team;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class BingoRoundEndEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Round round;
    private final Team winner;
    private final long durationSeconds;

    public BingoRoundEndEvent(Round round, Team winner, long durationSeconds) {
        this.round = round;
        this.winner = winner;
        this.durationSeconds = durationSeconds;
    }

    public Round getRound() {
        return round;
    }

    public Team getWinner() {
        return winner;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
