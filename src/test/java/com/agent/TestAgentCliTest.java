package com.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.ToolCall;
import com.agent.task.ProjectLock;
import com.agent.task.TaskManager;
import com.agent.task.TaskModels.RoundStatus;
import com.agent.task.TaskModels.RoundType;
import com.agent.task.TaskTestProjects;

class TestAgentCliTest {
    @Test
    void shouldShowWhetherACommandIsInsideATaskInPrompt(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        StringWriter terminal = new StringWriter();
        TestAgentCli cli = new TestAgentCli(manager, null,
                new PrintWriter(terminal, true), true);

        cli.run(new BufferedReader(new StringReader(
                "new fixture.calculator.Calculator\nexit\n")));

        var task = manager.store().list(0, 10).tasks().get(0);
        String text = terminal.toString();
        assertTrue(text.contains("test-agent[none]> "));
        assertTrue(text.contains("test-agent[" + manager.displayId(task.id()) + "]> "));
    }

    @Test
    void shouldUpdatePromptAfterResumeAndDeletingCurrentTask(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", false, false);
        String displayId = manager.displayId(created.task().id());
        StringWriter terminal = new StringWriter();
        TestAgentCli cli = new TestAgentCli(manager, null,
                new PrintWriter(terminal, true), true);

        cli.run(new BufferedReader(new StringReader(
                "resume " + displayId + "\ndelete " + displayId + "\nexit\n")));

        String text = terminal.toString();
        assertTrue(text.contains("test-agent[" + displayId + "]> "));
        assertTrue(text.lastIndexOf("test-agent[none]> ")
                > text.indexOf("test-agent[" + displayId + "]> "));
    }

    @Test
    void shouldHandleTaskCommandsWithoutSendingThemToModel(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        TestAgentCli cli = new TestAgentCli(new TaskManager(root), null,
                new PrintWriter(new StringWriter(), true), true);

        String created = cli.handle("new fixture.calculator.Calculator");
        String status = cli.handle("status");
        String history = cli.handle("history --limit 10 --offset 0");
        String noKey = cli.handle("分析边界场景");

        assertTrue(created.contains("已创建任务"));
        assertTrue(status.contains("fixture.calculator.Calculator"));
        var task = new TaskManager(root).store().list(0, 10).tasks().get(0);
        assertTrue(status.contains("任务 ID: " + task.id().substring(0, 8)));
        assertTrue(history.contains("fixture.calculator.Calculator"));
        assertTrue(noKey.contains("GLM_API_KEY"));
    }

    @Test
    void shouldRestrictSecondProcessToReadOnlyCommands(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        TestAgentCli cli = new TestAgentCli(new TaskManager(root), null,
                new PrintWriter(new StringWriter(), true), false);

        assertTrue(cli.handle("new fixture.calculator.Calculator").contains("只读模式"));
        assertTrue(cli.handle("history").contains("has_more"));
    }

    @Test
    void shouldAcquireReleasedProjectLockBeforeExecutingWriteCommand(@TempDir Path root)
            throws Exception {
        TaskTestProjects.calculator(root);
        StringWriter terminal = new StringWriter();
        ProjectLock first = ProjectLock.tryAcquire(root);
        try (ProjectLock waiting = ProjectLock.tryAcquire(root)) {
            TestAgentCli cli = new TestAgentCli(new TaskManager(root), null,
                    new PrintWriter(terminal, true), waiting);

            assertFalse(waiting.acquired());
            assertTrue(cli.handle("status").contains("项目访问模式: 只读"));
            assertTrue(cli.handle("new fixture.calculator.Calculator").contains("只读模式"));

            first.close();
            assertFalse(waiting.acquired());
            String created = cli.handle("new fixture.calculator.Calculator");

            assertTrue(created.contains("已创建任务"), created);
            assertTrue(waiting.acquired());
            assertTrue(terminal.toString().contains("[lock] 已取得项目写锁"));
            assertTrue(cli.handle("status").contains("项目访问模式: 可写"));
        } finally {
            first.close();
        }
    }

    @Test
    void shouldUseModelClassificationInsteadOfKeywordsForNegativeRequest(@TempDir Path root)
            throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        StringWriter terminal = new StringWriter();
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("ANALYSIS", null, 30, 1),
                new LLMResponse("", List.of(new ToolCall("read", new ToolCall.Function(
                        "read_file",
                        "{\"path\":\"src/main/java/fixture/calculator/Calculator.java\"}"))),
                        40, 2),
                new LLMResponse("建议覆盖正常、边界和异常分支。", null, 50, 8)));
        TestAgentCli cli = new TestAgentCli(manager, fake,
                new PrintWriter(terminal, true), true);

        cli.handle("new fixture.calculator.Calculator");
        String result = cli.handle(
                "分析 Calculator 的公开行为、边界条件和异常分支，只给出测试建议，不生成或运行测试");

        var task = manager.store().list(0, 10).tasks().get(0);
        var round = manager.store().loadRound(task.id(), 1);
        assertEquals(RoundType.ANALYSIS, round.type());
        assertEquals(RoundStatus.ANALYZED, round.status());
        assertEquals(120, round.promptTokenCount());
        assertEquals(11, round.completionTokenCount());
        assertEquals(131, round.totalTokenCount());
        assertTrue(result.contains("建议覆盖"));
        assertTrue(result.contains("Token 总量: 输入=120，输出=11，合计=131"));
        assertTrue(cli.handle("show " + task.id() + " --round 1")
                .contains("Token 总量: 输入=120，输出=11，合计=131"));
        assertTrue(terminal.toString().contains("[intent] type=ANALYSIS source=model"));
    }

    @Test
    void shouldPersistModelClassifiedConstraintWithoutCreatingRound(@TempDir Path root)
            throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        StringWriter terminal = new StringWriter();
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("CONSTRAINT", null, 28, 1)));
        TestAgentCli cli = new TestAgentCli(manager, fake,
                new PrintWriter(terminal, true), true);

        cli.handle("new fixture.calculator.Calculator");
        String request = "今后生成的测试统一使用行为式名称";
        String result = cli.handle(request);

        var task = manager.store().list(0, 10).tasks().get(0);
        assertEquals(request, task.persistentConstraints());
        assertEquals(0, task.roundCount());
        assertEquals(1, fake.requests().size());
        assertTrue(result.contains("未创建执行轮次"));
        assertTrue(terminal.toString().contains("[intent] type=CONSTRAINT source=model"));
    }
}
