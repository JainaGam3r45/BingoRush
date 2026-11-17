package com.jifercraft.bingo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ConfigSyncTest {

    /**
     * Intentional value differences between Legacy and Modern:
     * 1. round-chat.advancements: Spigot cannot alter advancement announcement
     *    lines per-player, so anything other than "vanilla" flips the global
     *    announceAdvancements gamerule. Off by default in Legacy.
     * 2. advancement-tab: 1.12+ legacy servers reload every online player's
     *    advancements for each tab entry, pausing a busy server at countdown.
     *    Off by default in Legacy.
     * 3. card.tiers.epic.items: Legacy includes beacon and
     *    enchanted_golden_apple in its pool tuning.
     */
    private static final Set<String> INTENTIONAL_DIFFERENCES;

    static {
        Set<String> diffs = new HashSet<>();
        diffs.add("round-chat.advancements");
        diffs.add("advancement-tab");
        diffs.add("card.tiers.epic.items");
        INTENTIONAL_DIFFERENCES = Collections.unmodifiableSet(diffs);
    }

    @Test
    void allConfigKeysMatchBetweenLegacyAndModern() throws Exception {
        Map<String, Object> legacyConfig = loadYaml(findConfigFile("bingorush-legacy/src/main/resources/config.yml"));
        Map<String, Object> modernConfig = loadYaml(findConfigFile("bingorush-modern/src/main/resources/config.yml"));

        Set<String> legacyKeys = new TreeSet<>();
        Map<String, Object> legacyValues = new HashMap<>();
        flatten("", legacyConfig, legacyKeys, legacyValues);

        Set<String> modernKeys = new TreeSet<>();
        Map<String, Object> modernValues = new HashMap<>();
        flatten("", modernConfig, modernKeys, modernValues);

        // All keys present in legacy must be in modern, and vice versa.
        assertEquals(legacyKeys, modernKeys, "Config keys in Legacy and Modern have drifted");

        // Verify that only the known intentional differences differ in values.
        for (String key : legacyKeys) {
            Object legacyVal = legacyValues.get(key);
            Object modernVal = modernValues.get(key);

            if (INTENTIONAL_DIFFERENCES.contains(key)) {
                // Verify specific expected values for documented differences.
                switch (key) {
                    case "round-chat.advancements":
                        assertEquals("vanilla", legacyVal);
                        assertEquals("custom", modernVal);
                        break;
                    case "advancement-tab":
                        assertEquals(Boolean.FALSE, legacyVal);
                        assertEquals(Boolean.TRUE, modernVal);
                        break;
                    case "card.tiers.epic.items":
                        assertTrue(legacyVal instanceof List);
                        assertTrue(modernVal instanceof List);
                        List<?> legacyList = (List<?>) legacyVal;
                        List<?> modernList = (List<?>) modernVal;
                        assertTrue(legacyList.contains("minecraft:beacon"));
                        assertTrue(legacyList.contains("minecraft:enchanted_golden_apple"));
                        break;
                    default:
                        break;
                }
            } else {
                assertEquals(legacyVal, modernVal, "Unexpected divergence at config key: " + key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> map, Set<String> keys, Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            keys.add(path);
            if (entry.getValue() instanceof Map) {
                flatten(path, (Map<String, Object>) entry.getValue(), keys, values);
            } else {
                values.put(path, entry.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(File file) throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(file)) {
            return (Map<String, Object>) yaml.load(in);
        }
    }

    private static File findConfigFile(String path) {
        File direct = new File(path);
        if (direct.exists()) {
            return direct;
        }
        File parent = new File("../" + path);
        if (parent.exists()) {
            return parent;
        }
        File current = new File(".").getAbsoluteFile();
        while (current != null) {
            File candidate = new File(current, path);
            if (candidate.exists()) {
                return candidate;
            }
            current = current.getParentFile();
        }
        throw new IllegalStateException("Could not find config file: " + path);
    }
}
