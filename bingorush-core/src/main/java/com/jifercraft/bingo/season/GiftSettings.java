package com.jifercraft.bingo.season;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration options for the seasonal lobby gifts hunt event.
 */
public final class GiftSettings {

    private final boolean enabled;
    private final boolean autoSpawn;
    private final int autoSpawnCount;
    private final double proximityRadius;
    private final Map<String, GiftType> types;
    private final List<GiftLocation> configuredLocations;
    private final List<String> completionCommands;

    public GiftSettings(boolean enabled, boolean autoSpawn, int autoSpawnCount,
            double proximityRadius, Map<String, GiftType> types,
            List<GiftLocation> configuredLocations, List<String> completionCommands) {
        this.enabled = enabled;
        this.autoSpawn = autoSpawn;
        this.autoSpawnCount = Math.max(1, autoSpawnCount);
        this.proximityRadius = Math.max(1.0, proximityRadius);
        this.types = types != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(types))
                : Collections.emptyMap();
        this.configuredLocations = configuredLocations != null
                ? Collections.unmodifiableList(new ArrayList<>(configuredLocations))
                : Collections.emptyList();
        this.completionCommands = completionCommands != null
                ? Collections.unmodifiableList(new ArrayList<>(completionCommands))
                : Collections.emptyList();
    }

    public static GiftSettings disabled() {
        return new GiftSettings(false, false, 5, 8.0, Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean autoSpawn() {
        return autoSpawn;
    }

    public int autoSpawnCount() {
        return autoSpawnCount;
    }

    public double proximityRadius() {
        return proximityRadius;
    }

    public Map<String, GiftType> types() {
        return types;
    }

    public List<GiftLocation> configuredLocations() {
        return configuredLocations;
    }

    public List<String> completionCommands() {
        return completionCommands;
    }
}
