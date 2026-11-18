package com.jifercraft.bingo.api;

import com.jifercraft.bingo.game.Round;
import com.jifercraft.bingo.game.Team;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;

/**
 * Public API for querying game state, active rounds, teams and lobby location.
 */
public interface BingoRushAPI {

    /** Returns true if a bingo round is currently in progress. */
    boolean isRoundRunning();

    /** Returns the active round if running, or empty if in the lobby. */
    Optional<Round> getActiveRound();

    /** Returns the team of the given player UUID, or empty if none. */
    Optional<Team> getPlayerTeam(UUID playerId);

    /** Returns true if the player is currently participating in the round. */
    boolean isPlayerInGame(UUID playerId);

    /** Returns all configured teams. */
    Collection<Team> getTeams();

    /** Returns the current lobby location where players gather. */
    Location getLobbyLocation();
}
