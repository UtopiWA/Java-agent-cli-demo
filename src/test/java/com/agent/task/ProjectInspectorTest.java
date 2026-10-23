package com.agent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectInspectorTest {
    @Test
    void shouldResolvePathAndQualifiedClassToSameTarget(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        ProjectInspector inspector = new ProjectInspector(root);

        var byClass = inspector.inspect("fixture.calculator.Calculator");
        var byPath = inspector.inspect("src/main/java/fixture/calculator/Calculator.java");

        assertEquals(byClass, byPath);
        assertEquals("src/test/java/fixture/calculator/CalculatorTest.java", byClass.testFile());
        assertEquals("fixture.calculator.CalculatorTest", byClass.qualifiedTestClass());
    }

    @Test
    void shouldRejectIgnoredTaskCriticalPath(@TempDir Path root) throws Exception {
        TaskTestProjects.calculator(root);
        Files.writeString(root.resolve(".agentignore"), "src/test/java/\n");

        Exception error = assertThrows(Exception.class,
                () -> new ProjectInspector(root).inspect("fixture.calculator.Calculator"));

        assertTrue(error.getMessage().contains(".agentignore"));
    }

    @Test
    void shouldRejectIgnoredTestPathWhenAgentIgnoreStartsWithUtf8Bom(@TempDir Path root)
            throws Exception {
        TaskTestProjects.calculator(root);
        Files.writeString(root.resolve(".agentignore"),
                "\uFEFFsrc/test/java/fixture/calculator/CalculatorTest.java\n");

        Exception error = assertThrows(Exception.class,
                () -> new ProjectInspector(root).inspect("fixture.calculator.Calculator"));

        assertTrue(error.getMessage().contains(".agentignore"));
        assertTrue(error.getMessage().contains("CalculatorTest.java"));
    }
}
