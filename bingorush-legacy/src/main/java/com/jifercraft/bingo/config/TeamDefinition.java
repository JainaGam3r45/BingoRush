package com.jifercraft.bingo.config;

/** A team as written in config.yml. The color is an RGB int. */
public final class TeamDefinition {
    private final String name;
    private final int rgb;

    public TeamDefinition(String name, int rgb) {
        this.name = name;
        this.rgb = rgb;
    }

    public String name() {
        return name;
    }

    public int rgb() {
        return rgb;
    }
}
