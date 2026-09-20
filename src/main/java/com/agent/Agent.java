package com.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;

/** 兼容 Lab01 的响应逻辑，并在配置 LLM 后提供 Lab03 的 ReAct Agent。 */
public class Agent {
    static final int MAX_ITERATIONS = 10;
    private static final String SYSTEM_PROMPT = """
            你是一个 Java 编程助手 Agent，可以读取和搜索用户的项目代码。

            可用工具：
            1. list_files：列出目录下的文件；
            2. read_file：读取文件内容；
            3. search_code：搜索代码。

            需要了解项目代码时，请调用工具。
            获得工具结果后，再基于结果继续完成任务。
            请用中文回复用户。
            """;

    private final LLMClient llm;
    private final ToolRegistry registry;
    private final List<Message> history = new ArrayList<>();

    /** 保留 Lab01 的无参用法；该实例只支持 respond，不支持 run。 */
    public Agent() {
        this.llm = null;
        this.registry = null;
    }

    /** 创建具备模型决策、工具执行和消息历史能力的 Agent。 */
    public Agent(LLMClient llm, ToolRegistry registry) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.registry = Objects.requireNonNull(registry, "registry");
        resetHistory();
    }

    /** 执行一次完整 ReAct 循环，直到模型给出最终回答、调用失败或达到轮数上限。 */
    public String run(String userInput) {
        ensureReactConfigured();
        history.add(Message.user(Objects.requireNonNull(userInput, "userInput")));

        for (int round = 1; round <= MAX_ITERATIONS; round++) {
            System.out.println("\n思考中……第 " + round + " 轮");

            LLMResponse response;
            try {
                response = llm.chat(history, registry.getToolDefinitions());
            } catch (Exception e) {
                return "LLM 调用失败: " + safeMessage(e);
            }

            System.out.printf("Token：输入=%d，输出=%d%n",
                    response.promptTokens(), response.completionTokens());

            if (response.hasToolCalls()) {
                // 先保存模型的完整调用意图，再按顺序回传每个调用的 Observation
                history.add(Message.assistant(response.content(), response.toolCalls()));
                for (ToolCall call : response.toolCalls()) {
                    System.out.println("执行工具: " + call.name());
                    System.out.println("参数: " + call.arguments());

                    String result = registry.execute(call.name(), call.arguments());
                    System.out.println("结果: " + truncate(result, 200));
                    history.add(Message.tool(call.id(), result));
                }
                continue;
            }

            history.add(Message.assistant(response.content()));
            return response.content();
        }

        return "达到最大推理轮数限制（" + MAX_ITERATIONS + "）";
    }

    /** 清空对话记忆，但保留定义 Agent 身份和工具规则的 system 消息。 */
    public void clearHistory() {
        ensureReactConfigured();
        resetHistory();
    }

    private void resetHistory() {
        history.clear();
        history.add(Message.system(SYSTEM_PROMPT));
    }

    private void ensureReactConfigured() {
        if (llm == null || registry == null) {
            throw new IllegalStateException("此 Agent 未配置 LLMClient 和 ToolRegistry");
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /** Lab01 的简单响应入口，继续支持原有调用方式。 */
    public String respond(String input) {
        return respond(input, false);
    }

    /** Lab01 的简单响应入口，可选择将结果转换为大写。 */
    public String respond(String input, boolean upper) {
        String response;
        if (input == null || input.isBlank()) {
            response = "Hello, Agent!";
        } else if ("hello".equalsIgnoreCase(input.trim())) {
            response = "Hello, Agent!";
        } else {
            response = "I heard: " + input;
        }

        return upper ? response.toUpperCase(Locale.ROOT) : response;
    }
}
