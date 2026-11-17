package com.jifercraft.bingo.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueuedFileWriterTest {

    private static final Logger LOGGER = Logger.getAnonymousLogger();

    @Test
    void deleteAfterQueuedWritesLeavesNoFile(@TempDir Path tempDir) {
        File file = tempDir.resolve("data/active-round.json").toFile();
        QueuedFileWriter writer = new QueuedFileWriter(file, LOGGER);

        for (int i = 0; i < 50; i++) {
            writer.write("{\"tick\":" + i + "}");
        }
        writer.delete();
        writer.close();

        assertFalse(file.exists());
        assertFalse(new File(file.getParentFile(), file.getName() + ".tmp").exists());
    }

    @Test
    void lastWriteWins(@TempDir Path tempDir) throws IOException {
        File file = tempDir.resolve("active-round.json").toFile();
        QueuedFileWriter writer = new QueuedFileWriter(file, LOGGER);

        for (int i = 0; i < 50; i++) {
            writer.write("{\"tick\":" + i + "}");
        }
        writer.close();

        assertEquals("{\"tick\":49}", Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void writeAfterCloseRunsInline(@TempDir Path tempDir) {
        File file = tempDir.resolve("active-round.json").toFile();
        QueuedFileWriter writer = new QueuedFileWriter(file, LOGGER);
        writer.close();

        writer.write("{}");

        assertTrue(file.exists());
    }
}
