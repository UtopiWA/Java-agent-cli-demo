package com.agent.llm;

/** 模型产生的函数调用意图；arguments 保存原始 JSON 字符串。 */
public record ToolCall(String id, Function function) {
    public record Function(String name, String arguments) {
    }

    public String name() {
        return function == null ? null : function.name();
    }

    public String arguments() {
        return function == null ? null : function.arguments();
    }
}
