package com.agent;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.history.ChatHistoryStore;
import com.agent.history.HistoryContextBuilder;
import com.agent.history.HistorySanitizer;
import com.agent.history.SessionManager;
import com.agent.history.TitleGenerator;
import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.tool.ToolRegistry;

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

    @Test
    void shouldHandleClearCommandInAgentCli() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("第一轮", null, 1, 1),
                new LLMResponse("第二轮", null, 1, 1)));
        Agent agent = new Agent(fake, new ToolRegistry());
        StringWriter output = new StringWriter();

        Main.runAgentCli(
                new BufferedReader(new StringReader("问题一\nclear\n问题二\nexit\n")),
                new PrintWriter(output, true),
                agent);

        assertTrue(output.toString().contains("历史已清空"));
        assertEquals(2, fake.requests().get(1).size());
        assertEquals("问题二", fake.requests().get(1).get(1).content());
    }

    @Test
    void shouldDeleteSessionFromPersistentCli(@TempDir Path root) throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("已完成", null, 1, 1),
                new LLMResponse("模型标题", null, 1, 1)));
        HistorySanitizer sanitizer = new HistorySanitizer();
        HistoryContextBuilder context = new HistoryContextBuilder();
        Agent agent = new Agent(fake, new ToolRegistry(), context);
        SessionManager sessions = new SessionManager(agent, new ChatHistoryStore(root),
                sanitizer, context, new TitleGenerator(fake, sanitizer));
        String id = sessions.currentSession().id();
        StringWriter output = new StringWriter();

        Main.runAgentCli(
                new BufferedReader(new StringReader("保存一轮\ndelete " + id + "\nexit\n")),
                new PrintWriter(output, true),
                sessions);

        assertTrue(output.toString().contains("已删除会话: " + id));
        assertTrue(output.toString().contains("已开始新会话"));
        assertTrue(sessions.sessions().contains("暂无历史会话"));
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
