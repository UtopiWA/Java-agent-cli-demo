package com.agent;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        assertTrue(secondRequest.get(3).content().startsWith("目录树:"));
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
    void shouldCompleteListThenReadThenExplainWorkflow(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Demo.java"), "class Demo {}");
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(toolCall(
                        "call_list", "list_files", "{\"path\":\"src\"}")), 1, 1),
                new LLMResponse("", List.of(toolCall(
                        "call_read", "read_file", "{\"path\":\"src/Demo.java\"}")), 1, 1),
                new LLMResponse("Demo.java 定义了 Demo 类。", null, 1, 1)));

        String answer = new Agent(fake, new ToolRegistry(root))
                .run("列出 Java 文件，读取一个文件并解释职责");

        assertEquals("Demo.java 定义了 Demo 类。", answer);
        assertEquals(3, fake.requests().size());
        List<Message> finalRequest = fake.requests().get(2);
        assertEquals("call_list", finalRequest.get(3).toolCallId());
        assertTrue(finalRequest.get(3).content().contains("src/"));
        assertTrue(finalRequest.get(3).content().contains("Demo.java"));
        assertEquals("call_read", finalRequest.get(5).toolCallId());
        assertTrue(finalRequest.get(5).content().contains("class Demo"));
    }

    @Test
    void shouldStopAtMaximumIterations() {
        List<LLMResponse> responses = new ArrayList<>();
        for (int index = 0; index < Agent.MAX_ITERATIONS; index++) {
            responses.add(new LLMResponse("", List.of(toolCall(
                    "call_" + index, "no_such_tool",
                    "{\"attempt\":" + index + "}")), 1, 1));
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

    @Test
    void shouldRejectBlankOrDuplicateToolCallIds() {
        FakeLLMClient blankId = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(toolCall("", "list_files", "{}")), 1, 1)));
        FakeLLMClient duplicateId = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(
                        toolCall("same", "list_files", "{\"path\":\".\"}"),
                        toolCall("same", "read_file", "{\"path\":\"README.md\"}")), 1, 1)));

        String blankAnswer = new Agent(blankId, new ToolRegistry()).run("blank");
        String duplicateAnswer = new Agent(duplicateId, new ToolRegistry()).run("duplicate");

        assertTrue(blankAnswer.contains("工具调用 ID 不能为空"));
        assertTrue(duplicateAnswer.contains("工具调用 ID 重复"));
    }

    @Test
    void shouldStopOnThirdCanonicalRepeatedCall() {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(toolCall(
                        "repeat_1", "no_such_tool", "{\"a\":1,\"b\":2}")), 1, 1),
                new LLMResponse("", List.of(toolCall(
                        "repeat_2", "no_such_tool", "{\"b\":2,\"a\":1}")), 1, 1),
                new LLMResponse("", List.of(toolCall(
                        "repeat_3", "no_such_tool", "{\"a\":1,\"b\":2}")), 1, 1)));

        String answer = new Agent(fake, new ToolRegistry()).run("重复调用");

        assertTrue(answer.contains("连续 3 次相同工具调用"));
        assertEquals(3, fake.requests().size());
    }

    @Test
    void shouldCompleteReadWriteExecuteAndAnswerWorkflow(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("task.txt"), "请生成一个输出 done 的 Java 程序");
        ObjectMapper mapper = new ObjectMapper();
        String readArgs = mapper.createObjectNode()
                .put("path", "task.txt").toString();
        String source = "class Generated { public static void main(String[] args) { "
                + "System.out.print(\"done\"); } }";
        String writeArgs = mapper.createObjectNode()
                .put("path", "Generated.java")
                .put("content", source)
                .toString();
        var commandArgs = mapper.createObjectNode();
        commandArgs.put("program", "java");
        commandArgs.putArray("arguments").add("Generated.java");
        commandArgs.put("timeout_seconds", 20);

        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(toolCall(
                        "call_read", "read_file", readArgs)), 1, 1),
                new LLMResponse("", List.of(toolCall(
                        "call_write", "write_file", writeArgs)), 1, 1),
                new LLMResponse("", List.of(toolCall(
                        "call_execute", "execute_command", commandArgs.toString())), 1, 1),
                new LLMResponse("已生成并验证，程序输出 done。", null, 1, 1)));

        String answer = new Agent(fake, new ToolRegistry(root)).run("完成 task.txt 中的任务");

        assertEquals("已生成并验证，程序输出 done。", answer);
        assertEquals(source, Files.readString(root.resolve("Generated.java")));
        List<Message> finalRequest = fake.requests().get(3);
        assertTrue(finalRequest.get(3).content().contains("请生成一个输出 done"));
        assertTrue(finalRequest.get(5).content().contains("写入文件成功"));
        assertTrue(finalRequest.get(7).content().contains("退出码: 0"));
        assertTrue(finalRequest.get(7).content().contains("done"));
    }

    private static ToolCall toolCall(String id, String name, String arguments) {
        return new ToolCall(id, new ToolCall.Function(name, arguments));
    }
}
