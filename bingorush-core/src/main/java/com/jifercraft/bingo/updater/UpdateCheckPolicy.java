package com.jifercraft.bingo.updater;

/**
 * Throttles update checks to stay well clear of GitHub's 60 requests per hour
 * anonymous rate limit. Checks within the interval reuse the last outcome.
 */
public final class UpdateCheckPolicy {

    public static final long DEFAULT_MIN_INTERVAL_MS = 15 * 60 * 1000L;

    private final long minIntervalMs;
    private long lastCheckTime;

    public UpdateCheckPolicy() {
        this(DEFAULT_MIN_INTERVAL_MS);
    }

    public UpdateCheckPolicy(long minIntervalMs) {
        if (minIntervalMs < 0) {
            throw new IllegalArgumentException("Interval cannot be negative: " + minIntervalMs);
        }
        this.minIntervalMs = minIntervalMs;
    }

    public synchronized boolean shouldCheck(long now) {
        return lastCheckTime == 0L || (now - lastCheckTime) >= minIntervalMs;
    }

    public synchronized void recordCheck(long now) {
        this.lastCheckTime = now;
    }

    public synchronized long lastCheckTime() {
        return lastCheckTime;
    }

    public long minIntervalMs() {
        return minIntervalMs;
    }

    public synchronized void reset() {
        this.lastCheckTime = 0L;
    }
}
