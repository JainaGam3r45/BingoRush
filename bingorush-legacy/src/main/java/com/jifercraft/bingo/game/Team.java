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
    private TextColor color;
    private String tag;
    private final Set<UUID> members = new LinkedHashSet<>();

    Team(int id, String name, TextColor color, String tag) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.tag = tag == null || tag.trim().isEmpty() ? TeamLabel.initial(name) : tag.trim();
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

    /** The short mark in front of member names, "R" for Red unless the config says otherwise. */
    public String tag() {
        return tag;
    }

    void recolor(TextColor newColor) {
        color = newColor;
    }

    void retag(String newTag) {
        tag = newTag == null || newTag.trim().isEmpty() ? TeamLabel.initial(name) : newTag.trim();
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
