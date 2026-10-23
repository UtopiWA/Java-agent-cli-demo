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

class TaskCandidateLimitTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCountStaticValidationFailuresAndStopAtThree(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", false, false);
        var initial = manager.startRound(created.task(), RoundType.CHANGE, "生成测试");
        var accepted = TaskManager.copyRound(initial, RoundStatus.BASELINE_ACCEPTED,
                "测试准备", initial.attempts(), null, null, null, initial.fileState(),
                0, 0, initial.toolEvents());
        manager.store().saveRound(created.task().id(), accepted);
        var task = manager.store().load(created.task().id());
        var context = new TaskExecutionContext(manager.store(), task, accepted,
                manager.inspector().inspect(task.targetSource()),
                new PrintWriter(new StringWriter(), true));
        ToolRegistry registry = new ToolRegistry(context);
        registry.execute("read_file", mapper.createObjectNode()
                .put("path", task.targetSource()).toString());

        for (int number = 1; number <= 3; number++) {
            String invalid = """
                    package fixture.calculator;
                    import org.junit.jupiter.api.Test;
                    class CalculatorTest {
                        @Test void candidate%s() { new Calculator().add(1, 2); }
                    }
                    """.formatted(number);
            registry.execute("write_file", mapper.createObjectNode()
                    .put("content", invalid).toString());
        }

        assertEquals(3, context.round().attemptCount());
        assertEquals(RoundStatus.FAILED, context.round().status());
        assertFalse(Files.exists(root.resolve(task.testFile())));
        assertEquals(3, Files.list(manager.store().roundDirectory(task.id(), initial.number())
                .resolve("attempts")).count());
    }

    @Test
    void shouldRejectBlankContentWithoutConsumingCandidate(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", false, false);
        var initial = manager.startRound(created.task(), RoundType.CHANGE, "生成测试");
        var accepted = TaskManager.copyRound(initial, RoundStatus.BASELINE_ACCEPTED,
                "测试准备", initial.attempts(), null, null, null, initial.fileState(),
                0, 0, initial.toolEvents());
        manager.store().saveRound(created.task().id(), accepted);
        var task = manager.store().load(created.task().id());
        var context = new TaskExecutionContext(manager.store(), task, accepted,
                manager.inspector().inspect(task.targetSource()),
                new PrintWriter(new StringWriter(), true));
        ToolRegistry registry = new ToolRegistry(context);

        String empty = registry.execute("write_file", mapper.createObjectNode()
                .put("content", "").toString());
        String blank = registry.execute("write_file", mapper.createObjectNode()
                .put("content", "   ").toString());
        String directBlank = context.writeCandidate("   ");

        assertTrue(empty.contains("参数 content 不能为空"));
        assertTrue(blank.contains("缺少必填参数: content"));
        assertTrue(directBlank.contains("候选内容不能为空或只含空白"));
        assertEquals(0, context.round().attemptCount());
        assertEquals(RoundStatus.BASELINE_ACCEPTED, context.round().status());
    }

    @Test
    void shouldExposeDomainSpecificMutationSchemas(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", false, false);
        var round = manager.startRound(created.task(), RoundType.ANALYSIS, "分析");
        var task = manager.store().load(created.task().id());
        var context = new TaskExecutionContext(manager.store(), task, round,
                manager.inspector().inspect(task.targetSource()),
                new PrintWriter(new StringWriter(), true));
        var definitions = new ToolRegistry(context).getToolDefinitions();

        var writeDefinition = definitions.get(3);
        var write = writeDefinition.parameters().path("properties");
        var execute = definitions.get(4).parameters().path("properties");
        assertTrue(write.has("content"));
        assertEquals(1, write.path("content").path("minLength").asInt());
        assertTrue(writeDefinition.description().contains("普通 @Test"));
        assertFalse(write.has("path"));
        assertTrue(execute.has("action"));
        assertFalse(execute.has("program"));
    }
}
