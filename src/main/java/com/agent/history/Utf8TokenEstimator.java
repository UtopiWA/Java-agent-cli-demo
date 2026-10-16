package com.agent.history;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.agent.llm.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 用序列化后的 UTF-8 字节数作为确定且保守的 Token 上界。 */
public final class Utf8TokenEstimator implements TokenEstimator {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public int estimate(List<Message> messages) {
        try {
            return mapper.writeValueAsString(messages).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法估算历史上下文大小", e);
        }
    }
}
