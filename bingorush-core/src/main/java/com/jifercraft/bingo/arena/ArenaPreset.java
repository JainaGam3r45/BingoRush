package com.jifercraft.bingo.arena;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A named set of arena settings from presets.yml, applied on top of the
 * defaults when an arena is created or later from the editor. Only the
 * settings a preset lists change; the name never does.
 */
public final class ArenaPreset {

    private final String name;
    private final Map<ArenaOption, Object> values;

    public ArenaPreset(String name, Map<ArenaOption, Object> values) {
        this.name = name;
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
    }

    public String name() {
        return name;
    }

    /** Parsed values, as {@link ArenaOption#parse} returns them. */
    public Map<ArenaOption, Object> values() {
        return values;
    }

    /**
     * Reads one preset. Broken entries are skipped and described in
     * problems, one line each, so a typo costs that setting and not the
     * whole file.
     */
    public static ArenaPreset parse(String name, Map<String, ?> raw, List<String> problems) {
        Map<ArenaOption, Object> values = new EnumMap<>(ArenaOption.class);
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            ArenaOption option = ArenaOption.byKey(entry.getKey());
            if (option == null) {
                problems.add("preset '" + name + "' has an unknown setting '" + entry.getKey() + "'");
                continue;
            }
            if (option == ArenaOption.NAME) {
                problems.add("preset '" + name + "' sets name, which presets cannot change");
                continue;
            }
            try {
                values.put(option, option.parse(String.valueOf(entry.getValue())));
            } catch (InvalidArenaValue e) {
                problems.add("preset '" + name + "': " + e.getMessage());
            }
        }
        return new ArenaPreset(name, values);
    }
}
