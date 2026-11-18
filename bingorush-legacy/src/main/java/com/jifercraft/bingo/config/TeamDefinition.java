package com.jifercraft.bingo.config;

/** A team as written in config.yml. The color is an RGB int; a null tag means the first letter. */
public final class TeamDefinition {
    private final String name;
    private final int rgb;
    private final String tag;

    public TeamDefinition(String name, int rgb, String tag) {
        this.name = name;
        this.rgb = rgb;
        this.tag = tag;
    }

    public String name() {
        return name;
    }

    public int rgb() {
        return rgb;
    }

    public String tag() {
        return tag;
    }
}
