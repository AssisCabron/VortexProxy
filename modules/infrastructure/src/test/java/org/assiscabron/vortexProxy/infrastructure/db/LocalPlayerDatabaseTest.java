package org.assiscabron.vortexProxy.infrastructure.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LocalPlayerDatabaseTest {

    private LocalPlayerDatabase database;
    private Path tempDir;
    private Logger mockLogger;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("vortex_tests_");
        mockLogger = mock(Logger.class);
        database = new LocalPlayerDatabase(tempDir, mockLogger);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up temp directory after tests
        Files.walk(tempDir)
            .sorted((a, b) -> b.compareTo(a))
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {}
            });
    }

    @Test
    void testInitPlayerCreatesFile() {
        UUID randomId = UUID.randomUUID();
        String username = "TestPlayer";
        String ip = "127.0.0.1";

        database.initPlayer(randomId, username, ip);

        Path expectedFile = tempDir.resolve("players").resolve(randomId.toString() + ".json");
        assertTrue(Files.exists(expectedFile), "Player JSON file should have been created.");
    }
}
