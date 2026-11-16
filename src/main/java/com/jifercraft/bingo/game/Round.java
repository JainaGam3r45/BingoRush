package com.jifercraft.bingo.game;

import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.ClaimBoard;

/**
 * One round in progress: the card every team plays, the claims so far, and
 * the clock. Pauses are subtracted from the clock by accumulating elapsed
 * time instead of trusting the wall clock since the start.
 */
public final class Round {

    public enum State {
        COUNTDOWN,
        RUNNING,
        PAUSED
    }

    private final long seed;
    private final BingoCard card;
    private final ClaimBoard board;
    private State state = State.COUNTDOWN;
    private long elapsedMs;
    private long lastResumeAt;

    Round(long seed, BingoCard card, ClaimBoard board) {
        this.seed = seed;
        this.card = card;
        this.board = board;
    }

    public long seed() {
        return seed;
    }

    public BingoCard card() {
        return card;
    }

    public ClaimBoard board() {
        return board;
    }

    public State state() {
        return state;
    }

    void beginRun() {
        state = State.RUNNING;
        lastResumeAt = System.currentTimeMillis();
    }

    void pause() {
        if (state != State.RUNNING) {
            return;
        }
        elapsedMs += System.currentTimeMillis() - lastResumeAt;
        lastResumeAt = 0;
        state = State.PAUSED;
    }

    void resume() {
        if (state != State.PAUSED) {
            return;
        }
        state = State.RUNNING;
        lastResumeAt = System.currentTimeMillis();
    }

    /** Time the round has spent running, pauses not counted. */
    public long elapsedMs() {
        long running = lastResumeAt == 0 ? 0 : System.currentTimeMillis() - lastResumeAt;
        return elapsedMs + running;
    }

    /** Milliseconds left on the clock, or Long.MAX_VALUE with no limit. */
    public long remainingMs(long limitSeconds) {
        if (limitSeconds <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, limitSeconds * 1000 - elapsedMs());
    }
}
