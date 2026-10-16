package com.agent.history;

/** 工具调用的持久化形式；原始参数始终由清理器替换为空对象。 */
public record StoredToolCall(String id, String type, StoredFunction function) {
    public record StoredFunction(String name, String arguments) {
    }
}
