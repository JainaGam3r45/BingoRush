package com.jifercraft.bingo.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeasonClockTest {

    @Test
    void fixedClockReturnsConstantTime() {
        SeasonClock clock = SeasonClock.fixed(123456789L);
        assertEquals(123456789L, clock.currentTimeMillis());
        assertEquals(123456789L, clock.currentTimeMillis());
    }

    @Test
    void systemClockAdvances() {
        SeasonClock clock = SeasonClock.system();
        long t1 = clock.currentTimeMillis();
        assertTrue(t1 > 0);
    }

    @Test
    void simulatedClockWarpsCorrectly() {
        SimulatedSeasonClock clock = new SimulatedSeasonClock(1000L);
        assertEquals(1000L, clock.currentTimeMillis());

        clock.warp(500L);
        assertEquals(1500L, clock.currentTimeMillis());

        clock.warpSeconds(10);
        assertEquals(11500L, clock.currentTimeMillis());

        clock.set(50000L);
        assertEquals(50000L, clock.currentTimeMillis());
    }
}
