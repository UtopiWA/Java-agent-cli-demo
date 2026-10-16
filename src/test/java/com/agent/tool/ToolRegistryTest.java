package com.agent.tool;

import com.agent.llm.LLMClient;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldExposeFiveDetailedToolSchemas() {
        ToolRegistry registry = new ToolRegistry();

        List<LLMClient.ToolDef> definitions = registry.getToolDefinitions();

        assertEquals(List.of(
                        "list_files", "read_file", "search_code",
                        "write_file", "execute_command"),
                definitions.stream().map(LLMClient.ToolDef::name).toList());
        LLMClient.ToolDef list = definitions.get(0);
        assertFalse(list.parameters().path("additionalProperties").asBoolean(true));
        assertEquals("integer", list.parameters()
                .path("properties").path("max_depth").path("type").asText());
        assertEquals(0, list.parameters()
                .path("properties").path("max_depth").path("minimum").asInt());
        LLMClient.ToolDef read = definitions.get(1);
        assertTrue(read.parameters().path("properties").has("offset"));
        assertTrue(read.parameters().path("properties").has("limit"));
        LLMClient.ToolDef search = definitions.get(2);
        assertEquals("object", search.parameters().path("type").asText());
        assertTrue(search.parameters().path("properties").has("keyword"));
        assertTrue(search.parameters().path("properties").has("path"));
        assertEquals("boolean", search.parameters()
                .path("properties").path("regex").path("type").asText());
        assertEquals(2, search.parameters().path("required").size());

        LLMClient.ToolDef write = definitions.get(3);
        assertTrue(write.parameters().path("properties").has("content"));
        assertEquals(0, write.parameters().path("properties")
                .path("content").path("minLength").asInt(-1));
        LLMClient.ToolDef execute = definitions.get(4);
        assertEquals("array", execute.parameters().path("properties")
                .path("arguments").path("type").asText());
        assertEquals("string", execute.parameters().path("properties")
                .path("arguments").path("items").path("type").asText());
        assertEquals(120, execute.parameters().path("properties")
                .path("timeout_seconds").path("maximum").asInt());
    }

    @Test
    void shouldReuseReadFileTool(@TempDir Path root) throws Exception {
        Path file = root.resolve("sample.txt");
        Files.writeString(file, "来自 Lab02 的内容");
        String arguments = jsonPath(file);

        String result = new ToolRegistry(root).execute("read_file", arguments);

        assertTrue(result.startsWith("文件内容:\n"));
        assertTrue(result.contains("来自 Lab02 的内容"));
    }

    @Test
    void shouldReturnErrorsAsObservations() {
        ToolRegistry registry = new ToolRegistry();

        assertEquals("未知工具: no_such_tool", registry.execute("no_such_tool", "{}"));
        assertTrue(registry.execute("read_file", "not-json")
                .startsWith("执行工具失败:"));
        assertTrue(registry.execute("read_file", "")
                .contains("arguments 必须是 JSON 对象"));
        assertTrue(registry.execute("read_file", "{}")
                .contains("缺少必填参数: path"));
        assertTrue(registry.execute("read_file", "{\"path\":\"README.md\",\"extra\":1}")
                .contains("未知参数: extra"));
        assertTrue(registry.execute("list_files", "{\"path\":\".\",\"max_depth\":\"1\"}")
                .contains("类型必须为 integer"));
    }

    @Test
    void shouldCreateAndOverwriteFileWithinProject(@TempDir Path root) throws Exception {
        ToolRegistry registry = new ToolRegistry(root);
        String create = registry.execute("write_file", mapper.createObjectNode()
                .put("path", "src/Generated.java")
                .put("content", "class Generated {}")
                .toString());
        String overwrite = registry.execute("write_file", mapper.createObjectNode()
                .put("path", "src/Generated.java")
                .put("content", "class Generated { int value; }")
                .toString());

        assertTrue(create.contains("写入文件成功"));
        assertTrue(create.contains("操作: 新建"));
        assertTrue(overwrite.contains("操作: 覆盖"));
        assertEquals("class Generated { int value; }",
                Files.readString(root.resolve("src/Generated.java")));
    }

    @Test
    void shouldRejectUnsafeWriteTargets(@TempDir Path parent) throws Exception {
        Path root = Files.createDirectory(parent.resolve("project"));
        ToolRegistry registry = new ToolRegistry(root);

        String traversal = registry.execute("write_file", mapper.createObjectNode()
                .put("path", "../outside.txt")
                .put("content", "outside")
                .toString());
        String environment = registry.execute("write_file", mapper.createObjectNode()
                .put("path", ".env.local")
                .put("content", "secret")
                .toString());
        String gitMetadata = registry.execute("write_file", mapper.createObjectNode()
                .put("path", ".git/config")
                .put("content", "unsafe")
                .toString());

        assertTrue(traversal.contains("路径超出项目根目录"));
        assertTrue(environment.contains("拒绝修改敏感路径"));
        assertTrue(gitMetadata.contains("拒绝修改敏感路径"));
        assertFalse(Files.exists(parent.resolve("outside.txt")));
        assertFalse(Files.exists(root.resolve(".env.local")));
    }

    @Test
    void shouldExecuteAllowedCommandAndRejectUnsafeCommandOptions(@TempDir Path root) {
        ToolRegistry registry = new ToolRegistry(root);
        var command = mapper.createObjectNode();
        command.put("program", "java");
        command.putArray("arguments").add("-version");
        String javaVersion = registry.execute("execute_command", command.toString());
        String denied = registry.execute("execute_command", mapper.createObjectNode()
                .put("program", "powershell")
                .toString());
        String outsideDirectory = registry.execute("execute_command", mapper.createObjectNode()
                .put("program", "java")
                .put("working_directory", "..")
                .toString());

        assertTrue(javaVersion.contains("退出码: 0"));
        assertTrue(javaVersion.contains("标准错误:"));
        assertTrue(denied.contains("不允许执行程序"));
        assertTrue(outsideDirectory.contains("路径超出项目根目录"));
    }

    @Test
    void shouldNeverExposeEnvFiles(@TempDir Path root) throws Exception {
        Path envFile = root.resolve(".env");
        Files.writeString(envFile, "GLM_API_KEY=not-a-real-secret");
        String rootJson = jsonPath(root);
        String fileJson = jsonPath(envFile);

        ToolRegistry registry = new ToolRegistry(root);
        String listed = registry.execute("list_files", rootJson);
        String searched = registry.execute("search_code", mapper.createObjectNode()
                .put("keyword", "GLM_API_KEY")
                .put("path", root.toString())
                .toString());
        String read = registry.execute("read_file", fileJson);

        assertFalse(listed.contains(".env"));
        assertFalse(searched.contains("not-a-real-secret"));
        assertTrue(read.contains("拒绝访问敏感配置文件"));
    }

    @Test
    void shouldSupportOptionalDepthPaginationAndRegex(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/deep"));
        Files.writeString(root.resolve("top.txt"), "first\nsecond\nthird");
        Files.writeString(root.resolve("src/deep/Demo.java"), "class Demo42 {}");
        ToolRegistry registry = new ToolRegistry(root);

        String listed = registry.execute("list_files", mapper.createObjectNode()
                .put("path", ".")
                .put("max_depth", 1)
                .toString());
        String read = registry.execute("read_file", mapper.createObjectNode()
                .put("path", "top.txt")
                .put("offset", 1)
                .put("limit", 1)
                .toString());
        String searched = registry.execute("search_code", mapper.createObjectNode()
                .put("keyword", "Demo\\d+")
                .put("path", ".")
                .put("regex", true)
                .toString());

        assertTrue(listed.contains("top.txt"));
        assertFalse(listed.contains("Demo.java"));
        assertEquals("文件内容:\nsecond", read);
        assertTrue(searched.contains("src/deep/Demo.java:1"));
    }

    @Test
    void shouldLimitLargeToolResultsBeforeReturningObservation(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("large.txt"), "x".repeat(
                ToolRegistry.MAX_RESULT_CHARS * 2));

        String result = new ToolRegistry(root).execute(
                "read_file", "{\"path\":\"large.txt\"}");

        assertTrue(result.length() <= ToolRegistry.MAX_RESULT_CHARS);
        assertTrue(result.contains("工具结果已截断"));
        assertTrue(result.contains("原始长度="));
    }

    @Test
    void shouldRejectPathsOutsideProjectRoot(@TempDir Path parent) throws Exception {
        Path project = Files.createDirectory(parent.resolve("project"));
        Path outside = parent.resolve("outside.txt");
        Files.writeString(outside, "outside content must stay private");
        ToolRegistry registry = new ToolRegistry(project);

        String traversal = registry.execute(
                "read_file", "{\"path\":\"../outside.txt\"}");
        String absolute = registry.execute("read_file", jsonPath(outside));
        String outsideList = registry.execute("list_files", jsonPath(parent));

        assertTrue(traversal.contains("路径超出项目根目录"));
        assertTrue(absolute.contains("路径超出项目根目录"));
        assertTrue(outsideList.contains("路径超出项目根目录"));
        assertFalse(traversal.contains("outside content"));
        assertFalse(absolute.contains("outside content"));
    }

    private static String jsonPath(Path path) throws Exception {
        return new ObjectMapper()
                .createObjectNode()
                .put("path", path.toString())
                .toString();
    }
}
