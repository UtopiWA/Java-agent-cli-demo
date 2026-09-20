package com.agent.llm;

import java.util.List;

/** 表示发送给模型的一条 system、user、assistant 或 tool 消息。 */
public record Message(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId) {

    public Message {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("message role must not be blank");
        }
        if (toolCalls != null) {
            toolCalls = List.copyOf(toolCalls);
        }
        if (toolCalls != null && !toolCalls.isEmpty() && toolCallId != null) {
            throw new IllegalArgumentException(
                    "toolCalls and toolCallId cannot appear in the same message");
        }
    }

    /** 创建由 Agent 注入的系统约束消息。 */
    public static Message system(String content) {
        return new Message("system", content, null, null);
    }

    /** 创建用户输入消息。 */
    public static Message user(String content) {
        return new Message("user", content, null, null);
    }

    /** 创建不包含工具调用的模型回答。 */
    public static Message assistant(String content) {
        return new Message("assistant", content, null, null);
    }

    /** 创建包含一个或多个工具调用意图的模型消息。 */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content, toolCalls, null);
    }

    /** 创建工具 Observation，并通过调用 ID 与 assistant 请求配对。 */
    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, null, toolCallId);
    }
}
