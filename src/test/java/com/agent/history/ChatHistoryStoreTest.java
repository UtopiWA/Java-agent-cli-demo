package com.agent.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatHistoryStoreTest {
    @Test
    void shouldCreateUpdateAndLoadUtf8Session(@TempDir Path root) throws Exception {
        ChatHistoryStore store = new ChatHistoryStore(root);
        ChatSession first = session("id-1", "中文标题", "2026-10-16T10:00:00+08:00");

        store.save(first);
        ChatSession updated = new ChatSession(first.schemaVersion(), first.id(), first.title(),
                first.createdAt(), "2026-10-16T11:00:00+08:00", first.turnCount(),
                first.systemPromptVersion(), first.messages());
        store.save(updated);

        assertEquals(1, Files.list(store.historyDirectory()).filter(
                path -> path.toString().endsWith(".json")).count());
        assertEquals("中文标题", store.load("id-1").title());
    }

    @Test
    void shouldSortValidSessionsAndSkipCorruptFiles(@TempDir Path root) throws Exception {
        ChatHistoryStore store = new ChatHistoryStore(root);
        store.save(session("old", "旧", "2026-10-16T10:00:00+08:00"));
        store.save(session("new", "新", "2026-10-16T12:00:00+08:00"));
        Files.writeString(store.historyDirectory().resolve("broken.json"), "not json");

        ChatHistoryStore.SessionList result = store.list();

        assertEquals(List.of("new", "old"),
                result.sessions().stream().map(ChatSession::id).toList());
        assertEquals(1, result.skippedFiles());
    }

    @Test
    void shouldDeleteOnlyExactSessionId(@TempDir Path root) throws Exception {
        ChatHistoryStore store = new ChatHistoryStore(root);
        store.save(session("keep", "保留", "2026-10-16T10:00:00+08:00"));
        store.save(session("remove", "删除", "2026-10-16T11:00:00+08:00"));

        assertTrue(store.delete("remove"));
        assertEquals(List.of("keep"), store.list().sessions().stream()
                .map(ChatSession::id).toList());
        assertEquals(false, store.delete("missing"));
    }

    private static ChatSession session(String id, String title, String updatedAt) {
        String created = "2026-10-16T09:00:00+08:00";
        return new ChatSession(1, id, title, created, updatedAt, 1, 1, List.of(
                new StoredMessage(0, "system", "系统", null, null, null, created),
                new StoredMessage(1, "user", "任务", null, null, null, created),
                new StoredMessage(2, "assistant", "完成", null, null, null, updatedAt)));
    }
}
