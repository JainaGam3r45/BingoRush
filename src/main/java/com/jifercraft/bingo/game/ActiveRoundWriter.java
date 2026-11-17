package com.jifercraft.bingo.game;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes and deletes the active round file on one background thread.
 * A single thread on purpose: the Bukkit async pool gives no ordering, and a
 * save that lands after the delete at the end of a round would bring a
 * finished round back from the dead on the next boot.
 */
final class ActiveRoundWriter {

    private final File file;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BingoRush-RoundWriter");
        thread.setDaemon(true);
        return thread;
    });

    ActiveRoundWriter(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    void write(String json) {
        submit(() -> {
            try {
                RoundStorage.write(file, json);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not save active round state", e);
            }
        });
    }

    void delete() {
        submit(() -> RoundStorage.delete(file));
    }

    /** Waits for queued writes so a final synchronous save cannot be overwritten. */
    void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Active round writes did not finish within 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            // Already closed, which only happens while the plugin shuts down.
            task.run();
        }
    }
}
