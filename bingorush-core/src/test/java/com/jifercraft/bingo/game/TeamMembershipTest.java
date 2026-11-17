package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamMembershipTest {

    private static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BEN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CAL = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void nameWinsWhenTeamsChangeOrder() {
        List<String> teams = List.of("Blue", "Red");
        Map<UUID, Integer> placement = TeamMembership.resolve(
                List.of(new TeamMembership.Member(ANA, "Red", 0)), teams);

        assertEquals(1, placement.get(ANA));
    }

    @Test
    void indexKeepsPlayersWhenATeamIsRenamed() {
        List<String> teams = List.of("Azul");
        Map<UUID, Integer> placement = TeamMembership.resolve(
                List.of(new TeamMembership.Member(ANA, "Blue", 0)), teams);

        assertEquals(0, placement.get(ANA));
    }

    @Test
    void nameMatchIgnoresCase() {
        List<String> teams = List.of("Red");
        Map<UUID, Integer> placement = TeamMembership.resolve(
                List.of(new TeamMembership.Member(ANA, "red", 4)), teams);

        assertEquals(0, placement.get(ANA));
    }

    @Test
    void membersOfARemovedTeamStayOut() {
        List<String> teams = List.of("Red", "Blue");
        Map<UUID, Integer> placement = TeamMembership.resolve(
                List.of(new TeamMembership.Member(ANA, "Red", 0),
                        new TeamMembership.Member(BEN, "Green", 2),
                        new TeamMembership.Member(CAL, "Blue", 5)),
                teams);

        assertEquals(0, placement.get(ANA));
        assertEquals(1, placement.get(CAL));
        assertFalse(placement.containsKey(BEN));
    }
}
