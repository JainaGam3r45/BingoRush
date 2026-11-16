package com.jifercraft.bingo.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimBoardTest {

    @Test
    void fillingTheWholeCardWinsInFullCardMode() {
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD);
        for (int cell = 0; cell < BingoCard.CELLS - 1; cell++) {
            board.claim(cell, 1, 100 + cell);
            assertTrue(board.completedWinner().isEmpty(), "nobody can win before the last cell");
        }
        board.claim(BingoCard.CELLS - 1, 1, 100 + BingoCard.CELLS - 1);
        assertEquals(1, board.completedWinner().getAsInt());
    }

    @Test
    void aFullRowWinsInLineMode() {
        ClaimBoard board = new ClaimBoard(WinMode.LINE);
        for (int cell : List.of(5, 6, 7, 8, 9)) {
            board.claim(cell, 2, 50);
        }
        assertEquals(2, board.completedWinner().getAsInt());
    }

    @Test
    void aFullColumnWinsInLineMode() {
        ClaimBoard board = new ClaimBoard(WinMode.LINE);
        for (int cell : List.of(3, 8, 13, 18, 23)) {
            board.claim(cell, 1, 50);
        }
        assertEquals(1, board.completedWinner().getAsInt());
    }

    @Test
    void bothDiagonalsWinInLineMode() {
        ClaimBoard main = new ClaimBoard(WinMode.LINE);
        for (int cell : List.of(0, 6, 12, 18, 24)) {
            main.claim(cell, 1, 10);
        }
        assertEquals(1, main.completedWinner().getAsInt());

        ClaimBoard anti = new ClaimBoard(WinMode.LINE);
        for (int cell : List.of(4, 8, 12, 16, 20)) {
            anti.claim(cell, 1, 10);
        }
        assertEquals(1, anti.completedWinner().getAsInt());
    }

    @Test
    void scatteredClaimsWinNothingInLineMode() {
        ClaimBoard board = new ClaimBoard(WinMode.LINE);
        for (int cell : List.of(0, 1, 2, 3, 7, 13, 17, 21, 23, 24, 6, 12)) {
            board.claim(cell, 1, 10);
        }
        assertTrue(board.completedWinner().isEmpty());
    }

    @Test
    void teamsProgressIndependently() {
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD);
        assertTrue(board.claim(0, 1, 10));
        assertTrue(board.claim(0, 2, 20));
        assertEquals(1, board.count(1));
        assertEquals(1, board.count(2));
        assertEquals(List.of(1, 2), board.claimers(0));
    }

    @Test
    void repeatingAClaimChangesNothing() {
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD);
        assertTrue(board.claim(7, 1, 100));
        assertFalse(board.claim(7, 1, 500));
        assertEquals(1, board.count(1));
        assertEquals(100, board.claimTime(7, 1));
        assertEquals(100, board.lastClaimTime(1));
    }

    @Test
    void theLeaderIsTheTeamWithTheMostCells() {
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD);
        for (int cell = 0; cell < 3; cell++) {
            board.claim(cell, 1, 100);
        }
        for (int cell = 0; cell < 5; cell++) {
            board.claim(cell, 2, 200);
        }
        assertEquals(2, board.leader().getAsInt());
    }

    @Test
    void aCountTieGoesToTheEarlierLastClaim() {
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD);
        for (int cell = 0; cell < 2; cell++) {
            board.claim(cell, 1, 900);
        }
        for (int cell = 0; cell < 2; cell++) {
            board.claim(cell, 2, 300);
        }
        assertEquals(2, board.leader().getAsInt());
    }

    @Test
    void anIdleBoardHasNoLeader() {
        ClaimBoard board = new ClaimBoard(WinMode.LINE);
        assertTrue(board.leader().isEmpty());
    }

    @Test
    void theFirstTeamToCompleteKeepsTheWin() {
        ClaimBoard board = new ClaimBoard(WinMode.LINE);
        // Team 2 completes a row at t=50, team 1 gets a full row later.
        for (int cell : List.of(5, 6, 7, 8, 9)) {
            board.claim(cell, 2, 50);
        }
        for (int cell : List.of(10, 11, 12, 13, 14)) {
            board.claim(cell, 1, 80);
        }
        assertEquals(2, board.completedWinner().getAsInt());
    }

    @Test
    void untoldTeamsReadAsZero() {
        ClaimBoard board = new ClaimBoard(WinMode.FULL_CARD);
        assertEquals(0, board.count(9));
        assertEquals(0, board.lastClaimTime(9));
        assertTrue(board.claimers(3).isEmpty());
        assertFalse(board.isClaimed(3, 9));
    }
}
