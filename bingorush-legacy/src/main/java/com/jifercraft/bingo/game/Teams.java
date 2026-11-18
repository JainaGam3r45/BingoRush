package com.jifercraft.bingo.game;

import com.jifercraft.bingo.config.TeamDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Every team of the event, in creation order. Team ids match their index, so
 * a removed team renumbers the ones after it; that is only allowed while no
 * round is running, because the claim board knows teams by id. The size cap
 * is not stored here: it comes from the live config on every check.
 */
public final class Teams {

    /** Colors cycled when the plugin has to invent a team. */
    private static final NamedTextColor[] AUTO_COLORS = {
            NamedTextColor.RED, NamedTextColor.GOLD, NamedTextColor.YELLOW, NamedTextColor.GREEN,
            NamedTextColor.AQUA, NamedTextColor.BLUE, NamedTextColor.LIGHT_PURPLE, NamedTextColor.WHITE,
    };

    private final List<Team> teams = new ArrayList<>();

    /** Replaces the teams with the configured ones. Only call while no round is running. */
    public void reset(List<TeamDefinition> definitions) {
        teams.clear();
        for (TeamDefinition definition : definitions) {
            create(definition.name(), TextColor.color(definition.rgb()), definition.tag());
        }
    }

    /** Throws IllegalArgumentException when the name is taken. */
    public Team create(String name, TextColor color) {
        return create(name, color, null);
    }

    /** Throws IllegalArgumentException when the name is taken. A null tag means the first letter. */
    public Team create(String name, TextColor color, String tag) {
        if (byName(name) != null) {
            throw new IllegalArgumentException("The team '" + name + "' already exists");
        }
        Team team = new Team(teams.size(), name, color, tag);
        teams.add(team);
        return team;
    }

    public void remove(Team team) {
        teams.remove(team);
        for (int i = 0; i < teams.size(); i++) {
            teams.get(i).renumber(i);
        }
    }

    public Team byName(String name) {
        for (Team team : teams) {
            if (team.name().equalsIgnoreCase(name)) {
                return team;
            }
        }
        return null;
    }

    public Team byId(int id) {
        return id >= 0 && id < teams.size() ? teams.get(id) : null;
    }

    public Team of(UUID playerId) {
        for (Team team : teams) {
            if (team.hasMember(playerId)) {
                return team;
            }
        }
        return null;
    }

    public List<Team> all() {
        return Collections.unmodifiableList(new ArrayList<>(teams));
    }

    public int totalMembers() {
        int total = 0;
        for (Team team : teams) {
            total += team.size();
        }
        return total;
    }

    /** The team with room and the fewest players, or null when every team is full. */
    public Team withRoom(int maxSize) {
        Team best = null;
        for (Team team : teams) {
            if (team.size() >= maxSize) {
                continue;
            }
            if (best == null || team.size() < best.size()) {
                best = team;
            }
        }
        return best;
    }

    /**
     * For when the admin configured no teams: invents one with a name and a
     * color from the cycle, so a quick game needs no configuration at all.
     */
    public Team createAuto(String name, int number) {
        return create(name, AUTO_COLORS[Math.floorMod(number, AUTO_COLORS.length)]);
    }
}
