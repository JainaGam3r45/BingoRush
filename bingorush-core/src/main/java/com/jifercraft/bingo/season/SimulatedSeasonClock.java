package com.jifercraft.bingo.season;

/** A clock /bingo season phase can set, to try a phase without waiting for it. */
public final class SimulatedSeasonClock implements SeasonClock {

    private volatile long currentMillis;

    public SimulatedSeasonClock(long initialMillis) {
        this.currentMillis = initialMillis;
    }

    public void set(long millis) {
        this.currentMillis = millis;
    }

    public void warp(long deltaMillis) {
        this.currentMillis += deltaMillis;
    }

    public void warpSeconds(long seconds) {
        warp(seconds * 1000L);
    }

    @Override
    public long currentTimeMillis() {
        return currentMillis;
    }
}
