package com.agent.tool;

import com.agent.llm.LLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证文件工具的协议定义、统一执行入口和失败 Observation。 */
class ToolRegistryTest {
    @Test
    void shouldExposeThreeConsistentToolSchemas() {
        ToolRegistry registry = new ToolRegistry();

        List<LLMClient.ToolDef> definitions = registry.getToolDefinitions();

        assertEquals(List.of("list_files", "read_file", "search_code"),
                definitions.stream().map(LLMClient.ToolDef::name).toList());
        LLMClient.ToolDef search = definitions.get(2);
        assertEquals("object", search.parameters().path("type").asText());
        assertTrue(search.parameters().path("properties").has("keyword"));
        assertTrue(search.parameters().path("properties").has("path"));
        assertEquals(2, search.parameters().path("required").size());
    }

    @Test
    void shouldReuseReadFileTool(@TempDir Path root) throws Exception {
        Path file = root.resolve("sample.txt");
        Files.writeString(file, "来自 Lab02 的内容");
        String arguments = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put("path", file.toString())
                .toString();

        String result = new ToolRegistry().execute("read_file", arguments);

        assertTrue(result.startsWith("文件内容:\n"));
        assertTrue(result.contains("来自 Lab02 的内容"));
    }

    @Test
    void shouldReturnErrorsAsObservations() {
        ToolRegistry registry = new ToolRegistry();

        assertEquals("未知工具: no_such_tool", registry.execute("no_such_tool", "{}"));
        assertTrue(registry.execute("read_file", "not-json")
                .startsWith("执行工具失败:"));
        assertTrue(registry.execute("read_file", "{}")
                .contains("缺少必填参数: path"));
    }

    @Test
    void shouldNeverExposeEnvFiles(@TempDir Path root) throws Exception {
        Path envFile = root.resolve(".env");
        Files.writeString(envFile, "GLM_API_KEY=not-a-real-secret");
        String rootJson = jsonPath(root);
        String fileJson = jsonPath(envFile);

        ToolRegistry registry = new ToolRegistry();
        String listed = registry.execute("list_files", rootJson);
        String searched = registry.execute(
                "search_code", "{\"keyword\":\"GLM_API_KEY\",\"path\":"
                        + new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString(root.toString()) + "}");
        String read = registry.execute("read_file", fileJson);

        assertFalse(listed.contains(".env"));
        assertFalse(searched.contains("not-a-real-secret"));
        assertTrue(read.contains("拒绝访问敏感配置文件"));
    }

    private static String jsonPath(Path path) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put("path", path.toString())
                .toString();
    }
}
