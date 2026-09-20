package com.agent.llm;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** 模型抽象接口 */
public interface LLMClient {
    record ToolDef(String name, String description, JsonNode parameters) {
    }

    /** 根据完整消息历史和可用工具定义生成下一步模型响应。 */
    LLMResponse chat(List<Message> messages, List<ToolDef> tools) throws IOException;
}
