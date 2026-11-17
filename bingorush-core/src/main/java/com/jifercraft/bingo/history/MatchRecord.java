package com.jifercraft.bingo.history;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Full record of a completed match written to disk as JSON. The field names
 * are the JSON keys of every match file already on disk, so they stay put.
 */
public final class MatchRecord {

    private static final DateTimeFormatter ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    // Not final: Gson fills these in by reflection when it reads a file, and
    // newer JDKs warn about (and will refuse) writes to final fields.
    private String id;
    private long timestamp;
    private String date;
    private long seed;
    private String gameMode;
    private String winMode;
    private boolean lockout;
    private long durationMs;
    private String durationFormatted;
    private String reason;
    private TeamResult winner;
    private List<TeamResult> ranking;
    private FirstClaim firstClaim;
    private List<String> board;

    public MatchRecord(String id, long timestamp, String date, long seed, String gameMode, String winMode,
            boolean lockout, long durationMs, String durationFormatted, String reason, TeamResult winner,
            List<TeamResult> ranking, FirstClaim firstClaim, List<String> board) {
        this.id = id;
        this.timestamp = timestamp;
        this.date = date;
        this.seed = seed;
        this.gameMode = gameMode;
        this.winMode = winMode;
        this.lockout = lockout;
        this.durationMs = durationMs;
        this.durationFormatted = durationFormatted;
        this.reason = reason;
        this.winner = winner;
        this.ranking = ranking;
        this.firstClaim = firstClaim;
        this.board = board;
    }

    public static final class TeamResult {
        private int id;
        private String name;
        private String color;
        private int claims;
        private List<String> players;

        public TeamResult(int id, String name, String color, int claims, List<String> players) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.claims = claims;
            this.players = players;
        }

        public int id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String color() {
            return color;
        }

        public int claims() {
            return claims;
        }

        public List<String> players() {
            return players;
        }
    }

    public static final class FirstClaim {
        private int cell;
        private String item;
        private int teamId;
        private String teamName;
        private long elapsedMs;
        private String timeFormatted;

        public FirstClaim(int cell, String item, int teamId, String teamName, long elapsedMs, String timeFormatted) {
            this.cell = cell;
            this.item = item;
            this.teamId = teamId;
            this.teamName = teamName;
            this.elapsedMs = elapsedMs;
            this.timeFormatted = timeFormatted;
        }

        public int cell() {
            return cell;
        }

        public String item() {
            return item;
        }

        public int teamId() {
            return teamId;
        }

        public String teamName() {
            return teamName;
        }

        public long elapsedMs() {
            return elapsedMs;
        }

        public String timeFormatted() {
            return timeFormatted;
        }
    }

    public static String generateId(long timestamp) {
        return ID_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    public static String formatDate(long timestamp) {
        return DATE_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    public String id() {
        return id;
    }

    public long timestamp() {
        return timestamp;
    }

    public String date() {
        return date;
    }

    public long seed() {
        return seed;
    }

    public String gameMode() {
        return gameMode;
    }

    public String winMode() {
        return winMode;
    }

    public boolean lockout() {
        return lockout;
    }

    public long durationMs() {
        return durationMs;
    }

    public String durationFormatted() {
        return durationFormatted;
    }

    public String reason() {
        return reason;
    }

    public TeamResult winner() {
        return winner;
    }

    public List<TeamResult> ranking() {
        return ranking;
    }

    public FirstClaim firstClaim() {
        return firstClaim;
    }

    public List<String> board() {
        return board;
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
