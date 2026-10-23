package com.agent;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

import com.agent.history.HistoryContextBuilder;
import com.agent.history.Utf8TokenEstimator;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.task.TaskExecutionContext;
import com.agent.task.TaskModels.RoundType;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 面向单个测试维护轮次的统一 Tool Call/Observation Agent 循环。 */
public final class TestTaskAgent {
    private static final int MAX_USER_CHARS = 8_000;
    private static final int MAX_CALLS_PER_RESPONSE = 8;
    private static final int INPUT_BUDGET = 24_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LLMClient llm;
    private final ToolRegistry registry;
    private final TaskExecutionContext context;
    private final HistoryContextBuilder contextBuilder;
    private final PrintWriter output;

    public TestTaskAgent(
            LLMClient llm,
            ToolRegistry registry,
            TaskExecutionContext context,
            PrintWriter output) {
        this.llm = llm;
        this.registry = registry;
        this.context = context;
        this.output = output;
        int toolBytes = registry.getToolDefinitions().stream()
                .mapToInt(tool -> tool.name().length() + tool.description().length()
                        + tool.parameters().toString().length())
                .sum();
        this.contextBuilder = new HistoryContextBuilder(
                new Utf8TokenEstimator(), Math.max(8_000, INPUT_BUDGET - toolBytes));
    }

    public String run(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "用户要求不能为空";
        }
        if (userInput.length() > MAX_USER_CHARS) {
            return "用户输入超过 8,000 个字符，请把详细内容放入项目文件后再引用。";
        }

        List<Message> history = new ArrayList<>();
        history.add(Message.system(systemPrompt()));
        history.add(Message.user(userInput));
        SafetyGuard safety = new SafetyGuard();
        String lastAnswer = "";

        try {
            while (context.beginModelCall()) {
                output.println("[round] task=" + TaskManagerId.shortId(context.task().id())
                        + " round=" + context.round().number()
                        + " type=" + context.round().type()
                        + " stage=" + context.round().status()
                        + " model=" + context.round().modelCallCount()
                        + '/' + TaskExecutionContext.MAX_MODEL_CALLS);
                LLMResponse response;
                try {
                    response = llm.chat(contextBuilder.build(history), registry.getToolDefinitions());
                } catch (Exception e) {
                    context.finishAtLimit("LLM 调用失败: " + safeMessage(e));
                    return context.finalReport("LLM 调用失败: " + safeMessage(e));
                }
                if (response == null) {
                    context.finishAtLimit("LLM 响应为空");
                    return context.finalReport("LLM 响应为空");
                }
                context.recordTokenUsage(response.promptTokens(), response.completionTokens());
                output.println("[model] prompt_tokens=" + response.promptTokens()
                        + " completion_tokens=" + response.completionTokens());

                if (response.hasToolCalls()) {
                    List<ToolCall> calls = response.toolCalls();
                    if (calls.size() > MAX_CALLS_PER_RESPONSE) {
                        context.finishAtLimit("单次模型响应的 Tool Call 超过 8 个");
                        return context.finalReport("工具调用被拒绝：单次最多 8 个。");
                    }
                    String invalid = safety.validateCalls(calls);
                    if (invalid != null) {
                        context.finishAtLimit("工具调用协议无效: " + invalid);
                        return context.finalReport("工具调用协议无效: " + invalid);
                    }
                    history.add(Message.assistant(response.content(), calls));
                    for (int index = 0; index < calls.size(); index++) {
                        ToolCall call = calls.get(index);
                        if (context.isTerminal()) {
                            pairSkipped(history, calls, index, "轮次已经终止");
                            break;
                        }
                        if (!context.beginToolCall()) {
                            pairSkipped(history, calls, index, "达到每轮 30 次工具调用上限");
                            context.finishAtLimit("达到每轮 30 次工具调用上限");
                            break;
                        }
                        String repeated = safety.checkRepeatedCall(call);
                        if (repeated != null) {
                            String blocked = "工具调用被拒绝: " + repeated;
                            history.add(Message.tool(call.id(), blocked));
                            context.recordToolEvent(call.name(), summarizeArguments(call), blocked, 0, true);
                            pairSkipped(history, calls, index + 1, "检测到重复工具调用");
                            context.finishAtLimit(repeated);
                            break;
                        }

                        String parameters = summarizeArguments(call);
                        output.println("[tool] " + call.name() + ' ' + parameters);
                        long started = System.nanoTime();
                        String result = registry.execute(call.name(), call.arguments());
                        long elapsed = (System.nanoTime() - started) / 1_000_000;
                        history.add(Message.tool(call.id(), result));
                        context.recordToolEvent(call.name(), parameters, result, elapsed, false);
                        output.println("[result] " + resultStatus(result)
                                + " chars=" + result.length() + " elapsed_ms=" + elapsed);
                    }
                    if (context.isTerminal()) {
                        return context.finalReport(response.content());
                    }
                    continue;
                }

                lastAnswer = response.content() == null ? "" : response.content();
                history.add(Message.assistant(lastAnswer));
                if (context.round().type() == RoundType.ANALYSIS
                        && context.completeAnalysis(lastAnswer)) {
                    return context.finalReport(lastAnswer);
                }
                if (context.isTerminal()) {
                    return context.finalReport(lastAnswer);
                }
                history.add(Message.user("[系统完成门槛] " + context.completionRequirement()
                        + " 这不是新的用户要求，请继续当前轮次。"));
            }
            context.finishAtLimit("达到模型响应次数上限");
            return context.finalReport(lastAnswer.isBlank() ? "本轮未完成。" : lastAnswer);
        } catch (Exception e) {
            context.abort("Agent 异常: " + safeMessage(e));
            return context.finalReport("Agent 执行异常: " + safeMessage(e));
        }
    }

    private String systemPrompt() {
        return """
                你是 Java 单元测试生成与维护 Agent，只处理当前固定目标类的 JUnit 5 测试。
                你必须通过统一工具调用收集证据；不得假装读取、写入或执行过没有真实工具结果的操作。
                只允许修改当前任务推导出的测试文件，不得修改生产代码、pom.xml 或其他测试。
                list_files 用于浅层查看结构；read_file 用 offset/limit 分页；search_code 只做 Java 字面量搜索。
                write_file 只接收完整 content，路径由任务固定；execute_command 只接收 action。
                CHANGE 顺序：读取目标和必要上下文 -> baseline_test -> write_file -> target_test -> regression_test。
                VERIFY 顺序：读取必要上下文 -> target_test -> regression_test。ANALYSIS 不写文件、不执行 Maven。
                测试应验证公开可观察行为，包含有效断言，不复制生产算法，不新增依赖。
                第一版测试方法必须使用普通 org.junit.jupiter.api.Test；允许同时使用 @DisplayName
                等辅助注解且注解顺序不限，但不要用 @ParameterizedTest、@RepeatedTest、
                @TestFactory 或 @TestTemplate 代替普通 @Test。
                工具失败时根据有界诊断修正；每轮最多三个不同候选，不要重复提交相同内容。
                不得在任务内切换目标类。最终回答必须区分真实验证结果和人工复核事项。

                当前任务：
                task_id=%s
                target_class=%s
                target_source=%s
                test_file=%s
                round=%d
                round_type=%s
                round_status=%s
                request=%s
                recent_outcome=%s
                latest_attempt_failure=%s
                persistent_constraints=%s
                committed_test_hash=%s
                """.formatted(
                context.task().id(), context.task().targetClass(), context.task().targetSource(),
                context.task().testFile(), context.round().number(), context.round().type(),
                context.round().status(), context.round().requestSummary(),
                context.round().outcomeSummary().isBlank() ? "无" : context.round().outcomeSummary(),
                context.round().attempts().isEmpty() ? "无"
                        : context.round().attempts().get(context.round().attempts().size() - 1)
                                .failureSummary(),
                context.task().persistentConstraints().isBlank()
                        ? "无" : context.task().persistentConstraints(),
                context.task().committedTestHash() == null
                        ? "无" : context.task().committedTestHash());
    }

    private void pairSkipped(
            List<Message> history, List<ToolCall> calls, int start, String reason) throws Exception {
        for (int index = start; index < calls.size(); index++) {
            history.add(Message.tool(calls.get(index).id(), "工具调用未执行: " + reason));
            context.recordSkippedToolCall(calls.get(index).name(),
                    summarizeArguments(calls.get(index)), reason);
        }
    }

    static String summarizeArguments(ToolCall call) {
        try {
            JsonNode root = MAPPER.readTree(call.arguments() == null ? "{}" : call.arguments());
            if ("write_file".equals(call.name())) {
                return summarizeWriteArguments(root);
            }
            if (!root.isObject()) {
                return "arguments=<invalid>";
            }
            List<String> values = new ArrayList<>();
            root.fields().forEachRemaining(entry -> {
                if ("content".equals(entry.getKey())) {
                    String content = entry.getValue().asText("");
                    values.add("content_chars=" + content.length());
                } else {
                    String value = entry.getValue().isTextual()
                            ? entry.getValue().asText() : entry.getValue().toString();
                    values.add(entry.getKey() + '=' + abbreviate(value, 120));
                }
            });
            return String.join(" ", values);
        } catch (Exception e) {
            return "write_file".equals(call.name())
                    ? "source=model_tool_call json_valid=false content_present=unknown"
                    : "arguments=<invalid-json>";
        }
    }

    /** 只保存模型工具参数的结构和内容指纹，不把测试源码写入任务历史。 */
    private static String summarizeWriteArguments(JsonNode root) throws Exception {
        if (root == null) {
            return "source=model_tool_call json_valid=false arguments_object=false"
                    + " content_present=false";
        }
        if (!root.isObject()) {
            return "source=model_tool_call json_valid=true arguments_object=false"
                    + " content_present=false";
        }
        JsonNode content = root.get("content");
        if (content == null || content.isNull()) {
            return "source=model_tool_call json_valid=true arguments_object=true"
                    + " content_present=false";
        }
        if (!content.isTextual()) {
            return "source=model_tool_call json_valid=true arguments_object=true"
                    + " content_present=true content_type=" + content.getNodeType().name().toLowerCase();
        }
        String value = content.textValue();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        return "source=model_tool_call json_valid=true arguments_object=true"
                + " content_present=true content_type=string"
                + " content_chars=" + value.length()
                + " content_bytes=" + bytes.length
                + " content_sha256=" + hash;
    }

    static String resultStatus(String result) {
        if (result == null || result.isBlank()) {
            return "failure";
        }
        String executionStatus = result.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("status:"))
                .map(line -> line.substring("status:".length()).strip())
                .findFirst()
                .orElse(null);
        if (executionStatus != null) {
            return "PASSED".equalsIgnoreCase(executionStatus) ? "success" : "failure";
        }

        String firstLine = result.lines().findFirst().orElse("").strip();
        String lowerFirstLine = firstLine.toLowerCase();
        if (firstLine.contains("失败") || firstLine.contains("拒绝")
                || firstLine.startsWith("未知工具:")
                || lowerFirstLine.equals("error") || lowerFirstLine.startsWith("error:")
                || lowerFirstLine.startsWith("error ")) {
            return "failure";
        }
        return "success";
    }

    private static String abbreviate(String value, int maximum) {
        String oneLine = value.replaceAll("\\s+", " ");
        return oneLine.length() <= maximum ? oneLine : oneLine.substring(0, maximum) + "...";
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** 避免让任务包为了终端显示依赖 TaskManager 的包级方法。 */
    private static final class TaskManagerId {
        private static String shortId(String id) {
            return id.substring(0, Math.min(8, id.length()));
        }
    }
}
