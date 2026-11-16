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
}
