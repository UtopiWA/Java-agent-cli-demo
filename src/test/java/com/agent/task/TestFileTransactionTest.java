package com.agent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.task.TaskModels.RoundType;

class TestFileTransactionTest {
    @Test
    void shouldRestoreRoundBaselineAndPreserveExternalEditOnConflict(@TempDir Path root)
            throws Exception {
        TaskTestProjects.calculator(root);
        TaskManager manager = new TaskManager(root);
        var created = manager.create("fixture.calculator.Calculator", true, false);
        Path test = root.resolve(created.task().testFile());
        Files.writeString(test, "baseline");
        var round = manager.startRound(created.task(), RoundType.CHANGE, "重新生成测试");
        TestFileTransaction transaction = new TestFileTransaction(
                manager.store(), manager.store().load(created.task().id()), round.number());

        transaction.write("candidate");
        transaction.rollback();
        assertEquals("baseline", Files.readString(test));
        assertFalse(Files.exists(manager.store().roundDirectory(created.task().id(), round.number())
                .resolve("transaction.json")));

        TestFileTransaction conflicting = new TestFileTransaction(
                manager.store(), manager.store().load(created.task().id()), round.number());
        conflicting.write("candidate-2");
        Files.writeString(test, "external edit");
        assertThrows(TestFileTransaction.RollbackConflictException.class, conflicting::rollback);
        assertEquals("external edit", Files.readString(test));
        assertTrue(Files.exists(manager.store().roundDirectory(created.task().id(), round.number())
                .resolve("transaction.json")));
    }
}
