package com.jifercraft.bingo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Upgrades the files shipped with 0.2.0-pre.1 against the current templates. */
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

    private static String upgrade(String template, String old) throws Exception {
        List<String> templateLines = lines(template);
        YamlConfiguration folder = yaml(lines(old));
        ConfigUpdater.mergeMissing(yaml(templateLines), folder);
        return ConfigUpdater.buildUpdatedContent(templateLines, folder);
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
    void upgradeIsStable() throws Exception {
        for (String[] pair : new String[][]{
                {"/config.yml", "/config-0.2.0-pre.1.yml"},
                {"/messages.yml", "/messages-0.2.0-pre.1.yml"}}) {
            List<String> template = lines(pair[0]);
            String upgraded = upgrade(pair[0], pair[1]);

            // Running the upgrade again on its own output must change nothing.
            YamlConfiguration again = yaml(upgraded.lines().toList());
            assertFalse(ConfigUpdater.mergeMissing(yaml(template), again), pair[0]);
            assertEquals(upgraded, ConfigUpdater.buildUpdatedContent(template, again), pair[0]);
        }
    }

    @Test
    void messagesUpgradeKeepsEveryOldValue() throws Exception {
        YamlConfiguration old = yaml(lines("/messages-0.2.0-pre.1.yml"));
        YamlConfiguration template = yaml(lines("/messages.yml"));
        YamlConfiguration result = yaml(upgrade("/messages.yml", "/messages-0.2.0-pre.1.yml").lines().toList());

        for (String key : old.getKeys(true)) {
            if (old.isConfigurationSection(key) || !template.contains(key)) {
                continue;
            }
            assertEquals(old.get(key), result.get(key), key);
        }
        for (String key : template.getKeys(true)) {
            assertTrue(result.contains(key), "missing " + key);
            if (!old.contains(key) && !template.isConfigurationSection(key)) {
                assertEquals(template.get(key), result.get(key), key);
            }
        }
    }

    @Test
    void quoteSurvivesALoad() throws Exception {
        String tricky = "line one\nline \"two\"\tC:\\path";
        YamlConfiguration loaded = yaml(List.of("value: " + ConfigUpdater.quote(tricky)));
        assertEquals(tricky, loaded.getString("value"));
    }
}
