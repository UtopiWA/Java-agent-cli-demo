package com.agent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;

/** 离线验证 ReAct 分支、历史顺序、调用 ID 和循环上限。 */
class AgentReActTest {
    @Test
    void shouldExitOnFinalAnswer() {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("你好，我是 Agent。", null, 10, 5)));

        String answer = new Agent(fake, new ToolRegistry()).run("hi");

        assertEquals("你好，我是 Agent。", answer);
        assertEquals(1, fake.requests().size());
    }

    @Test
    void shouldLoopOnToolCallAndFeedMatchingObservationBack() {
        ToolCall call = toolCall("call_1", "list_files", "{\"path\":\".\"}");
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call), 100, 20),
                new LLMResponse("已列出文件。", null, 200, 10)));

        String answer = new Agent(fake, new ToolRegistry()).run("列出当前目录");

        assertEquals("已列出文件。", answer);
        assertEquals(2, fake.requests().size());
        List<Message> secondRequest = fake.requests().get(1);
        assertEquals("assistant", secondRequest.get(2).role());
        assertEquals("call_1", secondRequest.get(2).toolCalls().get(0).id());
        assertEquals("tool", secondRequest.get(3).role());
        assertEquals("call_1", secondRequest.get(3).toolCallId());
        assertTrue(secondRequest.get(3).content().startsWith("文件列表:"));
    }

    @Test
    void shouldReturnUnknownToolFailureToModel() {
        ToolCall call = toolCall("call_unknown", "no_such_tool", "{}");
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call), 50, 5),
                new LLMResponse("抱歉，该工具不存在。", null, 80, 10)));

        String answer = new Agent(fake, new ToolRegistry()).run("调用不存在的工具");

        assertTrue(answer.contains("不存在"));
        Message observation = fake.requests().get(1).get(3);
        assertEquals("call_unknown", observation.toolCallId());
        assertEquals("未知工具: no_such_tool", observation.content());
    }

    @Test
    void shouldFeedBackEveryToolCallInOrder() {
        ToolCall first = toolCall("call_a", "no_a", "{}");
        ToolCall second = toolCall("call_b", "no_b", "{}");
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(first, second), 1, 1),
                new LLMResponse("两个调用均已处理。", null, 1, 1)));

        new Agent(fake, new ToolRegistry()).run("执行两个工具");

        List<Message> history = fake.requests().get(1);
        assertEquals("call_a", history.get(3).toolCallId());
        assertEquals("call_b", history.get(4).toolCallId());
    }

    @Test
    void shouldStopAtMaximumIterations() {
        List<LLMResponse> responses = new ArrayList<>();
        for (int index = 0; index < Agent.MAX_ITERATIONS; index++) {
            responses.add(new LLMResponse("", List.of(toolCall(
                    "call_" + index, "no_such_tool", "{}")), 1, 1));
        }
        FakeLLMClient fake = new FakeLLMClient(responses);

        String answer = new Agent(fake, new ToolRegistry()).run("持续调用工具");

        assertEquals("达到最大推理轮数限制（10）", answer);
        assertEquals(Agent.MAX_ITERATIONS, fake.requests().size());
    }

    @Test
    void shouldClearHistoryButKeepSystemMessage() {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("第一轮", null, 1, 1),
                new LLMResponse("第二轮", null, 1, 1)));
        Agent agent = new Agent(fake, new ToolRegistry());
        agent.run("问题一");

        agent.clearHistory();
        agent.run("问题二");

        List<Message> secondConversation = fake.requests().get(1);
        assertEquals(2, secondConversation.size());
        assertEquals("system", secondConversation.get(0).role());
        assertEquals("问题二", secondConversation.get(1).content());
    }

    private static ToolCall toolCall(String id, String name, String arguments) {
        return new ToolCall(id, new ToolCall.Function(name, arguments));
    }
}
