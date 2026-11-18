package com.jifercraft.bingo.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GiftClaimsTest {

    @Test
    void recordClaimTracksUniquely() {
        GiftClaims claims = new GiftClaims();
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        assertTrue(claims.recordClaim(player1, "gift_1"));
        assertFalse(claims.recordClaim(player1, "gift_1"));
        assertTrue(claims.recordClaim(player1, "gift_2"));

        assertEquals(2, claims.claimedCount(player1));
        assertEquals(0, claims.claimedCount(player2));
        assertTrue(claims.hasClaimed(player1, "gift_1"));
        assertTrue(claims.hasClaimed(player1, "gift_2"));
        assertFalse(claims.hasClaimed(player2, "gift_1"));
    }

    @Test
    void resetClearsPlayerProgress() {
        GiftClaims claims = new GiftClaims();
        UUID player = UUID.randomUUID();

        claims.recordClaim(player, "gift_1");
        assertEquals(1, claims.claimedCount(player));

        claims.reset(player);
        assertEquals(0, claims.claimedCount(player));
        assertFalse(claims.hasClaimed(player, "gift_1"));
    }

    @Test
    void saveAndLoadSurvivesRestart(@TempDir Path tempDir) {
        File file = tempDir.resolve("claims.json").toFile();
        GiftClaims original = new GiftClaims();
        UUID player = UUID.randomUUID();

        original.recordClaim(player, "loc_1");
        original.recordClaim(player, "loc_2");
        original.save(file);

        assertTrue(file.exists());

        GiftClaims loaded = GiftClaims.load(file, null);
        assertEquals(2, loaded.claimedCount(player));
        assertTrue(loaded.hasClaimed(player, "loc_1"));
        assertTrue(loaded.hasClaimed(player, "loc_2"));
    }
}
