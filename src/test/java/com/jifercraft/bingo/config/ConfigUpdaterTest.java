package com.jifercraft.bingo.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigUpdaterTest {

    @Test
    void preservesCommentsAndHeadersWhileInjectingMissingKeys() {
        List<String> jarLines = List.of(
                "# Header comment",
                "# Another comment line",
                "brand: \"<gradient:#FFD23F:#FF8A1F><b>BingoRush</b></gradient>\"",
                "",
                "# Palette section",
                "palette:",
                "  body: \"#FFF3D6\"",
                "  new-key: \"#123456\"",
                "",
                "# Long message test",
                "claim: \"<prefix><body>El equipo <team><body> consiguió <hl><item></hl> <muted>(<count>/<total>)</muted>\""
        );

        String userYamlStr = """
                # Custom user comment that SnakeYAML usually wipes
                brand: "<gradient:#FFFFFF:#000000><b>Custom</b></gradient>"
                palette:
                  body: "#AAAAAA"
                """;

        YamlConfiguration folderConfig = new YamlConfiguration();
        try {
            folderConfig.loadFromString(userYamlStr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Simulate folderConfig missing 'palette.new-key' and 'claim'
        folderConfig.set("palette.new-key", "#123456");
        folderConfig.set("claim", "<prefix><body>El equipo <team><body> consiguió <hl><item></hl> <muted>(<count>/<total>)</muted>");

        String updated = ConfigUpdater.buildUpdatedContent(jarLines, folderConfig);

        // Header comments from the template are preserved
        assertTrue(updated.contains("# Header comment"));
        assertTrue(updated.contains("# Another comment line"));
        assertTrue(updated.contains("# Palette section"));

        // User's custom value for existing keys is preserved
        assertTrue(updated.contains("brand: \"<gradient:#FFFFFF:#000000><b>Custom</b></gradient>\""));
        assertTrue(updated.contains("body: \"#AAAAAA\""));

        // Missing keys were injected with default values
        assertTrue(updated.contains("new-key: \"#123456\""));

        // Long string is enclosed in double quotes on a single line without multi-line splitting
        assertTrue(updated.contains("claim: \"<prefix><body>El equipo <team><body> consiguió <hl><item></hl> <muted>(<count>/<total>)</muted>\""));
        assertFalse(updated.contains("'\n"));
    }

    @Test
    void updatesAdvancementPoolPreservingComments() {
        List<String> jarLines = List.of(
                "# Game mode",
                "game-mode: items",
                "",
                "# Advancement card pool",
                "advancement-pool:",
                "  tiers:",
                "    easy:",
                "      cells: 10",
                "      advancements:",
                "        - minecraft:story/mine_stone",
                "        - minecraft:story/upgrade_tools"
        );

        String userYamlStr = """
                # Existing user config
                game-mode: advancements
                """;

        YamlConfiguration folderConfig = new YamlConfiguration();
        try {
            folderConfig.loadFromString(userYamlStr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        folderConfig.set("game-mode", "advancements");
        folderConfig.set("advancement-pool.tiers.easy.cells", 10);
        folderConfig.set("advancement-pool.tiers.easy.advancements",
                List.of("minecraft:story/mine_stone", "minecraft:story/upgrade_tools"));

        String updated = ConfigUpdater.buildUpdatedContent(jarLines, folderConfig);
        assertTrue(updated.contains("# Game mode"));
        assertTrue(updated.contains("game-mode: \"advancements\""));
        assertTrue(updated.contains("# Advancement card pool"));
        assertTrue(updated.contains("cells: 10"));
        assertTrue(updated.contains("- \"minecraft:story/mine_stone\""));
    }
}
