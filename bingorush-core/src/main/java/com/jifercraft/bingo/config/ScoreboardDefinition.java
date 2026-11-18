package com.jifercraft.bingo.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One sidebar from config.yml: whether it shows, its title and its lines. */
public final class ScoreboardDefinition {

    private final boolean enabled;
    private final String title;
    private final List<String> lines;
    private final String teamFormat;

    public ScoreboardDefinition(boolean enabled, String title, List<String> lines, String teamFormat) {
        this.enabled = enabled;
        this.title = title;
        this.lines = lines != null ? Collections.unmodifiableList(new ArrayList<>(lines)) : Collections.emptyList();
        this.teamFormat = teamFormat;
    }

    public ScoreboardDefinition(boolean enabled, String title, List<String> lines) {
        this(enabled, title, lines, null);
    }

    public static ScoreboardDefinition defaultDisabled() {
        return new ScoreboardDefinition(false, null, Collections.<String>emptyList(), null);
    }

    public static ScoreboardDefinition defaultEnabled() {
        return new ScoreboardDefinition(true, null, Collections.<String>emptyList(), null);
    }

    public boolean enabled() {
        return enabled;
    }

    public String title() {
        return title;
    }

    public List<String> lines() {
        return lines;
    }

    public String teamFormat() {
        return teamFormat;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScoreboardDefinition that = (ScoreboardDefinition) o;
        return enabled == that.enabled
                && Objects.equals(title, that.title)
                && Objects.equals(lines, that.lines)
                && Objects.equals(teamFormat, that.teamFormat);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, title, lines, teamFormat);
    }

    @Override
    public String toString() {
        return "ScoreboardDefinition{" +
                "enabled=" + enabled +
                ", title='" + title + '\'' +
                ", lines=" + lines +
                ", teamFormat='" + teamFormat + '\'' +
                '}';
    }
}
