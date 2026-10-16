package com.agent.history;

import java.util.List;

import com.agent.llm.Message;

/** 可替换的历史上下文 Token 预算估算器。 */
@FunctionalInterface
public interface TokenEstimator {
    int estimate(List<Message> messages);
}
