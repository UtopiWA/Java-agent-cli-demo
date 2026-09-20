package com.agent.tool;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.agent.llm.LLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 将 Lab02 的文件工具适配为模型可描述、可按名称执行的统一接口。 */
public class ToolRegistry {
    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public record ToolEntry(
            String name,
            String description,
            JsonNode parameters,
            ToolHandler handler) {
    }

    @FunctionalInterface
    public interface ToolHandler {
        String execute(Map<String, String> args);
    }

    private record Param(
            String name,
            String type,
            String description,
            boolean required) {
    }

    /** 创建最小注册表，并注册 Lab02 已实现的三个文件工具。 */
    public ToolRegistry() {
        registerFileTools();
    }

    /** 按注册顺序生成发送给模型的 Tool Calling 定义。 */
    public List<LLMClient.ToolDef> getToolDefinitions() {
        return tools.values().stream()
                .map(tool -> new LLMClient.ToolDef(
                        tool.name(), tool.description(), tool.parameters()))
                .toList();
    }

    /** 解析模型产生的 JSON 参数并执行对应工具；所有失败都转成可回传的 Observation。 */
    public String execute(String toolName, String argumentsJson) {
        ToolEntry tool = tools.get(toolName);
        if (tool == null) {
            return "未知工具: " + toolName;
        }

        try {
            JsonNode node = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            if (!node.isObject()) {
                return "执行工具失败: arguments 必须是 JSON 对象";
            }

            Map<String, String> args = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry ->
                    args.put(entry.getKey(), entry.getValue().asText()));
            return tool.handler().execute(args);
        } catch (Exception e) {
            return "执行工具失败: " + errorMessage(e);
        }
    }

    /** 根据参数元数据构造 JSON Schema。 */
    private JsonNode createParameters(Param... params) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");

        for (Param param : params) {
            ObjectNode property = properties.putObject(param.name());
            property.put("type", param.type());
            property.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }
        return root;
    }

    /** 只做协议适配，实际列举、读取和搜索仍由 Lab02 的实现完成。 */
    private void registerFileTools() {
        ListFilesTool lister = new ListFilesTool();
        ReadFileTool reader = new ReadFileTool();
        SearchCodeTool searcher = new SearchCodeTool();

        tools.put("list_files", new ToolEntry(
                "list_files",
                "递归列出指定目录下的文件，返回相对路径列表",
                createParameters(new Param("path", "string", "目录路径", true)),
                args -> {
                    try {
                        return "文件列表:\n" + String.join("\n",
                                lister.listFiles(Path.of(requiredArg(args, "path"))).stream()
                                        .filter(path -> !isProtectedEnvFile(Path.of(path)))
                                        .toList());
                    } catch (Exception e) {
                        return "列出文件失败: " + errorMessage(e);
                    }
                }));

        tools.put("read_file", new ToolEntry(
                "read_file",
                "读取指定 UTF-8 文本文件的内容",
                createParameters(new Param("path", "string", "文件路径", true)),
                args -> {
                    try {
                        Path path = Path.of(requiredArg(args, "path"));
                        if (isProtectedEnvFile(path)) {
                            return "读取文件失败: 拒绝访问敏感配置文件";
                        }
                        return "文件内容:\n"
                                + reader.readFile(path);
                    } catch (Exception e) {
                        return "读取文件失败: " + errorMessage(e);
                    }
                }));

        tools.put("search_code", new ToolEntry(
                "search_code",
                "在指定目录中搜索包含关键字的代码行",
                createParameters(
                        new Param("keyword", "string", "搜索关键字", true),
                        new Param("path", "string", "搜索目录", true)),
                args -> {
                    try {
                        List<SearchCodeTool.Match> matches = searcher.searchCode(
                                requiredArg(args, "keyword"),
                                Path.of(requiredArg(args, "path"))).stream()
                                .filter(match -> !isProtectedEnvFile(Path.of(match.file())))
                                .toList();
                        StringBuilder result = new StringBuilder("搜索结果:\n");
                        matches.forEach(match -> result
                                .append(match.file()).append(':')
                                .append(match.line()).append(": ")
                                .append(match.content()).append('\n'));
                        return result.toString();
                    } catch (Exception e) {
                        return "搜索失败: " + errorMessage(e);
                    }
                }));
    }

    private static String requiredArg(Map<String, String> args, String name) {
        String value = args.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: " + name);
        }
        return value;
    }

    /** 避免模型阅读密钥配置文件 */
    private static boolean isProtectedEnvFile(Path path) {
        Path fileName = path.normalize().getFileName();
        if (fileName == null) {
            return false;
        }
        String normalizedName = fileName.toString().toLowerCase(Locale.ROOT);
        return normalizedName.equals(".env") || normalizedName.startsWith(".env.");
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
