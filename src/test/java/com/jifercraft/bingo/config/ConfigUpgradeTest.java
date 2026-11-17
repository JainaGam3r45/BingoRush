package com.jifercraft.bingo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Upgrades the config.yml shipped with 0.2.0-pre.1 against the current template. */
class ConfigUpgradeTest {

    private static List<String> lines(String resource) throws Exception {
        try (InputStream in = ConfigUpgradeTest.class.getResourceAsStream(resource)) {
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList();
        }
    }

    private static YamlConfiguration yaml(List<String> lines) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(String.join("\n", lines));
        return yaml;
    }

    @Test
    void upgradeKeepsTeamColors() throws Exception {
        List<String> template = lines("/config.yml");
        YamlConfiguration folder = yaml(lines("/config-0.2.0-pre.1.yml"));

        assertTrue(ConfigUpdater.mergeMissing(yaml(template), folder));
        String upgraded = ConfigUpdater.buildUpdatedContent(template, folder);
        YamlConfiguration result = yaml(upgraded.lines().toList());

        List<Map<?, ?>> teams = result.getMapList("teams");
        assertEquals(4, teams.size());
        assertEquals("Rojo", teams.get(0).get("name"));
        assertEquals("#FF5555", teams.get(0).get("color"));
        assertEquals("#FFFF55", teams.get(3).get("color"));
        assertTrue(result.contains("lockout"));
        assertTrue(result.contains("history.max-saved-matches"));
    }

    @Test
    void upgradeOutputLoadsAsAValidConfig() throws Exception {
        List<String> template = lines("/config.yml");
        YamlConfiguration folder = yaml(lines("/config-0.2.0-pre.1.yml"));
        ConfigUpdater.mergeMissing(yaml(template), folder);

        String upgraded = ConfigUpdater.buildUpdatedContent(template, folder);

        // Running the upgrade again on its own output must change nothing.
        YamlConfiguration again = yaml(upgraded.lines().toList());
        assertEquals(false, ConfigUpdater.mergeMissing(yaml(template), again));
        assertEquals(upgraded, ConfigUpdater.buildUpdatedContent(template, again));
    }
}
