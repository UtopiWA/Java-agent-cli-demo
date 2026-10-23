package com.agent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.task.TaskModels.Attempt;
import com.agent.task.TaskModels.Round;
import com.agent.task.TaskModels.RoundType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

class TaskManagerTest {
    @Test
    void shouldReadLegacyAttemptWithoutRemovedMethods() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        Attempt attempt = mapper.readValue("""
                {
                  "number": 1,
                  "attachment": "rounds/round-0001/attempts/candidate-01.java",
                  "sha256": "abc",
                  "bytes": 3,
                  "static_validation_passed": true,
                  "validation_summary": "通过",
                  "target_result": null,
                  "failure_summary": null
                }
                """, Attempt.class);

        assertTrue(attempt.removedMethods().isEmpty());
    }

    @Test
    void shouldReadLegacyRoundWithoutTokenCounts() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        Round round = mapper.readValue("""
                {
                  "schema_version": 1,
                  "number": 1,
                  "created_at": "2026-10-22T12:00:00+08:00",
                  "updated_at": "2026-10-22T12:00:01+08:00",
                  "status": "ANALYZED",
                  "type": "ANALYSIS",
                  "request_summary": "分析边界",
                  "round_constraints": "",
                  "outcome_summary": "分析完成",
                  "attempt_count": 0,
                  "model_call_count": 1,
                  "tool_call_count": 0,
                  "tool_events": [],
                  "attempts": [],
                  "baseline_result": null,
                  "target_result": null,
                  "regression_result": null,
                  "file_state": null
                }
                """, Round.class);

        assertEquals(0, round.promptTokenCount());
        assertEquals(0, round.completionTokenCount());
        assertEquals(0, round.totalTokenCount());
    }

    @Test
    void shouldCreateDetectDuplicateListShowAndDeleteTask(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);

        var created = manager.create("fixture.calculator.Calculator", false, false);
        var duplicate = manager.create("fixture.calculator.Calculator", false, false);
        var round = manager.startRound(created.task(), RoundType.ANALYSIS, "分析边界");

        assertTrue(created.created());
        assertFalse(duplicate.created());
        assertEquals(1, round.number());
        assertTrue(manager.history(0, 20).contains("fixture.calculator.Calculator"));
        assertTrue(manager.show(created.task().id().substring(0, 8), 1).contains("分析边界"));

        manager.delete(created.task().id());
        assertFalse(Files.exists(manager.store().taskDirectory(created.task().id())));
        assertFalse(Files.exists(root.resolve(created.task().testFile())));
    }
}
