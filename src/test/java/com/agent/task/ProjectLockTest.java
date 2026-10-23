package com.agent.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectLockTest {
    @Test
    void shouldAllowOnlyOneWriterForTheSameProject(@TempDir Path root) throws Exception {
        try (ProjectLock first = ProjectLock.tryAcquire(root);
                ProjectLock second = ProjectLock.tryAcquire(root)) {
            assertTrue(first.acquired());
            assertFalse(second.acquired());
        }
        try (ProjectLock next = ProjectLock.tryAcquire(root)) {
            assertTrue(next.acquired());
        }
    }

    @Test
    void shouldAllowReadOnlyInstanceToAcquireReleasedLock(@TempDir Path root) throws Exception {
        ProjectLock first = ProjectLock.tryAcquire(root);
        try (ProjectLock waiting = ProjectLock.tryAcquire(root)) {
            assertTrue(first.acquired());
            assertFalse(waiting.acquired());
            assertFalse(waiting.tryReacquire());

            first.close();

            assertTrue(waiting.tryReacquire());
            assertTrue(waiting.acquired());
        } finally {
            first.close();
        }
    }
}
