package com.jifercraft.bingo.config;

import com.jifercraft.bingo.game.BingoGameMode;
import org.bukkit.Location;

/** A configured game map with its display name, seed and settings. */
public record GameMapDefinition(String id, String name, String seed, BingoGameMode mode, long timeLimit, Location waitingLobby) {
}
