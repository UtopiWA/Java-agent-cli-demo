package com.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @Test
    void shouldLoadNonBlankKeyFromEnvFile(@TempDir Path root)
            throws Exception {
        Path envFile = root.resolve(".env");
        Files.writeString(envFile, "# local only\nGLM_API_KEY=file-test-key\n");

        assertEquals("file-test-key",
                Main.loadApiKey(envFile, Map.of("GLM_API_KEY", "environment-test-key")));
    }

    @Test
    void shouldFallBackToEnvironmentWhenPlaceholderIsBlank(
            @TempDir Path root) throws Exception {
        Path envFile = root.resolve(".env");
        Files.writeString(envFile, "# fill later\nGLM_API_KEY=\n");

        assertEquals("environment-test-key",
                Main.loadApiKey(envFile, Map.of("GLM_API_KEY", " environment-test-key ")));
        assertNull(Main.loadApiKey(envFile, Map.of()));
    }

    @Test
    void shouldRejectLegacyCommandLineOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.main(new String[]{"--upper"}));
    }
}
