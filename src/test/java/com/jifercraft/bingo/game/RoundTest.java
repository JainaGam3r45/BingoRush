package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jifercraft.bingo.card.BingoCard;
import com.jifercraft.bingo.card.ClaimBoard;
import com.jifercraft.bingo.card.WinMode;
import java.util.List;
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
}
