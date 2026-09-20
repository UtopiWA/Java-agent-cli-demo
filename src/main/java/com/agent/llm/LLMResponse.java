package com.agent.llm;

import java.util.List;

/** 封装一次模型响应中的文本、工具调用及 Token 用量。 */
public record LLMResponse(
        String content,
        List<ToolCall> toolCalls,
        int promptTokens,
        int completionTokens) {

    public LLMResponse {
        if (toolCalls != null) {
            toolCalls = List.copyOf(toolCalls); // 创建一个不可变的列表
        }
    }

    /** ReAct 循环以此作为“执行工具还是返回答案”的唯一分支条件。 */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
