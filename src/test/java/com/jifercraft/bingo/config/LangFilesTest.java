package com.jifercraft.bingo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The bundled translations have to stay in step with each other. */
class LangFilesTest {

    // Formatting tags, not values the code fills in.
    private static final Set<String> STYLE_TAGS = Set.of(
            "body", "hl", "ok", "err", "errval", "muted", "brand", "prefix",
            "b", "i", "st", "br", "newline", "white", "color", "gradient", "click", "!italic");
    private static final Pattern TAG = Pattern.compile("<(!?[a-z_]+)[^>]*>");

    private static YamlConfiguration load(String resource) throws Exception {
        try (InputStream in = LangFilesTest.class.getResourceAsStream(resource)) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = TAG.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!STYLE_TAGS.contains(name) && !name.startsWith("color")) {
                found.add(name);
            }
        }
        return found;
    }

    @Test
    void englishAndSpanishHaveTheSameKeys() throws Exception {
        YamlConfiguration english = load("/lang/messages_en.yml");
        YamlConfiguration spanish = load("/lang/messages_es.yml");
        assertEquals(new TreeSet<>(english.getKeys(true)), new TreeSet<>(spanish.getKeys(true)));
    }

    @Test
    void everyKeyKeepsItsPlaceholders() throws Exception {
        YamlConfiguration english = load("/lang/messages_en.yml");
        YamlConfiguration spanish = load("/lang/messages_es.yml");
        for (String key : english.getKeys(true)) {
            // Help lines name their arguments in each language on purpose.
            if (english.isConfigurationSection(key) || key.startsWith("help.")) {
                continue;
            }
            assertEquals(placeholders(english.getString(key)), placeholders(spanish.getString(key)), key);
        }
    }

    @Test
    void englishIsTheDefaultInConfig() throws Exception {
        assertEquals("en", load("/config.yml").getString("language"));
        assertTrue(load("/lang/messages_en.yml").contains("prefix"));
    }
}
