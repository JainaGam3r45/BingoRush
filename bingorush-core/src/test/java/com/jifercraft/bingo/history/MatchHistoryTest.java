package com.jifercraft.bingo.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatchHistoryTest {

    private MatchRecord createDummyRecord(String id, long timestamp, String winnerName) {
        MatchRecord.TeamResult winner = new MatchRecord.TeamResult(
                1,
                winnerName,
                "#FF5555",
                5,
                List.of("Alice", "Bob")
        );
        MatchRecord.TeamResult runnerUp = new MatchRecord.TeamResult(
                2,
                "Blue Team",
                "#5555FF",
                3,
                List.of("Charlie")
        );
        MatchRecord.FirstClaim first = new MatchRecord.FirstClaim(
                0,
                "minecraft:diamond",
                1,
                winnerName,
                15000L,
                "0:15"
        );
        List<String> board = List.of(
                "minecraft:diamond", "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:coal", "minecraft:dirt"
        );

        return new MatchRecord(
                id,
                timestamp,
                MatchRecord.formatDate(timestamp),
                123456789L,
                "ITEMS",
                "FULL_CARD",
                true,
                65000L,
                "1:05",
                "COMPLETION",
                winner,
                List.of(winner, runnerUp),
                first,
                board
        );
    }

    @Test
    void testRecordAndLoadMatch(@TempDir Path tempDir) {
        File dir = tempDir.toFile();
        MatchHistory history = new MatchHistory(dir, null, Runnable::run);

        long now = 1700000000000L;
        String id = "match-1";
        MatchRecord record = createDummyRecord(id, now, "Red Team");

        history.recordMatch(record, 50);

        File savedFile = new File(dir, id + ".json");
        assertTrue(savedFile.exists(), "JSON file should exist on disk");

        MatchRecord loaded = history.loadMatch(id);
        assertNotNull(loaded, "Match record should be loadable from disk");
        assertEquals(id, loaded.id());
        assertEquals(123456789L, loaded.seed());
        assertEquals("ITEMS", loaded.gameMode());
        assertEquals("FULL_CARD", loaded.winMode());
        assertTrue(loaded.lockout());
        assertEquals("1:05", loaded.durationFormatted());
        assertNotNull(loaded.winner());
        assertEquals("Red Team", loaded.winner().name());
        assertEquals(5, loaded.winner().claims());
        assertEquals(List.of("Alice", "Bob"), loaded.winner().players());
        assertNotNull(loaded.firstClaim());
        assertEquals("minecraft:diamond", loaded.firstClaim().item());

        assertEquals(1, history.summaries().size());
        MatchSummary summary = history.summaries().getFirst();
        assertEquals(id, summary.id());
        assertEquals("Red Team", summary.winnerName());
        assertEquals("#FF5555", summary.winnerColor());
    }

    @Test
    void testPruningOldMatchesExceedingLimit(@TempDir Path tempDir) {
        File dir = tempDir.toFile();
        MatchHistory history = new MatchHistory(dir, null, Runnable::run);

        int maxMatches = 3;
        for (int i = 1; i <= 5; i++) {
            long ts = 1700000000000L + (i * 1000L);
            MatchRecord record = createDummyRecord("match-" + i, ts, "Team " + i);
            history.recordMatch(record, maxMatches);
        }

        assertEquals(3, history.summaries().size(), "Summaries list should be pruned to max 3");

        // The remaining should be match-5, match-4, match-3 (newest first)
        assertEquals("match-5", history.summaries().get(0).id());
        assertEquals("match-4", history.summaries().get(1).id());
        assertEquals("match-3", history.summaries().get(2).id());

        // Check files on disk
        assertTrue(new File(dir, "match-5.json").exists());
        assertTrue(new File(dir, "match-4.json").exists());
        assertTrue(new File(dir, "match-3.json").exists());
        assertFalse(new File(dir, "match-1.json").exists(), "Oldest match file should be deleted");
        assertFalse(new File(dir, "match-2.json").exists(), "Second oldest match file should be deleted");
    }

    @Test
    void testLoadSummariesOnStartup(@TempDir Path tempDir) {
        File dir = tempDir.toFile();
        MatchHistory history = new MatchHistory(dir, null, Runnable::run);

        history.recordMatch(createDummyRecord("m-old", 1000L, "Old Team"), 50);
        history.recordMatch(createDummyRecord("m-new", 3000L, "New Team"), 50);
        history.recordMatch(createDummyRecord("m-mid", 2000L, "Mid Team"), 50);

        // Simulate server reboot by creating a fresh MatchHistory instance
        MatchHistory restored = new MatchHistory(dir, null, Runnable::run);
        List<MatchSummary> summaries = restored.summaries();

        assertEquals(3, summaries.size());
        assertEquals("m-new", summaries.get(0).id(), "Newest match should be index 0");
        assertEquals("m-mid", summaries.get(1).id());
        assertEquals("m-old", summaries.get(2).id());
    }

    @Test
    void testPaginationAndLookup(@TempDir Path tempDir) {
        File dir = tempDir.toFile();
        MatchHistory history = new MatchHistory(dir, null, Runnable::run);

        for (int i = 1; i <= 7; i++) {
            history.recordMatch(createDummyRecord("m-" + i, 1000L * i, "Team " + i), 50);
        }

        assertEquals(3, history.totalPages(3));
        assertEquals(3, history.page(1, 3).size());
        assertEquals(3, history.page(2, 3).size());
        assertEquals(1, history.page(3, 3).size());
        assertTrue(history.page(4, 3).isEmpty());

        assertNotNull(history.summary("m-4"));
        assertEquals("Team 4", history.summary("m-4").winnerName());
        assertNull(history.summary("non-existent"));
    }

    @Test
    void loadMatchRejectsIdsOutsideTheDirectory(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.resolve("matches").toFile();
        MatchHistory history = new MatchHistory(dir, null, Runnable::run);
        history.recordMatch(createDummyRecord("m-1", 1000L, "Team 1"), 50);
        Files.copy(new File(dir, "m-1.json").toPath(), tempDir.resolve("outside.json"));

        assertNull(history.loadMatch("../outside"));
        assertNull(history.loadMatch(""));
        assertNotNull(history.loadMatchAsync("m-1").get());
    }

    @Test
    void testClearHistory(@TempDir Path tempDir) {
        File dir = tempDir.toFile();
        MatchHistory history = new MatchHistory(dir, null, Runnable::run);

        history.recordMatch(createDummyRecord("m-1", 1000L, "Team 1"), 50);
        history.recordMatch(createDummyRecord("m-2", 2000L, "Team 2"), 50);

        assertEquals(2, history.summaries().size());
        assertTrue(new File(dir, "m-1.json").exists());

        history.clearHistory();

        assertTrue(history.summaries().isEmpty());
        assertFalse(new File(dir, "m-1.json").exists());
        assertFalse(new File(dir, "m-2.json").exists());
    }

    @Test
    void ownThreadKeepsSavesAndClearsInOrder(@TempDir Path tempDir) {
        File dir = tempDir.toFile();
        MatchHistory history = new MatchHistory(dir, null);

        history.recordMatch(createDummyRecord("m-1", 1000L, "Team 1"), 50);
        history.clearHistory();
        history.recordMatch(createDummyRecord("m-2", 2000L, "Team 2"), 50);
        history.close();

        assertFalse(new File(dir, "m-1.json").exists());
        assertTrue(new File(dir, "m-2.json").exists());
        assertEquals(1, history.summaries().size());
    }

    @Test
    void readsAMatchFileWrittenByPre2(@TempDir Path tempDir) throws Exception {
        // Written by 0.2.0-pre.2, when these classes were records.
        String json = """
                {
                  "id": "20261117-140656",
                  "timestamp": 1763388416000,
                  "date": "2026-11-17 14:06:56",
                  "seed": -7532274890455008512,
                  "gameMode": "ADVANCEMENTS",
                  "winMode": "FULL_CARD",
                  "lockout": false,
                  "durationMs": 61000,
                  "durationFormatted": "1:01",
                  "reason": "COMPLETION",
                  "winner": {"id": 4, "name": "Amarillo", "color": "#ffff55", "claims": 1, "players": ["JainaGamer45"]},
                  "ranking": [{"id": 4, "name": "Amarillo", "color": "#ffff55", "claims": 1, "players": ["JainaGamer45"]}],
                  "firstClaim": {"cell": 15, "item": "minecraft:story/upgrade_tools", "teamId": 4,
                    "teamName": "Amarillo", "elapsedMs": 23000, "timeFormatted": "0:23"},
                  "board": ["minecraft:story/upgrade_tools"]
                }
                """;
        File dir = tempDir.toFile();
        Files.writeString(tempDir.resolve("20261117-140656.json"), json);

        MatchHistory history = new MatchHistory(dir, null, Runnable::run);
        MatchRecord loaded = history.loadMatch("20261117-140656");

        assertNotNull(loaded);
        assertEquals(-7532274890455008512L, loaded.seed());
        assertEquals("Amarillo", loaded.winner().name());
        assertEquals(List.of("JainaGamer45"), loaded.winner().players());
        assertEquals("minecraft:story/upgrade_tools", loaded.firstClaim().item());
        assertEquals(1, history.summaries().size());
        assertEquals("Amarillo", history.summaries().get(0).winnerName());
    }
}
