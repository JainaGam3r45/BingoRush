package com.jifercraft.bingo.history;

/**
 * Lightweight summary of a past match kept in memory for quick listing.
 */
public record MatchSummary(
        String id,
        long timestamp,
        String date,
        String duration,
        String winnerName,
        String winnerColor,
        String gameMode,
        boolean lockout,
        int totalTeams
) {
}
