package com.jifercraft.bingo.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jifercraft.bingo.BingoRush;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Manages match history on disk and in memory. Completed matches are saved
 * asynchronously as individual JSON files in data/matches/. A lightweight
 * in-memory summary index allows instant in-game browsing.
 */
public final class MatchHistory {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final File directory;
    private final BingoRush plugin;
    private final Consumer<Runnable> asyncExecutor;
    private final ExecutorService ownExecutor;
    private final List<MatchSummary> summaries = new CopyOnWriteArrayList<>();

    /**
     * File work runs on one thread of its own, in order: the Bukkit async
     * pool could run a history clear before a match save queued earlier, and
     * it drops whatever is still queued when the plugin disables.
     */
    public MatchHistory(File directory, BingoRush plugin) {
        this(directory, plugin, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BingoRush-history");
            thread.setDaemon(true);
            return thread;
        }));
    }

    private MatchHistory(File directory, BingoRush plugin, ExecutorService executor) {
        this.directory = directory;
        this.plugin = plugin;
        this.ownExecutor = executor;
        this.asyncExecutor = task -> {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException e) {
                task.run();
            }
        };
        loadSummaries();
    }

    public MatchHistory(File directory, BingoRush plugin, Consumer<Runnable> asyncExecutor) {
        this.directory = directory;
        this.plugin = plugin;
        this.ownExecutor = null;
        this.asyncExecutor = asyncExecutor;
        loadSummaries();
    }

    /** Waits for queued saves, for the plugin shutting down. */
    public void close() {
        if (ownExecutor == null) {
            return;
        }
        ownExecutor.shutdown();
        try {
            if (!ownExecutor.awaitTermination(10, TimeUnit.SECONDS) && plugin != null) {
                plugin.getLogger().warning("Match history writes did not finish within 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public File directory() {
        return directory;
    }

    public synchronized void loadSummaries() {
        summaries.clear();
        if (!directory.exists()) {
            return;
        }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json") && !name.endsWith(".tmp"));
        if (files == null) {
            return;
        }
        List<MatchSummary> loaded = new ArrayList<>();
        for (File file : files) {
            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                MatchRecord record = GSON.fromJson(reader, MatchRecord.class);
                if (record != null) {
                    loaded.add(record.toSummary());
                }
            } catch (Exception e) {
                if (plugin != null) {
                    plugin.getLogger().warning("Could not read match file " + file.getName() + ": " + e.getMessage());
                }
            }
        }
        loaded.sort(Comparator.comparingLong(MatchSummary::timestamp).reversed());
        summaries.addAll(loaded);
    }

    public void recordMatch(MatchRecord record, int maxSavedMatches) {
        if (record == null) {
            return;
        }
        synchronized (this) {
            summaries.removeIf(s -> s.id().equalsIgnoreCase(record.id()));
            summaries.add(0, record.toSummary());
        }

        asyncExecutor.accept(() -> {
            saveToDisk(record);
            if (maxSavedMatches > 0) {
                pruneOldMatches(maxSavedMatches);
            }
        });
    }

    private void saveToDisk(MatchRecord record) {
        if (!directory.exists()) {
            directory.mkdirs();
        }
        File targetFile = new File(directory, record.id() + ".json");
        File tempFile = new File(directory, record.id() + ".tmp");

        try {
            String json = GSON.toJson(record);
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile.toPath(), StandardCharsets.UTF_8)) {
                writer.write(json);
            }
            try {
                Files.move(tempFile.toPath(), targetFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (plugin != null) {
                plugin.getLogger().severe("Failed to save match " + record.id() + ": " + e.getMessage());
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private synchronized void pruneOldMatches(int maxSavedMatches) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json") && !name.endsWith(".tmp"));
        if (files == null || files.length <= maxSavedMatches) {
            return;
        }

        while (summaries.size() > maxSavedMatches) {
            MatchSummary toRemove = summaries.remove(summaries.size() - 1);
            File f = new File(directory, toRemove.id() + ".json");
            if (f.exists()) {
                f.delete();
            }
        }

        File[] remainingFiles = directory.listFiles((dir, name) -> name.endsWith(".json") && !name.endsWith(".tmp"));
        if (remainingFiles != null && remainingFiles.length > maxSavedMatches) {
            List<File> fileList = new ArrayList<>(List.of(remainingFiles));
            fileList.sort(Comparator.comparingLong(File::lastModified));
            int excess = fileList.size() - maxSavedMatches;
            for (int i = 0; i < excess; i++) {
                fileList.get(i).delete();
            }
        }
    }

    /** Reads a match file on the async executor. The future completes on that thread. */
    public CompletableFuture<MatchRecord> loadMatchAsync(String id) {
        CompletableFuture<MatchRecord> future = new CompletableFuture<>();
        asyncExecutor.accept(() -> future.complete(loadMatch(id)));
        return future;
    }

    public MatchRecord loadMatch(String id) {
        // The id comes straight from a command argument; keep it inside data/matches/.
        if (id == null || !VALID_ID.matcher(id).matches()) {
            return null;
        }
        File file = new File(directory, id + ".json");
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, MatchRecord.class);
        } catch (Exception e) {
            if (plugin != null) {
                plugin.getLogger().warning("Could not load match " + id + ": " + e.getMessage());
            }
            return null;
        }
    }

    /** Empties the in-memory index right away and deletes the files on the async executor. */
    public void clearHistory() {
        summaries.clear();
        asyncExecutor.accept(this::deleteMatchFiles);
    }

    private synchronized void deleteMatchFiles() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    public List<MatchSummary> summaries() {
        return Collections.unmodifiableList(summaries);
    }

    public MatchSummary summary(String id) {
        if (id == null) {
            return null;
        }
        for (MatchSummary s : summaries) {
            if (s.id().equalsIgnoreCase(id)) {
                return s;
            }
        }
        return null;
    }

    public int totalPages(int pageSize) {
        if (summaries.isEmpty()) {
            return 1;
        }
        return (int) Math.ceil((double) summaries.size() / pageSize);
    }

    public List<MatchSummary> page(int pageNumber, int pageSize) {
        if (summaries.isEmpty() || pageNumber < 1) {
            return List.of();
        }
        int fromIndex = (pageNumber - 1) * pageSize;
        if (fromIndex >= summaries.size()) {
            return List.of();
        }
        int toIndex = Math.min(fromIndex + pageSize, summaries.size());
        return List.copyOf(summaries.subList(fromIndex, toIndex));
    }
}
