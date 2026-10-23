package com.agent.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.task.TaskTestProjects;
import com.fasterxml.jackson.databind.ObjectMapper;

class HistoryIsolationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldHideAndRejectAgentHistoryPaths(@TempDir Path root) throws Exception {
        Path history = root.resolve(".agent/history");
        Files.createDirectories(history);
        Files.writeString(history.resolve("session.json"), "private-history-marker");
        ToolRegistry registry = TaskTestProjects.toolRegistry(root);

        String listed = registry.execute("list_files", args("path", "."));
        String read = registry.execute("read_file", args("path", ".agent/history/session.json"));
        String searched = registry.execute("search_code", mapper.createObjectNode()
                .put("path", ".")
                .put("keyword", "private-history-marker").toString());
        String written = registry.execute("write_file", mapper.createObjectNode()
                .put("path", ".agent/history/other.json")
                .put("content", "x").toString());

        assertFalse(listed.contains(".agent"));
        assertFalse(searched.contains("private-history-marker"));
        assertTrue(read.contains("拒绝访问敏感路径"));
        assertTrue(written.contains("未知参数: path"));
    }

    private String args(String name, String value) {
        return mapper.createObjectNode().put(name, value).toString();
    }
}
