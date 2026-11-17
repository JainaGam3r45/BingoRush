package com.jifercraft.bingo.game;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Writes a text file through a temporary file and a move, so a hard kill in
 * the middle of a save never leaves half a file behind.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void write(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmpFile = new File(parent, file.getName() + ".tmp");
        try {
            Files.write(tmpFile.toPath(), text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some Windows filesystems do not support atomic moves across directories or existing targets.
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmpFile.toPath());
            } catch (IOException ignored) {
            }
            throw new UncheckedIOException("Failed to write " + file.getPath(), e);
        }
    }

    /** Deletes the file and any temporary file a crash left next to it. */
    public static boolean delete(File file) {
        boolean deleted = false;
        try {
            deleted = Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
        File tmpFile = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            Files.deleteIfExists(tmpFile.toPath());
        } catch (IOException ignored) {
        }
        return deleted;
    }
}
