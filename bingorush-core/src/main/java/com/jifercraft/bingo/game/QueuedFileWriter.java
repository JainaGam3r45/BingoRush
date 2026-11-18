package com.jifercraft.bingo.game;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes and deletes one JSON file on its own background thread, in the
 * order the calls were made. A single thread on purpose: the Bukkit async
 * pool gives no ordering, and a save of the active round that lands after
 * the delete at the end of a round would bring it back on the next boot.
 */
public final class QueuedFileWriter {

    private final File file;
    private final Logger logger;
    private final ExecutorService executor;

    public QueuedFileWriter(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BingoRush-" + file.getName());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void write(String json) {
        submit(() -> {
            try {
                AtomicFiles.write(file, json);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not save " + file.getName(), e);
            }
        });
    }

    public void delete() {
        submit(() -> AtomicFiles.delete(file));
    }

    /** Waits for queued writes so a final synchronous save cannot be overwritten. */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Writes to " + file.getName() + " did not finish within 10 seconds");
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
