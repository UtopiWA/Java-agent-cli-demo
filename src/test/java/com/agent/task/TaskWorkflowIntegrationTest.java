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

import com.agent.task.TaskModels.RoundStatus;
import com.agent.task.TaskModels.RoundType;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

class TaskWorkflowIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCompleteRealBaselineTargetAndRegressionWorkflow(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", false, false);
        var round = manager.startRound(created.task(), RoundType.CHANGE, "生成完整单元测试");
        var task = manager.store().load(created.task().id());
        var context = new TaskExecutionContext(manager.store(), task, round,
                manager.inspector().inspect(task.targetSource()),
                new PrintWriter(new StringWriter(), true));
        ToolRegistry registry = new ToolRegistry(context);

        String read = registry.execute("read_file", mapper.createObjectNode()
                .put("path", task.targetSource()).toString());
        String baseline = registry.execute("execute_command", "{\"action\":\"baseline_test\"}");
        String written = registry.execute("write_file", mapper.createObjectNode()
                .put("content", TaskTestProjects.validTest()).toString());
        String target = registry.execute("execute_command", "{\"action\":\"target_test\"}");
        String regression = registry.execute(
                "execute_command", "{\"action\":\"regression_test\"}");

        assertTrue(read.contains("class Calculator"));
        assertTrue(baseline.contains("status: PASSED"), baseline);
        assertTrue(written.contains("static_validation: PASSED"), written);
        assertTrue(target.contains("status: PASSED"), target);
        assertTrue(regression.contains("status: PASSED"), regression);
        assertEquals(RoundStatus.SUCCEEDED, context.round().status());
        assertTrue(Files.readString(root.resolve(task.testFile())).contains("rejectsZeroDivisor"));
        assertFalse(Files.exists(manager.store().roundDirectory(task.id(), round.number())
                .resolve("transaction.json")));
        assertEquals(FileHashes.sha256(root.resolve(task.testFile())),
                manager.store().load(task.id()).committedTestHash());
    }

    @Test
    void shouldRejectDangerousCandidateBeforeWritingOrRunningCandidateTests(@TempDir Path root)
            throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", false, false);
        var round = manager.startRound(created.task(), RoundType.CHANGE, "生成危险测试");
        var task = manager.store().load(created.task().id());
        var context = new TaskExecutionContext(manager.store(), task, round,
                manager.inspector().inspect(task.targetSource()),
                new PrintWriter(new StringWriter(), true));
        ToolRegistry registry = new ToolRegistry(context);

        registry.execute("read_file", mapper.createObjectNode()
                .put("path", task.targetSource()).toString());
        registry.execute("execute_command", "{\"action\":\"baseline_test\"}");
        String dangerous = TaskTestProjects.validTest().replace(
                "assertEquals(5, new Calculator().add(2, 3));",
                "System.exit(0); Runtime.getRuntime().exec(\"echo unsafe\");");
        String rejected = registry.execute("write_file", mapper.createObjectNode()
                .put("content", dangerous).toString());

        assertTrue(rejected.contains("dangerous: true"), rejected);
        assertEquals(RoundStatus.FAILED, context.round().status());
        assertFalse(context.round().attempts().get(0).staticValidationPassed());
        assertTrue(context.round().outcomeSummary().contains("危险候选已拒绝"));
        assertEquals(null, context.round().targetResult());
        assertEquals(null, context.round().regressionResult());
        assertFalse(Files.exists(root.resolve(task.testFile())));
        assertTrue(Files.exists(manager.store().roundDirectory(task.id(), round.number())
                .resolve("attempts/attempt-01.java")));
    }
}
