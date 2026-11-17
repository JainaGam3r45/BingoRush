package com.jifercraft.bingo.game;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Puts saved team members back into the teams that exist now. The name wins
 * over the id, so a reordered config does not move anyone; the id catches a
 * renamed team. A member whose team is gone is left out.
 */
public final class TeamMembership {

    public static final class Member {
        private final UUID playerId;
        private final String teamName;
        private final int teamId;

        public Member(UUID playerId, String teamName, int teamId) {
            this.playerId = playerId;
            this.teamName = teamName;
            this.teamId = teamId;
        }

        public UUID playerId() {
            return playerId;
        }

        public String teamName() {
            return teamName;
        }

        public int teamId() {
            return teamId;
        }
    }

    private TeamMembership() {
    }

    /**
     * Returns the current team id for each saved member, in the saved order.
     * Members with no team left are not in the result.
     */
    public static Map<UUID, Integer> resolve(List<Member> members, List<String> teamNames) {
        Map<UUID, Integer> placement = new LinkedHashMap<>();
        for (Member member : members) {
            int byName = indexOf(teamNames, member.teamName());
            if (byName >= 0) {
                placement.put(member.playerId(), byName);
            } else if (member.teamId() >= 0 && member.teamId() < teamNames.size()) {
                placement.put(member.playerId(), member.teamId());
            }
        }
        return placement;
    }

    private static int indexOf(List<String> names, String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
