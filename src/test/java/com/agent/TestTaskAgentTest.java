package com.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.ToolCall;
import com.agent.task.TaskExecutionContext;
import com.agent.task.TaskManager;
import com.agent.task.TaskModels.RoundStatus;
import com.agent.task.TaskModels.RoundType;
import com.agent.task.TaskTestProjects;
import com.agent.tool.ToolRegistry;

class TestTaskAgentTest {
    @Test
    void shouldAllowFifteenModelResponsesBeforePausing(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", false, false);
        var round = manager.startRound(created.task(), RoundType.ANALYSIS, "持续分析");
        var task = manager.store().load(created.task().id());
        var context = new TaskExecutionContext(manager.store(), task, round,
                manager.inspector().inspect(task.targetSource()),
                new PrintWriter(new StringWriter(), true));
        FakeLLMClient fake = new FakeLLMClient(java.util.Collections.nCopies(
                TaskExecutionContext.MAX_MODEL_CALLS,
                new LLMResponse("还需要继续分析。", null, 1, 1)));

        String result = new TestTaskAgent(fake, new ToolRegistry(context), context,
                new PrintWriter(new StringWriter(), true)).run("持续分析");

        assertEquals(15, fake.requests().size());
        assertEquals(15, context.round().modelCallCount());
        assertEquals(30, context.round().totalTokenCount());
        assertEquals(RoundStatus.PAUSED, context.round().status());
        assertTrue(result.contains("Token 总量: 输入=15，输出=15，合计=30"));
    }

    @Test
    void shouldFinishAnalysisUsingUnifiedReadToolWithoutPersistingSourceBody(@TempDir Path root)
            throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", false, false);
        var round = manager.startRound(created.task(), RoundType.ANALYSIS, "分析边界场景");
        var task = manager.store().load(created.task().id());
        var context = new TaskExecutionContext(manager.store(), task, round,
                manager.inspector().inspect(task.targetSource()),
                new PrintWriter(new StringWriter(), true));
        ToolRegistry registry = new ToolRegistry(context);
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(new ToolCall("read", new ToolCall.Function(
                        "read_file", "{\"path\":\"" + task.targetSource() + "\"}"))), 10, 2),
                new LLMResponse("应测试加法、除法正常路径和除零异常。", null, 20, 5)));

        String result = new TestTaskAgent(fake, registry, context,
                new PrintWriter(new StringWriter(), true)).run("分析边界场景");

        assertTrue(result.contains("应测试加法"));
        assertTrue(result.contains("Token 总量: 输入=30，输出=7，合计=37"));
        assertEquals(RoundStatus.ANALYZED, context.round().status());
        assertEquals(30, context.round().promptTokenCount());
        assertEquals(7, context.round().completionTokenCount());
        assertFalse(Files.exists(root.resolve(task.testFile())));
        String stored = Files.readString(manager.store().roundDirectory(task.id(), round.number())
                .resolve("round.json"));
        assertFalse(stored.contains("return left + right"));
        assertTrue(stored.contains("read_file"));
        assertTrue(stored.contains("\"prompt_token_count\" : 30"));
        assertTrue(stored.contains("\"completion_token_count\" : 7"));
    }

    @Test
    void shouldPersistWriteArgumentShapeAndHashWithoutSourceBody(@TempDir Path root)
            throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", false, false);
        var round = manager.startRound(created.task(), RoundType.ANALYSIS, "分析");
        var task = manager.store().load(created.task().id());
        var context = new TaskExecutionContext(manager.store(), task, round,
                manager.inspector().inspect(task.targetSource()),
                new PrintWriter(new StringWriter(), true));
        ToolRegistry registry = new ToolRegistry(context);
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(new ToolCall("empty", new ToolCall.Function(
                        "write_file", "{\"content\":\"\"}"))), 10, 2),
                new LLMResponse("", List.of(new ToolCall("read", new ToolCall.Function(
                        "read_file", "{\"path\":\"" + task.targetSource() + "\"}"))), 10, 2),
                new LLMResponse("完成分析。", null, 10, 2)));

        new TestTaskAgent(fake, registry, context,
                new PrintWriter(new StringWriter(), true)).run("分析");

        String parameters = context.round().toolEvents().get(0).parameters();
        assertTrue(parameters.contains("source=model_tool_call"));
        assertTrue(parameters.contains("content_present=true"));
        assertTrue(parameters.contains("content_type=string"));
        assertTrue(parameters.contains("content_chars=0"));
        assertTrue(parameters.contains("content_bytes=0"));
        assertTrue(parameters.contains("content_sha256=e3b0c44298fc"));
        assertFalse(parameters.contains("完整 Java 测试源码"));
        assertEquals(0, context.round().attemptCount());
        assertTrue(fake.requests().get(0).get(0).content()
                .contains("不要用 @ParameterizedTest"));
    }

    @Test
    void shouldDistinguishMissingWrongTypeAndInvalidWriteArguments() {
        String missing = TestTaskAgent.summarizeArguments(new ToolCall(
                "missing", new ToolCall.Function("write_file", "{}")));
        String wrongType = TestTaskAgent.summarizeArguments(new ToolCall(
                "type", new ToolCall.Function("write_file", "{\"content\":123}")));
        String invalid = TestTaskAgent.summarizeArguments(new ToolCall(
                "invalid", new ToolCall.Function("write_file", "{")));

        assertTrue(missing.contains("json_valid=true"));
        assertTrue(missing.contains("content_present=false"));
        assertTrue(wrongType.contains("content_present=true"));
        assertTrue(wrongType.contains("content_type=number"));
        assertTrue(invalid.contains("json_valid=false"));
        assertFalse(missing.contains("content_sha256"));
    }

    @Test
    void shouldUseStructuredExecutionStatusWithoutTreatingZeroErrorsAsFailure() {
        String passed = """
                测试执行结果:
                action: regression_test
                status: PASSED
                failures: 0
                errors: 0
                """;
        String failed = """
                测试执行结果:
                action: target_test
                status: TEST_FAILED
                failures: 1
                errors: 0
                """;

        assertEquals("success", TestTaskAgent.resultStatus(passed));
        assertEquals("failure", TestTaskAgent.resultStatus(failed));
        assertEquals("failure", TestTaskAgent.resultStatus("执行工具失败: 参数错误"));
        assertEquals("success", TestTaskAgent.resultStatus(
                "path: Example.java\n00001 | int errors = 0;"));
    }
}
