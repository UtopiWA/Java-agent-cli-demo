package com.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.llm.LLMClient;
import com.agent.task.TaskTestProjects;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 验证测试任务五个工具的协议定义、统一执行入口和安全边界。 */
class ToolRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldExposeFiveDomainSpecificToolSchemas(@TempDir Path root) throws Exception {
        ToolRegistry registry = TaskTestProjects.toolRegistry(root);
        List<LLMClient.ToolDef> definitions = registry.getToolDefinitions();

        assertEquals(List.of("list_files", "read_file", "search_code",
                        "write_file", "execute_command"),
                definitions.stream().map(LLMClient.ToolDef::name).toList());
        LLMClient.ToolDef list = definitions.get(0);
        assertFalse(list.parameters().path("additionalProperties").asBoolean(true));
        assertEquals(0, list.parameters().path("properties")
                .path("max_depth").path("minimum").asInt());
        assertEquals(6, list.parameters().path("properties")
                .path("max_depth").path("maximum").asInt());
        assertTrue(list.parameters().path("properties").has("offset"));
        assertTrue(list.parameters().path("properties").has("limit"));

        LLMClient.ToolDef search = definitions.get(2);
        assertTrue(search.parameters().path("properties").has("keyword"));
        assertFalse(search.parameters().path("properties").has("regex"));
        assertTrue(search.parameters().path("properties").has("context_lines"));

        LLMClient.ToolDef write = definitions.get(3);
        assertTrue(write.parameters().path("properties").has("content"));
        assertFalse(write.parameters().path("properties").has("path"));
        assertEquals(1, write.parameters().path("properties")
                .path("content").path("minLength").asInt(-1));

        LLMClient.ToolDef execute = definitions.get(4);
        assertEquals("string", execute.parameters().path("properties")
                .path("action").path("type").asText());
        assertFalse(execute.parameters().path("properties").has("program"));
    }

    @Test
    void shouldReturnErrorsAsObservations(@TempDir Path root) throws Exception {
        ToolRegistry registry = TaskTestProjects.toolRegistry(root);

        assertEquals("未知工具: no_such_tool", registry.execute("no_such_tool", "{}"));
        assertTrue(registry.execute("read_file", "not-json").startsWith("执行工具失败:"));
        assertTrue(registry.execute("read_file", "").contains("arguments 必须是 JSON 对象"));
        assertTrue(registry.execute("read_file", "{}").contains("缺少必填参数: path"));
        assertTrue(registry.execute("read_file", "{\"path\":\"pom.xml\",\"extra\":1}")
                .contains("未知参数: extra"));
        assertTrue(registry.execute("list_files", "{\"path\":\".\",\"max_depth\":\"1\"}")
                .contains("类型必须为 integer"));
        assertTrue(registry.execute("write_file", "{\"path\":\"Other.java\",\"content\":\"x\"}")
                .contains("未知参数: path"));
        assertTrue(registry.execute("execute_command", "{\"program\":\"mvn\"}")
                .contains("未知参数: program"));
    }

    @Test
    void shouldNeverExposeEnvFiles(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".env"), "GLM_API_KEY=not-a-real-secret");
        ToolRegistry registry = TaskTestProjects.toolRegistry(root);

        String listed = registry.execute("list_files", "{\"path\":\".\"}");
        String searched = registry.execute("search_code",
                "{\"keyword\":\"GLM_API_KEY\",\"path\":\".\"}");
        String read = registry.execute("read_file", "{\"path\":\".env\"}");

        assertFalse(listed.contains(".env"));
        assertFalse(searched.contains("not-a-real-secret"));
        assertTrue(read.contains("拒绝访问敏感配置文件"));
    }

    @Test
    void shouldRejectDirectAccessToIgnoredPaths(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".agentignore"), "private/\n");
        Files.createDirectories(root.resolve("private"));
        Files.writeString(root.resolve("private/Secret.java"), "class Secret {}");
        ToolRegistry registry = TaskTestProjects.toolRegistry(root);

        assertTrue(registry.execute("list_files", "{\"path\":\"private\"}")
                .contains("命中 .agentignore"));
        assertTrue(registry.execute("read_file", "{\"path\":\"private/Secret.java\"}")
                .contains("命中 .agentignore"));
        assertTrue(registry.execute("search_code", "{\"keyword\":\"Secret\",\"path\":\"private\"}")
                .contains("命中 .agentignore"));
    }

    @Test
    void shouldSupportBoundedReadListAndLiteralSearch(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/deep"));
        Files.writeString(root.resolve("top.txt"), "first\nsecond\nthird");
        Files.writeString(root.resolve("src/deep/Demo.java"), "class Demo42 {}");
        ToolRegistry registry = TaskTestProjects.toolRegistry(root);

        String listed = registry.execute("list_files", mapper.createObjectNode()
                .put("path", ".").put("max_depth", 1).toString());
        String read = registry.execute("read_file", mapper.createObjectNode()
                .put("path", "top.txt").put("offset", 1).put("limit", 1).toString());
        String searched = registry.execute("search_code", mapper.createObjectNode()
                .put("keyword", "Demo42").put("path", ".")
                .put("context_lines", 1).toString());

        assertTrue(listed.contains("top.txt"));
        assertFalse(listed.contains("Demo.java"));
        assertTrue(read.contains("2 | second"));
        assertTrue(read.contains("returned_range: 2-2"));
        assertTrue(searched.contains("src/deep/Demo.java:1"));
        assertTrue(searched.contains("> 1 | class Demo42 {}"));
    }

    @Test
    void shouldReportSingleLineThatCannotFitAReadPage(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("large.txt"), "x".repeat(ToolRegistry.MAX_RESULT_CHARS * 2));
        String result = TaskTestProjects.toolRegistry(root)
                .execute("read_file", "{\"path\":\"large.txt\"}");

        assertTrue(result.length() <= ToolRegistry.MAX_RESULT_CHARS);
        assertTrue(result.contains("LINE_TOO_LONG"));
        assertTrue(result.contains("line: 1"));
    }

    @Test
    void shouldRejectPathsOutsideProjectRoot(@TempDir Path parent) throws Exception {
        Path project = Files.createDirectory(parent.resolve("project"));
        Path outside = parent.resolve("outside.txt");
        Files.writeString(outside, "outside content must stay private");
        ToolRegistry registry = TaskTestProjects.toolRegistry(project);

        String traversal = registry.execute("read_file", "{\"path\":\"../outside.txt\"}");
        String absolute = registry.execute("read_file", jsonPath(outside));
        String outsideList = registry.execute("list_files", jsonPath(parent));

        assertTrue(traversal.contains("路径超出项目根目录"));
        assertTrue(absolute.contains("路径超出项目根目录"));
        assertTrue(outsideList.contains("路径超出项目根目录"));
        assertFalse(traversal.contains("outside content"));
        assertFalse(absolute.contains("outside content"));
    }

    private static String jsonPath(Path path) throws Exception {
        return new ObjectMapper().createObjectNode().put("path", path.toString()).toString();
    }
}
