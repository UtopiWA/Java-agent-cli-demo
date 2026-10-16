package com.agent.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.Agent;
import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.tool.ToolRegistry;

class SessionManagerTest {
    @Test
    void shouldSaveSuccessfulTurnAndGenerateTitleOutsideHistory(@TempDir Path root) {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("任务完成", null, 1, 1),
                new LLMResponse("检查项目结构", null, 1, 1)));
        SessionManager manager = manager(root, fake);

        assertEquals("任务完成", manager.runTurn("检查项目结构"));

        assertEquals(1, manager.currentSession().turnCount());
        assertEquals("检查项目结构", manager.currentSession().title());
        assertEquals(3, manager.currentSession().messages().size());
        assertEquals(2, fake.requests().size());
        assertTrue(Files.isDirectory(root.resolve(".agent/history")));
    }

    @Test
    void shouldRollbackFailedTurnWithoutCreatingHistoryFile(@TempDir Path root) {
        FakeLLMClient fake = new FakeLLMClient(List.of());
        SessionManager manager = manager(root, fake);

        String answer = manager.runTurn("会失败");

        assertTrue(answer.contains("LLM 调用失败"));
        assertEquals(1, manager.currentSession().messages().size());
        assertEquals(0, manager.currentSession().turnCount());
        assertFalse(Files.exists(root.resolve(".agent/history")));
    }

    @Test
    void shouldLoadAndContinueSameSession(@TempDir Path root) {
        FakeLLMClient firstFake = new FakeLLMClient(List.of(
                new LLMResponse("第一轮", null, 1, 1),
                new LLMResponse("标题", null, 1, 1)));
        SessionManager first = manager(root, firstFake);
        first.runTurn("问题一");
        String id = first.currentSession().id();

        FakeLLMClient secondFake = new FakeLLMClient(List.of(
                new LLMResponse("第二轮", null, 1, 1)));
        SessionManager second = manager(root, secondFake);
        assertTrue(second.load(id).contains("已加载"));
        second.runTurn("问题二");

        assertEquals(id, second.currentSession().id());
        assertEquals(2, second.currentSession().turnCount());
        assertEquals(1, secondFake.requests().size());
    }

    @Test
    void shouldRollbackMemoryWhenAtomicSaveFails(@TempDir Path root) {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("已回答", null, 1, 1),
                new LLMResponse("标题", null, 1, 1)));
        HistorySanitizer sanitizer = new HistorySanitizer();
        HistoryContextBuilder context = new HistoryContextBuilder();
        Agent agent = new Agent(fake, new ToolRegistry(), context);
        ChatHistoryStore failingStore = new ChatHistoryStore(root) {
            @Override
            public void save(ChatSession session) throws IOException {
                throw new IOException("disk full");
            }
        };
        SessionManager manager = new SessionManager(agent, failingStore, sanitizer, context,
                new TitleGenerator(fake, sanitizer));

        String answer = manager.runTurn("问题");

        assertTrue(answer.contains("本轮未保存"));
        assertEquals(1, agent.historySnapshot().size());
        assertEquals(0, manager.currentSession().turnCount());
    }

    @Test
    void shouldDeleteCurrentSessionAndStartFresh(@TempDir Path root) {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("完成", null, 1, 1),
                new LLMResponse("模型生成标题", null, 1, 1)));
        SessionManager manager = manager(root, fake);
        manager.runTurn("第一轮任务内容很长且不应成为标题");
        String deletedId = manager.currentSession().id();

        String result = manager.delete(deletedId);

        assertTrue(result.contains("已删除会话"));
        assertFalse(deletedId.equals(manager.currentSession().id()));
        assertEquals(0, manager.currentSession().turnCount());
        assertTrue(manager.sessions().contains("暂无历史会话"));
    }

    @Test
    void shouldExposeSafeTitleFallbackReason(@TempDir Path root) {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("完成", null, 1, 1)));
        SessionManager manager = manager(root, fake);

        String answer = manager.runTurn("这是一个长度超过三十个字符的用户任务用来验证标题降级是否会给出提示");

        assertTrue(answer.contains("模型标题生成失败"));
        assertEquals(30, manager.currentSession().title().length());
    }

    private static SessionManager manager(Path root, FakeLLMClient fake) {
        HistorySanitizer sanitizer = new HistorySanitizer();
        HistoryContextBuilder context = new HistoryContextBuilder();
        Agent agent = new Agent(fake, new ToolRegistry(), context);
        return new SessionManager(agent, new ChatHistoryStore(root), sanitizer, context,
                new TitleGenerator(fake, sanitizer));
    }
}
