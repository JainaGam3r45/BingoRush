package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.ClaimBoard;
import com.jifercraft.bingo.card.WinMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RoundTest {

    @Test
    void recordsOnlyFirstClaim() {
        List<String> items = IntStream.range(0, 25).mapToObj(i -> "test:item" + i).toList();
        BingoCard card = new BingoCard(42L, items);
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD);
        Round round = new Round(42L, card, board);

        assertNull(round.firstClaim());

        round.recordFirstClaim(5, 1, 15000L);
        assertNotNull(round.firstClaim());
        assertEquals(5, round.firstClaim().cell());
        assertEquals(1, round.firstClaim().teamId());
        assertEquals(15000L, round.firstClaim().elapsedMs());

        round.recordFirstClaim(10, 2, 25000L);
        assertEquals(5, round.firstClaim().cell());
        assertEquals(1, round.firstClaim().teamId());
        assertEquals(15000L, round.firstClaim().elapsedMs());
    }

    @Test
    void storesGameMode() {
        List<String> items = IntStream.range(0, 25).mapToObj(i -> "test:item" + i).toList();
        BingoCard card = new BingoCard(1L, items);
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD);

        Round defaultRound = new Round(1L, card, board);
        assertEquals(BingoGameMode.ITEMS, defaultRound.mode());

        Round advRound = new Round(2L, BingoGameMode.ADVANCEMENTS, card, board);
        assertEquals(BingoGameMode.ADVANCEMENTS, advRound.mode());
    }

    @Test
    void supportsLockoutBoard() {
        List<String> items = IntStream.range(0, 25).mapToObj(i -> "test:item" + i).toList();
        BingoCard card = new BingoCard(3L, items);
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD, true);
        Round round = new Round(3L, card, board);
        assertTrue(round.board().isLockout());
    }

    private static Round livesRound(int lives) {
        List<String> items = IntStream.range(0, 25).mapToObj(i -> "test:item" + i).toList();
        return new Round(1L, BingoGameMode.ITEMS, new BingoCard(1L, items), new ClaimBoard(WinMode.FULL_CARD), lives);
    }

    @Test
    void unlimitedLivesNeverEliminate() {
        Round round = livesRound(0);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            assertFalse(round.recordDeath(player));
        }
        assertFalse(round.isEliminated(player));
        assertEquals(-1, round.livesLeft(player));
    }

    @Test
    void lastLifeEliminates() {
        Round round = livesRound(3);
        UUID player = UUID.randomUUID();
        assertEquals(3, round.livesLeft(player));
        assertFalse(round.recordDeath(player));
        assertFalse(round.recordDeath(player));
        assertEquals(1, round.livesLeft(player));
        assertTrue(round.recordDeath(player));
        assertTrue(round.isEliminated(player));
        assertEquals(0, round.livesLeft(player));
        // Dying again as a spectator changes nothing and is not a new elimination.
        assertFalse(round.recordDeath(player));
    }

    @Test
    void hardcoreIsOneLife() {
        Round round = livesRound(1);
        UUID player = UUID.randomUUID();
        assertTrue(round.recordDeath(player));
    }

    @Test
    void teamStandsWhileAnyMemberHasLives() {
        Round round = livesRound(1);
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID b1 = UUID.randomUUID();
        Map<Integer, List<UUID>> teams = Map.of(1, List.of(a1, a2), 2, List.of(b1));

        assertEquals(2, round.teamsStanding(teams).size());
        round.recordDeath(a1);
        assertEquals(2, round.teamsStanding(teams).size());
        round.recordDeath(b1);
        assertEquals(List.of(1), round.teamsStanding(teams));
        round.recordDeath(a2);
        assertTrue(round.teamsStanding(teams).isEmpty());
    }
}
