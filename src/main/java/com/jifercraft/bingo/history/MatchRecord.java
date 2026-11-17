package com.jifercraft.bingo.history;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Full record of a completed match written to disk as JSON.
 */
public record MatchRecord(
        String id,
        long timestamp,
        String date,
        long seed,
        String gameMode,
        String winMode,
        boolean lockout,
        long durationMs,
        String durationFormatted,
        String reason,
        TeamResult winner,
        List<TeamResult> ranking,
        FirstClaim firstClaim,
        List<String> board
) {

    private static final DateTimeFormatter ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public record TeamResult(
            int id,
            String name,
            String color,
            int claims,
            List<String> players
    ) {
    }

    public record FirstClaim(
            int cell,
            String item,
            int teamId,
            String teamName,
            long elapsedMs,
            String timeFormatted
    ) {
    }

    public static String generateId(long timestamp) {
        return ID_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    public static String formatDate(long timestamp) {
        return DATE_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    public MatchSummary toSummary() {
        return new MatchSummary(
                id,
                timestamp,
                date,
                durationFormatted,
                winner != null ? winner.name() : "-",
                winner != null ? winner.color() : "#FFFFFF",
                gameMode,
                lockout,
                ranking != null ? ranking.size() : 0
        );
    }
}
