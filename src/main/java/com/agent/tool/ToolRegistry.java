package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.agent.llm.LLMClient;
import com.agent.task.TaskExecutionContext;
import com.agent.task.TaskModels.TestAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 将 Lab02 的文件工具适配为模型可描述、可按名称执行的统一接口。 */
public class ToolRegistry {
    static final int MAX_RESULT_CHARS = 14_000; // 包含分页正文和元数据

    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path projectRoot;
    private final TaskExecutionContext taskContext;
    private final Map<String, String> readCache = new LinkedHashMap<>();

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
            Integer minLength,
            Long minimum,
            Long maximum,
            String itemType) {
        private Param(
                String name,
                String type,
                String description,
                boolean required,
                Long minimum) {
            this(name, type, description, required,
                    "string".equals(type) ? 1 : null,
                    minimum, null, null);
        }
    }

    /** 以当前工作目录作为唯一可访问的项目根目录。 */
    public ToolRegistry() {
        this(Path.of("."));
    }

    /** 注入项目根目录，便于 CLI 固定安全边界，也便于测试隔离文件。 */
    public ToolRegistry(Path projectRoot) {
        this(projectRoot, null);
    }

    /** 创建受当前测试任务和轮次状态约束的垂直工具注册表。 */
    public ToolRegistry(TaskExecutionContext taskContext) {
        this(Objects.requireNonNull(taskContext, "taskContext").projectRoot(), taskContext);
    }

    private ToolRegistry(Path projectRoot, TaskExecutionContext taskContext) {
        this.projectRoot = canonicalRoot(projectRoot);
        this.taskContext = taskContext;
        registerFileTools();
        registerMutationTools();
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
            if (node == null || !node.isObject()) {
                return "执行工具失败: arguments 必须是 JSON 对象";
            }
            ObjectNode arguments = (ObjectNode) node;
            validateArguments(arguments, tool.parameters());
            Map<String, String> adaptedArguments = new LinkedHashMap<>();
            arguments.fields().forEachRemaining(entry -> adaptedArguments.put(
                    entry.getKey(),
                    entry.getValue().isContainerNode()
                            ? entry.getValue().toString()
                            : entry.getValue().asText()));
            return limitResult(tool.handler().execute(adaptedArguments));
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
            if (param.minLength() != null) {
                property.put("minLength", param.minLength());
            }
            if (param.minimum() != null) {
                property.put("minimum", param.minimum());
            }
            if (param.maximum() != null) {
                property.put("maximum", param.maximum());
            }
            if (param.itemType() != null) {
                property.putObject("items").put("type", param.itemType());
            }
            if (param.required()) {
                required.add(param.name());
            }
        }
        return root;
    }

    /** 在真实执行前按已发送给模型的 JSON Schema 做同等的基础校验。 */
    private static void validateArguments(ObjectNode arguments, JsonNode schema) {
        JsonNode properties = schema.path("properties");
        Set<String> allowed = new HashSet<>();
        properties.fieldNames().forEachRemaining(allowed::add);
        arguments.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("未知参数: " + name);
            }
        });

        for (JsonNode required : schema.path("required")) {
            String name = required.asText();
            if (!arguments.has(name) || arguments.get(name).isNull()) {
                throw new IllegalArgumentException("缺少必填参数: " + name);
            }
        }

        arguments.fields().forEachRemaining(entry ->
                validateValue(entry.getKey(), entry.getValue(), properties.path(entry.getKey())));
    }

    private static void validateValue(String name, JsonNode value, JsonNode definition) {
        String type = definition.path("type").asText();
        boolean valid = switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("参数 " + name + " 类型必须为 " + type);
        }
        if (value.isIntegralNumber() && !value.canConvertToLong()) {
            throw new IllegalArgumentException("参数 " + name + " 超出整数范围");
        }

        if (value.isTextual()
                && value.textValue().length() < definition.path("minLength").asInt(0)) {
            throw new IllegalArgumentException("参数 " + name + " 不能为空");
        }
        if (value.isIntegralNumber()) {
            long number = value.longValue();
            if (definition.has("minimum") && number < definition.path("minimum").asLong()) {
                throw new IllegalArgumentException(
                        "参数 " + name + " 不能小于 " + definition.path("minimum").asLong());
            }
            if (definition.has("maximum") && number > definition.path("maximum").asLong()) {
                throw new IllegalArgumentException(
                        "参数 " + name + " 不能大于 " + definition.path("maximum").asLong());
            }
        }
        if (value.isArray()) {
            String itemType = definition.path("items").path("type").asText();
            for (JsonNode item : value) {
                if ("string".equals(itemType) && !item.isTextual()) {
                    throw new IllegalArgumentException("参数 " + name + " 的元素必须为 string");
                }
            }
        }
    }

    /** 注册面向测试生成任务的三个有界只读工具。 */
    private void registerFileTools() {
        ListFilesTool lister = new ListFilesTool();
        ReadFileTool reader = new ReadFileTool();
        SearchCodeTool searcher = new SearchCodeTool();

        tools.put("list_files", new ToolEntry(
                "list_files",
                "分页查看项目目录树；默认从项目根目录向下查看两层",
                createParameters(
                        new Param("path", "string", "项目根目录内的目录路径，默认 .", false, null),
                        new Param("max_depth", "integer", "最大递归深度，默认 2，范围 0 到 6", false,
                                null, 0L, 6L, null),
                        new Param("offset", "integer", "条目偏移量，默认 0", false, 0L),
                        new Param("limit", "integer", "本页最多返回的条目数，默认 100，范围 1 到 300", false,
                                null, 1L, 300L, null)),
                args -> {
                    try {
                        String requested = optionalText(args, "path", ".");
                        Path path = resolveProjectPath(requested);
                        if (isProtectedPath(path)) {
                            return "列出文件失败: 拒绝访问敏感路径";
                        }
                        if (isIgnoredPath(path, true)) {
                            return "列出文件失败: 路径命中 .agentignore";
                        }
                        int maxDepth = optionalInt(args, "max_depth", 2, 0);
                        int offset = optionalInt(args, "offset", 0, 0);
                        int limit = optionalInt(args, "limit", 100, 1);
                        ListFilesTool.TreePage page = lister.treePage(
                                projectRoot, path, maxDepth, offset, limit);
                        return "目录树:\n" + page.tree()
                                + "\n元数据:"
                                + "\npath: " + (requested.isBlank() ? "." : requested)
                                + "\nmax_depth: " + maxDepth
                                + "\nreturned_entries: " + page.returnedEntries()
                                + "\nhas_more: " + page.hasMore()
                                + "\nnext_offset: " + nullable(page.nextOffset())
                                + "\nscan_limited: " + page.scanLimited()
                                + "\nvisited_entries: " + page.visitedEntries();
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
                        new Param("limit", "integer", "最多读取多少行，默认 200，最大 400", false,
                                null, 1L, 400L, null)),
                args -> {
                    try {
                        Path path = resolveProjectPath(requiredText(args, "path"));
                        if (isProtectedPath(path)) {
                            return isProtectedEnvFile(path)
                                    ? "读取文件失败: 拒绝访问敏感配置文件"
                                    : "读取文件失败: 拒绝访问敏感路径";
                        }
                        if (isIgnoredPath(path, false)) {
                            return "读取文件失败: 路径命中 .agentignore";
                        }
                        int offset = optionalInt(args, "offset", 0, 0);
                        int limit = optionalInt(args, "limit", 200, 1);
                        ReadFileTool.ReadPage page = reader.readPage(path, offset, limit);
                        if (taskContext != null) {
                            taskContext.noteRead(path);
                        }
                        if ("LINE_TOO_LONG".equals(page.status())) {
                            return "读取文件失败: LINE_TOO_LONG"
                                    + "\npath: " + projectRelativePath(path)
                                    + "\nline: " + page.oversizedLine()
                                    + "\nline_length: " + page.oversizedLineLength()
                                    + "\ntotal_lines: " + page.totalLines()
                                    + "\nhas_more: " + page.hasMore()
                                    + "\nnext_offset: " + nullable(page.nextOffset())
                                    + "\nfingerprint: " + page.fingerprint();
                        }
                        String cacheKey = projectRelativePath(path) + ':' + offset + ':' + limit
                                + ':' + page.fingerprint();
                        if (taskContext != null && readCache.containsKey(cacheKey)) {
                            return "文件区间缓存命中"
                                    + "\ncache_id: " + readCache.get(cacheKey)
                                    + "\npath: " + projectRelativePath(path)
                                    + "\noffset: " + offset
                                    + "\nlimit: " + limit
                                    + "\nfingerprint: " + page.fingerprint();
                        }
                        readCache.put(cacheKey, Integer.toHexString(cacheKey.hashCode()));
                        return "文件内容:\n" + page.content()
                                + "\n元数据:"
                                + "\npath: " + projectRelativePath(path)
                                + "\ntotal_lines: " + page.totalLines()
                                + "\nreturned_range: " + page.startLine() + "-" + page.endLine()
                                + "\nhas_more: " + page.hasMore()
                                + "\nnext_offset: " + nullable(page.nextOffset())
                                + "\nfingerprint: " + page.fingerprint();
                    } catch (Exception e) {
                        return "读取文件失败: " + errorMessage(e);
                    }
                }));

        tools.put("search_code", new ToolEntry(
                "search_code",
                "在项目内的 Java 文件中执行大小写敏感的字面量搜索，并可返回少量上下文",
                createParameters(
                        new Param("keyword", "string", "普通文本关键字，长度 1 到 200", true, null),
                        new Param("path", "string", "项目根目录内的搜索目录，默认 src", false, null),
                        new Param("offset", "integer", "匹配记录偏移量，默认 0", false, 0L),
                        new Param("limit", "integer", "本页最多返回的匹配数，默认 20，最大 100", false,
                                null, 1L, 100L, null),
                        new Param("context_lines", "integer", "每条匹配前后附带的行数，默认 0，最大 3", false,
                                null, 0L, 3L, null)),
                args -> {
                    try {
                        String keyword = requiredText(args, "keyword");
                        if (keyword.length() > 200) {
                            throw new IllegalArgumentException("参数 keyword 长度不能超过 200");
                        }
                        Path path = resolveProjectPath(optionalText(args, "path", "src"));
                        if (isProtectedPath(path)) {
                            return "搜索失败: 拒绝访问敏感路径";
                        }
                        if (isIgnoredPath(path, true)) {
                            return "搜索失败: 路径命中 .agentignore";
                        }
                        int offset = optionalInt(args, "offset", 0, 0);
                        int limit = optionalInt(args, "limit", 20, 1);
                        int contextLines = optionalInt(args, "context_lines", 0, 0);
                        SearchCodeTool.SearchPage page = searcher.searchPage(
                                projectRoot, path, keyword, offset, limit, contextLines);
                        return "搜索结果:\n" + page.content()
                                + "\n元数据:"
                                + "\nreturned_matches: " + page.returnedMatches()
                                + "\nhas_more: " + page.hasMore()
                                + "\nnext_offset: " + nullable(page.nextOffset())
                                + "\nscan_limited: " + page.scanLimited()
                                + "\nscan_limit_reason: " + nullable(page.scanLimitReason())
                                + "\nscanned_files: " + page.scannedFiles()
                                + "\nscanned_bytes: " + page.scannedBytes();
                    } catch (Exception e) {
                        return "搜索失败: " + errorMessage(e);
                    }
                }));
    }

    /** 注册能够改变项目状态的工具，并在适配层统一施加路径和参数边界。 */
    private void registerMutationTools() {
        if (taskContext != null) {
            registerTaskMutationTools();
            return;
        }
        WriteFileTool writer = new WriteFileTool();
        ExecuteCommandTool commandExecutor = new ExecuteCommandTool();

        tools.put("write_file", new ToolEntry(
                "write_file",
                "以 UTF-8 新建或完整覆盖项目内的文本文件，父目录不存在时自动创建",
                createParameters(
                        new Param("path", "string", "项目根目录内的目标文件路径", true, null),
                        new Param("content", "string", "要写入的完整文件内容", true,
                                0, null, null, null)),
                args -> {
                    try {
                        Path path = resolveWritableProjectPath(requiredText(args, "path"));
                        if (isProtectedPath(path)) {
                            return "写入文件失败: 拒绝修改敏感路径";
                        }
                        if (isIgnoredPath(path, false)) {
                            return "写入文件失败: 路径命中 .agentignore";
                        }
                        WriteFileTool.WriteResult result = writer.writeFile(
                                path, requiredTextAllowEmpty(args, "content"));
                        return "写入文件成功: " + projectRelativePath(path)
                                + "\n操作: " + (result.created() ? "新建" : "覆盖")
                                + "\n写入字节数: " + result.bytesWritten();
                    } catch (Exception e) {
                        return "写入文件失败: " + errorMessage(e);
                    }
                }));

        tools.put("execute_command", new ToolEntry(
                "execute_command",
                "在项目内参数化执行受允许列表约束的开发命令，不接受 Shell 命令字符串",
                createParameters(
                        new Param("program", "string", "可执行程序名，例如 java、javac 或 mvn", true, null),
                        new Param("arguments", "array", "按顺序传递给程序的字符串参数", false,
                                null, null, null, "string"),
                        new Param("working_directory", "string", "项目根目录内的工作目录，默认 .", false, null),
                        new Param("timeout_seconds", "integer", "超时秒数，默认 30，范围 1 到 120", false,
                                null, 1L, 120L, null)),
                args -> {
                    try {
                        String workingDirectory = optionalText(args, "working_directory", ".");
                        Path directory = resolveProjectPath(workingDirectory);
                        if (!Files.isDirectory(directory)) {
                            return "执行命令失败: 工作目录不是目录";
                        }
                        int timeout = optionalInt(args, "timeout_seconds", 30, 1);
                        ExecuteCommandTool.CommandResult result = commandExecutor.execute(
                                directory,
                                requiredText(args, "program"),
                                optionalStringList(args, "arguments"),
                                Duration.ofSeconds(timeout));
                        return formatCommandResult(result);
                    } catch (Exception e) {
                        return "执行命令失败: " + errorMessage(e);
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

    /** 为可能尚不存在的写入目标验证词法边界和每个已存在路径分量。 */
    private Path resolveWritableProjectPath(String value) throws IOException {
        Path requested = Path.of(value);
        Path candidate = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : projectRoot.resolve(requested).normalize();
        if (!candidate.startsWith(projectRoot) || candidate.equals(projectRoot)) {
            throw new SecurityException("路径超出项目根目录或指向根目录: " + value);
        }

        Path current = projectRoot;
        for (Path part : projectRoot.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new SecurityException("路径包含符号链接: " + value);
                }
                Path realPath = current.toRealPath();
                if (!realPath.startsWith(projectRoot)) {
                    throw new SecurityException("路径逃逸项目根目录: " + value);
                }
            }
        }
        return candidate;
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

    private static String requiredText(Map<String, String> args, String name) {
        String value = requiredTextAllowEmpty(args, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: " + name);
        }
        return value;
    }

    private static String requiredTextAllowEmpty(Map<String, String> args, String name) {
        String value = args.get(name);
        if (value == null) {
            throw new IllegalArgumentException("缺少必填参数: " + name);
        }
        return value;
    }

    private static String optionalText(
            Map<String, String> args, String name, String defaultValue) {
        String value = args.get(name);
        return value == null ? defaultValue : value;
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
        if (raw == null) {
            return defaultValue;
        }
        long value = Long.parseLong(raw);
        if (value < minimum) {
            throw new IllegalArgumentException("参数 " + name + " 不能小于 " + minimum);
        }
        return value;
    }

    private static boolean optionalBoolean(
            Map<String, String> args, String name, boolean defaultValue) {
        String raw = args.get(name);
        return raw == null ? defaultValue : Boolean.parseBoolean(raw);
    }

    private List<String> optionalStringList(Map<String, String> args, String name)
            throws IOException {
        String raw = args.get(name);
        if (raw == null) {
            return List.of();
        }
        JsonNode values = mapper.readTree(raw);
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.textValue()));
        return List.copyOf(result);
    }

    /** 截断写入模型上下文的 Observation，并明确标记原始长度。 */
    private static String limitResult(String result) {
        if (result == null || result.length() <= MAX_RESULT_CHARS) {
            return result;
        }
        String marker = "\n...[工具结果已截断，原始长度=" + result.length() + "]";
        return result.substring(0, MAX_RESULT_CHARS - marker.length()) + marker;
    }

    /** 保护规则只按项目内相对路径判断，避免项目父目录名称造成误判。 */
    private boolean isProtectedPath(Path path) {
        return IgnoreRules.isAlwaysExcluded(projectRoot, path);
    }

    private void registerTaskMutationTools() {
        tools.put("write_file", new ToolEntry(
                "write_file",
                "提交当前轮次的完整 JUnit 5 测试候选；写入路径由任务固定，不能指定 path。"
                        + "第一版至少需要一个普通 @Test 方法；不要用 @ParameterizedTest、"
                        + "@RepeatedTest、@TestFactory 或 @TestTemplate 代替 @Test",
                createParameters(new Param("content", "string",
                        "非空的完整 Java 测试源码，最大 24 KiB；至少包含一个普通 @Test 方法",
                        true, 1, null, null, null)),
                args -> taskContext.writeCandidate(requiredText(args, "content"))));

        tools.put("execute_command", new ToolEntry(
                "execute_command",
                "执行受状态机约束的 Maven 测试动作，不接受命令、参数、工作目录或测试类名",
                createParameters(new Param("action", "string",
                        "仅允许 baseline_test、target_test、regression_test", true, null)),
                args -> {
                    try {
                        TestAction action = TestAction.valueOf(requiredText(args, "action"));
                        return taskContext.execute(action);
                    } catch (IllegalArgumentException e) {
                        return "执行测试失败: action 仅允许 baseline_test、target_test、regression_test";
                    }
                }));
    }

    private boolean isIgnoredPath(Path path, boolean directory) throws IOException {
        return IgnoreRules.load(projectRoot).isIgnored(projectRoot, path, directory);
    }

    private static boolean isProtectedEnvFile(Path path) {
        Path fileName = path.normalize().getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return name.equals(".env") || name.startsWith(".env.");
    }

    private static String formatCommandResult(ExecuteCommandTool.CommandResult result) {
        return "命令执行结果:"
                + "\n退出码: " + result.exitCode()
                + "\n超时: " + result.timedOut()
                + "\n标准输出:\n" + result.standardOutput()
                + "\n标准错误:\n" + result.standardError();
    }

    private static String nullable(Object value) {
        return value == null ? "null" : value.toString();
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
