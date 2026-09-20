package com.agent;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @Test
    void shouldProcessInputUntilEndOfStream() throws Exception {
        assertEquals(
                "Hello, Agent!" + System.lineSeparator() +
                        "I heard: Java" + System.lineSeparator(),
                runCli("hello\nJava\n", false));
    }

    @Test
    void shouldStopAtQuitWithoutPrintingAResponse() throws Exception {
        assertEquals(
                "I heard: before" + System.lineSeparator(),
                runCli("before\n QuIt \nafter\n", false));
    }

    @Test
    void shouldApplyUpperOptionToAllResponses() throws Exception {
        assertEquals(
                "HELLO, AGENT!" + System.lineSeparator() +
                        "I HEARD: JAVA" + System.lineSeparator(),
                runCli("hello\nJava\n", true));
    }

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

    private String runCli(String input, boolean upper) throws Exception {
        StringWriter output = new StringWriter();
        Main.run(
                new BufferedReader(new StringReader(input)),
                new PrintWriter(output, true),
                upper);
        return output.toString();
    }
}
