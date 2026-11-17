package com.jifercraft.bingo.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateCheckPolicyTest {

    @Test
    void allowsFirstCheckImmediately() {
        UpdateCheckPolicy policy = new UpdateCheckPolicy();
        assertTrue(policy.shouldCheck(1_000_000L));
    }

    @Test
    void blocksSubsequentCheckWithinInterval() {
        long interval = 60_000L;
        UpdateCheckPolicy policy = new UpdateCheckPolicy(interval);
        policy.recordCheck(100_000L);

        assertFalse(policy.shouldCheck(100_000L));
        assertFalse(policy.shouldCheck(159_999L));
    }

    @Test
    void allowsCheckOnceIntervalExpires() {
        long interval = 60_000L;
        UpdateCheckPolicy policy = new UpdateCheckPolicy(interval);
        policy.recordCheck(100_000L);

        assertTrue(policy.shouldCheck(160_000L));
        assertTrue(policy.shouldCheck(200_000L));
    }

    @Test
    void resetClearsLastCheckTimestamp() {
        UpdateCheckPolicy policy = new UpdateCheckPolicy(60_000L);
        policy.recordCheck(100_000L);
        assertFalse(policy.shouldCheck(110_000L));

        policy.reset();
        assertEquals(0L, policy.lastCheckTime());
        assertTrue(policy.shouldCheck(110_000L));
    }

    @Test
    void rejectsNegativeInterval() {
        assertThrows(IllegalArgumentException.class, () -> new UpdateCheckPolicy(-1L));
    }
}
