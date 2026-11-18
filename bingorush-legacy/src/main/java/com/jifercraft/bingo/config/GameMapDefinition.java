package com.jifercraft.bingo.config;

import com.jifercraft.bingo.game.BingoGameMode;

/** A configured game map with its display name, seed and settings. */
public final class GameMapDefinition {

    private final String id;
    private final String name;
    private final String seed;
    private final BingoGameMode mode;
    private final long timeLimit;

    public GameMapDefinition(String id, String name, String seed, BingoGameMode mode, long timeLimit) {
        this.id = id;
        this.name = name;
        this.seed = seed;
        this.mode = mode;
        this.timeLimit = timeLimit;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String seed() {
        return seed;
    }

    public BingoGameMode mode() {
        return mode;
    }

    public long timeLimit() {
        return timeLimit;
    }
}
