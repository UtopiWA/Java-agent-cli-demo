package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.agent.llm.LLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 将 Lab02 的文件工具适配为模型可描述、可按名称执行的统一接口。 */
public class ToolRegistry {
    static final int MAX_RESULT_CHARS = 12_000; // 工具返回结果的最大长度

    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path projectRoot;

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
            boolean required,
            Long minimum) {
    }

    /** 以当前工作目录作为唯一可访问的项目根目录。 */
    public ToolRegistry() {
        this(Path.of("."));
    }

    /** 注入项目根目录，便于 CLI 固定安全边界，也便于测试隔离文件。 */
    public ToolRegistry(Path projectRoot) {
        this.projectRoot = canonicalRoot(projectRoot);
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
            return limitResult(tool.handler().execute(args));
        } catch (Exception e) {
            return "执行工具失败: " + errorMessage(e);
        }
    }

    /** 根据参数元数据构造 JSON Schema。 */
    private JsonNode createParameters(Param... params) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");

        for (Param param : params) {
            ObjectNode property = properties.putObject(param.name());
            property.put("type", param.type());
            property.put("description", param.description());
            if ("string".equals(param.type())) {
                property.put("minLength", 1);
            }
            if (param.minimum() != null) {
                property.put("minimum", param.minimum());
            }
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
                "递归列出项目内指定目录下的文件，返回相对于项目根目录的路径列表",
                createParameters(
                        new Param("path", "string", "项目根目录内的目录路径", true, null),
                        new Param("max_depth", "integer", "最大递归深度，0 表示仅根节点", false, 0L)),
                args -> {
                    try {
                        Path path = resolveProjectPath(requiredArg(args, "path"));
                        int maxDepth = optionalInt(args, "max_depth", Integer.MAX_VALUE, 0);
                        return "文件列表:\n" + String.join("\n",
                                lister.listFiles(path, maxDepth).stream()
                                        .map(entry -> path.resolve(entry).normalize())
                                        .filter(entry -> !isProtectedEnvFile(entry))
                                        .map(this::projectRelativePath)
                                        .toList());
                    } catch (Exception e) {
                        return "列出文件失败: " + errorMessage(e);
                    }
                }));

        tools.put("read_file", new ToolEntry(
                "read_file",
                "读取项目内指定 UTF-8 文本文件的内容，可按行分页",
                createParameters(
                        new Param("path", "string", "项目根目录内的文件路径", true, null),
                        new Param("offset", "integer", "从第几行开始读取，默认 0", false, 0L),
                        new Param("limit", "integer", "最多读取多少行，默认读取至文件末尾", false, 1L)),
                args -> {
                    try {
                        Path path = resolveProjectPath(requiredArg(args, "path"));
                        if (isProtectedEnvFile(path)) {
                            return "读取文件失败: 拒绝访问敏感配置文件";
                        }
                        if (args.containsKey("offset") || args.containsKey("limit")) {
                            long offset = optionalLong(args, "offset", 0, 0);
                            long limit = optionalLong(args, "limit", Long.MAX_VALUE, 1);
                            return "文件内容:\n" + reader.readFile(path, offset, limit);
                        }
                        return "文件内容:\n" + reader.readFile(path);
                    } catch (Exception e) {
                        return "读取文件失败: " + errorMessage(e);
                    }
                }));

        tools.put("search_code", new ToolEntry(
                "search_code",
                "在项目内指定目录中搜索代码，支持字面量或正则表达式",
                createParameters(
                        new Param("keyword", "string", "搜索关键字或正则表达式", true, null),
                        new Param("path", "string", "项目根目录内的搜索目录", true, null),
                        new Param("regex", "boolean", "是否把 keyword 作为正则表达式，默认 false", false, null)),
                args -> {
                    try {
                        String keyword = requiredArg(args, "keyword");
                        Path path = resolveProjectPath(requiredArg(args, "path"));
                        boolean regex = optionalBoolean(args, "regex", false);
                        List<SearchCodeTool.Match> matches = (regex
                                ? searcher.searchCode(Pattern.compile(keyword), path)
                                : searcher.searchCode(keyword, path)).stream()
                                .filter(match -> !isProtectedEnvFile(
                                        path.resolve(match.file()).normalize()))
                                .toList();
                        StringBuilder result = new StringBuilder("搜索结果:\n");
                        matches.forEach(match -> result
                                .append(projectRelativePath(
                                        path.resolve(match.file()).normalize())).append(':')
                                .append(match.line()).append(": ")
                                .append(match.content()).append('\n'));
                        return result.toString();
                    } catch (Exception e) {
                        return "搜索失败: " + errorMessage(e);
                    }
                }));
    }

    /** 解析并规范化工具路径，同时阻止绝对路径、.. 和符号链接。 */
    private Path resolveProjectPath(String value) throws IOException {
        Path requested = Path.of(value);
        Path candidate = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : projectRoot.resolve(requested).normalize();
        if (!candidate.startsWith(projectRoot)) {
            throw new SecurityException("路径超出项目根目录: " + value);
        }

        Path realPath = candidate.toRealPath();
        if (!realPath.startsWith(projectRoot)) {
            throw new SecurityException("路径通过符号链接逃逸项目根目录: " + value);
        }
        return realPath;
    }

    /** 工具间统一交换项目根目录相对路径，避免后续 read_file 误解子目录结果。 */
    private String projectRelativePath(Path path) {
        return projectRoot.relativize(path).toString().replace('\\', '/');
    }

    private static Path canonicalRoot(Path root) {
        try {
            Path realRoot = Objects.requireNonNull(root, "projectRoot")
                    .toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(realRoot)) {
                throw new IllegalArgumentException("项目根目录不是目录: " + root);
            }
            return realRoot;
        } catch (IOException e) {
            throw new IllegalArgumentException("项目根目录不可访问: " + root, e);
        }
    }

    private static String requiredArg(Map<String, String> args, String name) {
        String value = args.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: " + name);
        }
        return value;
    }

    private static int optionalInt(
            Map<String, String> args, String name, int defaultValue, int minimum) {
        long value = optionalLong(args, name, defaultValue, minimum);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("参数 " + name + " 超出整数范围");
        }
        return (int) value;
    }

    private static long optionalLong(
            Map<String, String> args, String name, long defaultValue, long minimum) {
        String raw = args.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw);
            if (value < minimum) {
                throw new IllegalArgumentException("参数 " + name + " 不能小于 " + minimum);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + name + " 必须是整数");
        }
    }

    private static boolean optionalBoolean(
            Map<String, String> args, String name, boolean defaultValue) {
        String raw = args.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
            throw new IllegalArgumentException("参数 " + name + " 必须是布尔值");
        }
        return Boolean.parseBoolean(raw);
    }

    /** 截断写入模型上下文的 Observation，并明确标记原始长度。 */
    private static String limitResult(String result) {
        if (result == null || result.length() <= MAX_RESULT_CHARS) {
            return result;
        }
        String marker = "\n...[工具结果已截断，原始长度=" + result.length() + "]";
        return result.substring(0, MAX_RESULT_CHARS - marker.length()) + marker;
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
