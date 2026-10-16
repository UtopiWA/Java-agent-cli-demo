package com.agent.history;

import java.util.List;

/** 带顺序和时间戳的磁盘消息模型。 */
public record StoredMessage(
        int sequence,
        String role,
        String content,
        List<StoredToolCall> toolCalls,
        String toolCallId,
        ToolEvent toolEvent,
        String timestamp) {

    public StoredMessage {
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
    }
}
