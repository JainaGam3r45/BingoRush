package com.jifercraft.bingo.config;

/** A team as written in config.yml. The color is an RGB int; a null tag means the first letter. */
public record TeamDefinition(String name, int rgb, String tag) {}
