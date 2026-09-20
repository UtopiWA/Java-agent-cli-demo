package com.agent.llm;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 用预设响应替代真实请求，并保存每轮请求供 ReAct 测试断言。 */
public final class FakeLLMClient implements LLMClient {
    private final Deque<LLMResponse> script;
    private final List<List<Message>> requests = new ArrayList<>();

    public FakeLLMClient(List<LLMResponse> responses) {
        this.script = new ArrayDeque<>(responses);
    }

    /** 记录不可变的历史快照后，返回脚本中的下一条响应。 */
    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDef> tools) throws IOException {
        requests.add(List.copyOf(messages));
        if (script.isEmpty()) {
            throw new IOException("FakeLLMClient 没有更多预设响应");
        }
        return script.removeFirst();
    }

    public List<List<Message>> requests() {
        return List.copyOf(requests);
    }
}
