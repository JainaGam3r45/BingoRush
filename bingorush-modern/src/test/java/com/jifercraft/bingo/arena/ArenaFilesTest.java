package com.jifercraft.bingo.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jifercraft.bingo.card.WinMode;
import com.jifercraft.bingo.config.GameMapDefinition;
import com.jifercraft.bingo.game.BingoGameMode;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaFilesTest {

    private static final Logger LOGGER = Logger.getAnonymousLogger();

    // config.yml as 0.2.0-pre.4 wrote it: round settings at the top and the
    // arenas, called maps, in the same file.
    private static final String PRE_4_CONFIG = """
            language: es
            win-mode: line
            lockout: true
            time-limit: 900
            countdown: 5
            team-size: 3
            lives: 2
            maps:
              partida-1:
                name: "Partida 1"
                seed: "random"
                mode: items
                time-limit: 1800
              Final Round:
                name: "Final"
                seed: "verano"
                mode: advancements
            """;

    @Test
    void mapsMoveToArenasWithTheRoundSettingsOfTheSameFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.yml"), PRE_4_CONFIG);

        assertEquals(2, ArenaManager.migrateMaps(dir.toFile(), LOGGER));

        YamlConfiguration arenas = YamlConfiguration.loadConfiguration(dir.resolve("arenas.yml").toFile());
        Map<String, GameMapDefinition> read = ArenaManager.read(arenas, null, 4, LOGGER);
        GameMapDefinition first = read.get("partida-1");
        assertEquals("Partida 1", first.name());
        assertEquals(1800, first.timeLimit());
        assertEquals(WinMode.LINE, first.winMode());
        assertTrue(first.lockout());
        assertEquals(3, first.teamSize());
        assertEquals(2, first.lives());
        assertEquals(5, first.countdown());
        GameMapDefinition last = read.get("final-round");
        assertEquals(BingoGameMode.ADVANCEMENTS, last.mode());
        assertEquals("verano", last.seed());
        assertEquals(900, last.timeLimit());
    }

    @Test
    void anExistingArenasFileIsNeverReplaced(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.yml"), PRE_4_CONFIG);
        Files.writeString(dir.resolve("arenas.yml"), "arenas: {}\n");

        assertEquals(0, ArenaManager.migrateMaps(dir.toFile(), LOGGER));
        assertEquals("arenas: {}\n", Files.readString(dir.resolve("arenas.yml")));
    }

    @Test
    void configsWithoutMapsGetNoArenasFile(@TempDir Path dir) throws Exception {
        // 0.1.x had no maps: rounds stay in the main world, as before.
        Files.writeString(dir.resolve("config.yml"), "win-mode: full\ntime-limit: 1800\n");

        assertEquals(0, ArenaManager.migrateMaps(dir.toFile(), LOGGER));
        assertFalse(new File(dir.toFile(), "arenas.yml").exists());
    }

    @Test
    void badValuesKeepTheDefaultAndBadIdsAreSkipped() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                arenas:
                  Main:
                    team-size: 99
                    lives: 1
                    border: 2000
                  "///":
                    lives: 3
                  main:
                    lives: 5
                """);

        Map<String, GameMapDefinition> read = ArenaManager.read(yaml, null, 4, LOGGER);

        assertEquals(List.of("main"), List.copyOf(read.keySet()));
        GameMapDefinition main = read.get("main");
        assertEquals(2, main.teamSize());
        assertEquals(1, main.lives());
        assertEquals(2000, main.border());
    }

    @Test
    void writtenArenasReadBackTheSame() throws Exception {
        GameMapDefinition arena = GameMapDefinition.fromDefaults("duel", "Duel", null, 2)
                .with(ArenaOption.MODE, BingoGameMode.ADVANCEMENTS)
                .with(ArenaOption.WIN_MODE, WinMode.FULL_CARD)
                .with(ArenaOption.TIME_LIMIT, 600L)
                .with(ArenaOption.SEED, "summer")
                .with(ArenaOption.BORDER, 500);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(ArenaManager.write(List.of(arena)).saveToString());

        assertEquals(arena, ArenaManager.read(yaml, null, 2, LOGGER).get("duel"));
    }
}
