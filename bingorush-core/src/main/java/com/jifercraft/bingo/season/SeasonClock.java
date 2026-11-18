package com.jifercraft.bingo.season;

/**
 * Where the season reads the time. Separate from the system clock so
 * /bingo season phase and the tests can jump to any date.
 */
public interface SeasonClock {

    long currentTimeMillis();

    static SeasonClock system() {
        return System::currentTimeMillis;
    }

    static SeasonClock fixed(long millis) {
        return () -> millis;
    }
}
