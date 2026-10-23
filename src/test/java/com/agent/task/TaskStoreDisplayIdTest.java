package com.agent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.TestAgentCli;
import com.agent.task.TaskModels.RoundStatus;
import com.agent.task.TaskModels.RoundType;
import com.agent.task.TaskModels.Task;

class TaskStoreDisplayIdTest {
    @Test
    void shouldExtendEightCharacterPrefixWhenItCollides(@TempDir Path root) throws Exception {
        TaskStore store = new TaskStore(root);
        String first = "12345678-1111-4111-8111-111111111111";
        String second = "12345678-2222-4222-8222-222222222222";
        store.create(task(store, first, "fixture.First", RoundType.CHANGE,
                RoundStatus.SUCCEEDED));
        store.create(task(store, second, "fixture.Second", RoundType.ANALYSIS,
                RoundStatus.ANALYZED));

        assertEquals("12345678-1", store.displayId(first));
        assertEquals("12345678-2", store.displayId(second));
    }

    @Test
    void shouldRejectAmbiguousPrefixAndListCandidatesWithoutDeleting(@TempDir Path root)
            throws Exception {
        TaskStore store = new TaskStore(root);
        String first = "12345678-1111-4111-8111-111111111111";
        String second = "12345678-2222-4222-8222-222222222222";
        store.create(task(store, first, "fixture.First", RoundType.CHANGE,
                RoundStatus.SUCCEEDED));
        store.create(task(store, second, "fixture.Second", RoundType.ANALYSIS,
                RoundStatus.ANALYZED));

        AmbiguousTaskIdException error = assertThrows(
                AmbiguousTaskIdException.class, () -> store.delete("12345678"));
        String message = error.userMessage("delete");

        assertTrue(message.contains("任务 ID 前缀不唯一: 12345678"));
        assertTrue(message.contains("12345678-1  fixture.First"));
        assertTrue(message.contains("CHANGE/SUCCEEDED"));
        assertTrue(message.contains("12345678-2  fixture.Second"));
        assertTrue(message.contains("例如: delete 12345678-1"));
        assertEquals(first, store.load("12345678-1").id());
        assertTrue(store.taskDirectory(first).toFile().isDirectory());
        assertTrue(store.taskDirectory(second).toFile().isDirectory());
    }

    @Test
    void shouldReturnCommandSpecificDisambiguationFromCli(@TempDir Path root) throws Exception {
        TaskStore store = new TaskStore(root);
        String first = "12345678-1111-4111-8111-111111111111";
        String second = "12345678-2222-4222-8222-222222222222";
        store.create(task(store, first, "fixture.First", null, RoundStatus.NONE));
        store.create(task(store, second, "fixture.Second", null, RoundStatus.NONE));
        TestAgentCli cli = new TestAgentCli(new TaskManager(root), null,
                new PrintWriter(new StringWriter(), true), true);

        for (String command : new String[] {"show", "resume", "delete"}) {
            String result = cli.handle(command + " 12345678");
            assertTrue(result.startsWith("任务 ID 前缀不唯一: 12345678"));
            assertTrue(result.contains("例如: " + command + " 12345678-1"));
        }
        assertTrue(store.taskDirectory(first).toFile().isDirectory());
        assertTrue(store.taskDirectory(second).toFile().isDirectory());
    }

    @Test
    void shouldAlignHistoryColumnsWithExtendedIds(@TempDir Path root) throws Exception {
        TaskStore store = new TaskStore(root);
        String first = "12345678-1111-4111-8111-111111111111";
        String second = "12345678-2222-4222-8222-222222222222";
        store.create(task(store, first, "fixture.First", RoundType.CHANGE,
                RoundStatus.SUCCEEDED));
        store.create(task(store, second, "fixture.Second", RoundType.ANALYSIS,
                RoundStatus.ANALYZED));

        String[] lines = new TaskManager(root).history(0, 20).split("\\R");
        int roundColumn = lines[0].indexOf("ROUND");
        int validationColumn = lines[0].indexOf("VALIDATION");
        int targetColumn = lines[0].indexOf("TARGET");

        assertEquals(roundColumn, lines[1].indexOf("CHANGE/SUCCEEDED"));
        assertEquals(roundColumn, lines[2].indexOf("ANALYSIS/ANALYZED"));
        assertEquals(validationColumn, lines[1].indexOf("UNVERIFIED"));
        assertEquals(validationColumn, lines[2].indexOf("UNVERIFIED"));
        assertEquals(targetColumn, lines[1].indexOf("fixture.First"));
        assertEquals(targetColumn, lines[2].indexOf("fixture.Second"));
    }

    @Test
    void shouldKeepEightCharactersWhenTheyAreUnique(@TempDir Path root) throws Exception {
        TaskStore store = new TaskStore(root);
        String id = "abcdef12-1111-4111-8111-111111111111";
        store.create(task(store, id, "fixture.Only", null, RoundStatus.NONE));

        assertEquals("abcdef12", store.displayId(id));
    }

    private static Task task(
            TaskStore store,
            String id,
            String targetClass,
            RoundType type,
            RoundStatus status) throws Exception {
        String now = "2026-01-01T00:00:00Z";
        String simpleName = targetClass.substring(targetClass.lastIndexOf('.') + 1);
        String packagePath = targetClass.replace('.', '/');
        return new Task(
                TaskModels.SCHEMA_VERSION,
                id,
                store.projectRoot().toString(),
                targetClass,
                "src/main/java/" + packagePath + ".java",
                targetClass + "Test",
                "src/test/java/" + packagePath + "Test.java",
                now,
                now,
                type == null ? 0 : 1,
                null,
                type,
                status,
                false,
                "",
                null,
                null,
                null,
                null,
                simpleName + " task");
    }
}
