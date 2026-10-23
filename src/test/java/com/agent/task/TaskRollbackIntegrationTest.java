package com.agent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.TestAgentCli;
import com.agent.task.TaskModels.RoundStatus;
import com.agent.task.TaskModels.RoundType;
import com.agent.task.TaskModels.Task;

class TaskRollbackIntegrationTest {
    @Test
    void shouldRestorePreviousSuccessfulVersionAndRejectExternalChanges(@TempDir Path root)
            throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        Task task = manager.create("fixture.calculator.Calculator", false, false).task();
        String firstVersion = TaskTestProjects.validTest();
        String secondVersion = addDivisionTest(firstVersion);

        task = commit(manager, task, "生成基础测试", firstVersion);
        task = commit(manager, task, "增加普通除法测试", secondVersion);
        assertEquals(2, task.latestCommittedRound());

        StringWriter terminal = new StringWriter();
        TestAgentCli cli = new TestAgentCli(manager, null,
                new PrintWriter(terminal, true), true);
        cli.handle("resume " + manager.displayId(task.id()));
        String result = cli.handle("rollback");

        Task rolledBack = manager.store().load(task.id());
        var rollbackRound = manager.store().loadRound(task.id(), 3);
        assertEquals(firstVersion, Files.readString(root.resolve(task.testFile())));
        assertEquals(RoundType.CHANGE, rollbackRound.type());
        assertEquals(RoundStatus.SUCCEEDED, rollbackRound.status());
        assertEquals(0, rollbackRound.totalTokenCount());
        assertEquals("rollback --round 1", rollbackRound.requestSummary());
        assertEquals(3, rolledBack.latestCommittedRound());
        assertTrue(rollbackRound.attempts().get(0).removedMethods().contains("dividesNormally"));
        assertTrue(result.contains("本轮移除的已有测试方法: dividesNormally"));
        assertTrue(result.contains("Token 总量: 输入=0，输出=0，合计=0"));
        assertTrue(terminal.toString().contains("[rollback] source_round=1"));
        assertFalse(Files.exists(manager.store().roundDirectory(task.id(), 3)
                .resolve("transaction.json")));

        Files.writeString(root.resolve(task.testFile()), firstVersion + "// external edit\n");
        String refused = cli.handle("rollback --round 2");
        assertTrue(refused.contains("外部修改"), refused);
        assertEquals(3, manager.store().load(task.id()).roundCount());
    }

    private static Task commit(TaskManager manager, Task task, String request, String content)
            throws Exception {
        var round = manager.startRound(task, RoundType.CHANGE, request);
        task = manager.store().load(task.id());
        var context = new TaskExecutionContext(manager.store(), task, round,
                manager.inspector().inspect(task.targetSource()),
                new PrintWriter(new StringWriter(), true));
        context.noteRead(manager.store().projectRoot().resolve(task.targetSource()));
        context.noteRead(manager.store().projectRoot().resolve(task.testFile()));
        context.noteRead(manager.store().projectRoot().resolve("pom.xml"));

        context.execute(TaskModels.TestAction.baseline_test);
        context.writeCandidate(content);
        context.execute(TaskModels.TestAction.target_test);
        context.execute(TaskModels.TestAction.regression_test);

        assertEquals(RoundStatus.SUCCEEDED, context.round().status());
        return manager.store().load(task.id());
    }

    private static String addDivisionTest(String source) {
        int closingBrace = source.lastIndexOf('}');
        return source.substring(0, closingBrace) + """
                    @Test
                    void dividesNormally() {
                        assertEquals(2, new Calculator().divide(4, 2));
                    }
                }
                """;
    }
}
