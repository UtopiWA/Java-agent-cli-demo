package com.agent.history;

import java.util.List;

/** 一个可独立加载并继续写入的不可变会话快照。 */
public record ChatSession(
        int schemaVersion,
        String id,
        String title,
        String createdAt,
        String updatedAt,
        int turnCount,
        int systemPromptVersion,
        List<StoredMessage> messages) {

    public ChatSession {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
