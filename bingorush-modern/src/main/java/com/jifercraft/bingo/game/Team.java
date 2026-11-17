package com.jifercraft.bingo.game;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.TextColor;

/**
 * One colored team. Membership is tracked by UUID, so a player who
 * disconnects and comes back lands in the same team with the same progress.
 */
public final class Team {

    private int id;
    private final String name;
    private final TextColor color;
    private final Set<UUID> members = new LinkedHashSet<>();

    Team(int id, String name, TextColor color) {
        this.id = id;
        this.name = name;
        this.color = color;
    }

    /** The id the claim board knows this team by. */
    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public TextColor color() {
        return color;
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public int size() {
        return members.size();
    }

    public boolean hasMember(UUID playerId) {
        return members.contains(playerId);
    }

    void add(UUID playerId) {
        members.add(playerId);
    }

    void remove(UUID playerId) {
        members.remove(playerId);
    }

    /** Only for when another team is removed and the ids shift. */
    void renumber(int newId) {
        id = newId;
    }
}
