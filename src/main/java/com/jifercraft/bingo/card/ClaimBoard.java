package com.jifercraft.bingo.card;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Claim progress on one card. Progress belongs to each team. In lockout mode,
 * the first team to claim a cell locks it for everyone else.
 */
public final class ClaimBoard {

    private final WinMode mode;
    private final boolean lockout;
    // TreeMap keeps team order stable, which matters when two teams tie.
    private final Map<Integer, TeamProgress> teams = new TreeMap<>();
    private final int[] owners = new int[BingoCard.CELLS];
    private int totalClaimed;

    public ClaimBoard(WinMode mode) {
        this(mode, false);
    }

    public ClaimBoard(WinMode mode, boolean lockout) {
        this.mode = mode;
        this.lockout = lockout;
        Arrays.fill(owners, -1);
    }

    public boolean isLockout() {
        return lockout;
    }

    public WinMode winMode() {
        return mode;
    }

    public record ClaimRecord(int cell, int teamId, long timeMs) {
    }

    public List<ClaimRecord> allClaims() {
        List<ClaimRecord> list = new ArrayList<>();
        for (Map.Entry<Integer, TeamProgress> entry : teams.entrySet()) {
            int teamId = entry.getKey();
            TeamProgress progress = entry.getValue();
            for (int cell = 0; cell < BingoCard.CELLS; cell++) {
                if (progress.cells[cell]) {
                    list.add(new ClaimRecord(cell, teamId, progress.times[cell]));
                }
            }
        }
        list.sort(Comparator.comparingLong(ClaimRecord::timeMs));
        return list;
    }

    /** Records a claim. Returns false when the cell is unavailable or already claimed. */
    public boolean claim(int cell, int teamId, long timeMs) {
        if (cell < 0 || cell >= BingoCard.CELLS) {
            return false;
        }
        if (lockout && owners[cell] != -1) {
            return false;
        }
        TeamProgress progress = teams.computeIfAbsent(teamId, id -> new TeamProgress());
        if (!progress.claim(cell, timeMs, mode, lockout)) {
            return false;
        }
        if (owners[cell] == -1) {
            owners[cell] = teamId;
            totalClaimed++;
        }
        if (lockout && mode == WinMode.FULL_CARD && totalClaimed == BingoCard.CELLS) {
            leader().ifPresent(leadId -> {
                TeamProgress leadProgress = teams.get(leadId);
                if (leadProgress != null && leadProgress.completedAtMs == 0) {
                    leadProgress.completedAtMs = timeMs;
                }
            });
        }
        return true;
    }

    public int count(int teamId) {
        TeamProgress progress = teams.get(teamId);
        return progress == null ? 0 : progress.count;
    }

    public boolean isClaimed(int cell, int teamId) {
        TeamProgress progress = teams.get(teamId);
        return progress != null && progress.cells[cell];
    }

    /** Owner of the cell in lockout mode, or the first team that claimed it. */
    public OptionalInt owner(int cell) {
        if (cell < 0 || cell >= BingoCard.CELLS || owners[cell] == -1) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(owners[cell]);
    }

    /** When the team claimed a cell, or 0 if it never did. */
    public long claimTime(int cell, int teamId) {
        TeamProgress progress = teams.get(teamId);
        return progress == null ? 0 : progress.times[cell];
    }

    /** When the team last claimed any cell, or 0 if it never did. */
    public long lastClaimTime(int teamId) {
        TeamProgress progress = teams.get(teamId);
        return progress == null ? 0 : progress.lastClaimMs;
    }

    /** Every team holding one cell, for the card menu tooltip. */
    public List<Integer> claimers(int cell) {
        List<Integer> result = new ArrayList<>();
        for (Map.Entry<Integer, TeamProgress> entry : teams.entrySet()) {
            if (entry.getValue().cells[cell]) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** The first team to complete the win condition, if there is one. */
    public OptionalInt completedWinner() {
        long bestTime = Long.MAX_VALUE;
        int bestId = -1;
        for (Map.Entry<Integer, TeamProgress> entry : teams.entrySet()) {
            long at = entry.getValue().completedAtMs;
            if (at > 0 && at < bestTime) {
                bestTime = at;
                bestId = entry.getKey();
            }
        }
        return bestId < 0 ? OptionalInt.empty() : OptionalInt.of(bestId);
    }

    /**
     * The team with the most cells, for when the time runs out. A tie goes to
     * the team that made its last claim earlier.
     */
    public OptionalInt leader() {
        int bestId = -1;
        TeamProgress best = null;
        for (Map.Entry<Integer, TeamProgress> entry : teams.entrySet()) {
            TeamProgress progress = entry.getValue();
            if (progress.count == 0) {
                continue;
            }
            boolean better = best == null
                    || progress.count > best.count
                    || (progress.count == best.count && progress.lastClaimMs < best.lastClaimMs);
            if (better) {
                bestId = entry.getKey();
                best = progress;
            }
        }
        return best == null ? OptionalInt.empty() : OptionalInt.of(bestId);
    }

    private static boolean hasLine(boolean[] cells) {
        for (int row = 0; row < BingoCard.ROWS; row++) {
            boolean full = true;
            for (int column = 0; column < BingoCard.COLUMNS; column++) {
                if (!cells[row * BingoCard.COLUMNS + column]) {
                    full = false;
                    break;
                }
            }
            if (full) {
                return true;
            }
        }
        for (int column = 0; column < BingoCard.COLUMNS; column++) {
            boolean full = true;
            for (int row = 0; row < BingoCard.ROWS; row++) {
                if (!cells[row * BingoCard.COLUMNS + column]) {
                    full = false;
                    break;
                }
            }
            if (full) {
                return true;
            }
        }
        boolean mainDiagonal = true;
        boolean antiDiagonal = true;
        for (int i = 0; i < BingoCard.ROWS; i++) {
            if (!cells[i * BingoCard.COLUMNS + i]) {
                mainDiagonal = false;
            }
            if (!cells[i * BingoCard.COLUMNS + (BingoCard.COLUMNS - 1 - i)]) {
                antiDiagonal = false;
            }
        }
        return mainDiagonal || antiDiagonal;
    }

    private static final class TeamProgress {

        private final boolean[] cells = new boolean[BingoCard.CELLS];
        private final long[] times = new long[BingoCard.CELLS];
        private int count;
        private long lastClaimMs;
        // 0 until the team completes the win condition.
        private long completedAtMs;

        boolean claim(int cell, long timeMs, WinMode mode, boolean lockout) {
            if (cells[cell]) {
                return false;
            }
            cells[cell] = true;
            times[cell] = timeMs;
            lastClaimMs = timeMs;
            count++;
            if (completedAtMs == 0 && isComplete(mode, lockout)) {
                completedAtMs = timeMs;
            }
            return true;
        }

        private boolean isComplete(WinMode mode, boolean lockout) {
            return switch (mode) {
                case FULL_CARD -> lockout ? count >= (BingoCard.CELLS / 2 + 1) : count == BingoCard.CELLS;
                case LINE -> hasLine(cells);
            };
        }
    }
}
