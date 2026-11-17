package com.jifercraft.bingo.game;

import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.ClaimBoard;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public record FirstClaim(int cell, int teamId, long elapsedMs) {
    }

    private final long seed;
    private final BingoGameMode mode;
    private final BingoCard card;
    private final ClaimBoard board;
    private State state = State.COUNTDOWN;
    private long elapsedMs;
    private long lastResumeAt;
    private FirstClaim firstClaim;
    // 0 means nobody runs out. Deaths are kept per player, not per team
    // membership, so leaving and rejoining a team does not refill them.
    private final int lives;
    private final Map<UUID, Integer> deaths = new HashMap<>();

    Round(long seed, BingoCard card, ClaimBoard board) {
        this(seed, BingoGameMode.ITEMS, card, board);
    }

    Round(long seed, BingoGameMode mode, BingoCard card, ClaimBoard board) {
        this(seed, mode, card, board, 0);
    }

    Round(long seed, BingoGameMode mode, BingoCard card, ClaimBoard board, int lives) {
        this.seed = seed;
        this.mode = mode;
        this.card = card;
        this.board = board;
        this.lives = Math.max(0, lives);
    }

    Round(long seed, BingoGameMode mode, BingoCard card, ClaimBoard board, State state, long elapsedMs, FirstClaim firstClaim) {
        this(seed, mode, card, board, state, elapsedMs, firstClaim, 0, Map.of());
    }

    Round(long seed, BingoGameMode mode, BingoCard card, ClaimBoard board, State state, long elapsedMs,
            FirstClaim firstClaim, int lives, Map<UUID, Integer> deaths) {
        this(seed, mode, card, board, lives);
        this.state = state;
        this.elapsedMs = elapsedMs;
        this.lastResumeAt = state == State.RUNNING ? System.currentTimeMillis() : 0;
        this.firstClaim = firstClaim;
        this.deaths.putAll(deaths);
    }

    public long seed() {
        return seed;
    }

    public BingoGameMode mode() {
        return mode;
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

    public FirstClaim firstClaim() {
        return firstClaim;
    }

    public void recordFirstClaim(int cell, int teamId, long elapsedMs) {
        if (firstClaim == null) {
            firstClaim = new FirstClaim(cell, teamId, elapsedMs);
        }
    }

    /** Lives each player starts with; 0 for unlimited. */
    public int lives() {
        return lives;
    }

    /** Lives a player has left, or -1 when lives are unlimited. */
    public int livesLeft(UUID playerId) {
        if (lives == 0) {
            return -1;
        }
        return Math.max(0, lives - deaths.getOrDefault(playerId, 0));
    }

    public boolean isEliminated(UUID playerId) {
        return lives > 0 && deaths.getOrDefault(playerId, 0) >= lives;
    }

    /** Counts one death. True when it was the player's last life. */
    boolean recordDeath(UUID playerId) {
        if (lives == 0 || isEliminated(playerId)) {
            return false;
        }
        deaths.merge(playerId, 1, Integer::sum);
        return isEliminated(playerId);
    }

    /**
     * Ids of the teams with at least one member who still has lives, out of
     * the teams given. Teams without members are not counted as standing.
     */
    public List<Integer> teamsStanding(Map<Integer, ? extends Collection<UUID>> membersByTeam) {
        List<Integer> standing = new ArrayList<>();
        for (Map.Entry<Integer, ? extends Collection<UUID>> entry : membersByTeam.entrySet()) {
            for (UUID member : entry.getValue()) {
                if (!isEliminated(member)) {
                    standing.add(entry.getKey());
                    break;
                }
            }
        }
        return standing;
    }

    Map<UUID, Integer> deaths() {
        return Map.copyOf(deaths);
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
