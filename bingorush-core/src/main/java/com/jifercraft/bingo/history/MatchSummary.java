package com.jifercraft.bingo.history;

/**
 * Lightweight summary of a past match kept in memory for quick listing.
 */
public final class MatchSummary {

    private final String id;
    private final long timestamp;
    private final String date;
    private final String duration;
    private final String winnerName;
    private final String winnerColor;
    private final String gameMode;
    private final boolean lockout;
    private final int totalTeams;

    public MatchSummary(String id, long timestamp, String date, String duration, String winnerName,
            String winnerColor, String gameMode, boolean lockout, int totalTeams) {
        this.id = id;
        this.timestamp = timestamp;
        this.date = date;
        this.duration = duration;
        this.winnerName = winnerName;
        this.winnerColor = winnerColor;
        this.gameMode = gameMode;
        this.lockout = lockout;
        this.totalTeams = totalTeams;
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

    public String duration() {
        return duration;
    }

    public String winnerName() {
        return winnerName;
    }

    public String winnerColor() {
        return winnerColor;
    }

    public String gameMode() {
        return gameMode;
    }

    public boolean lockout() {
        return lockout;
    }

    public int totalTeams() {
        return totalTeams;
    }
}
