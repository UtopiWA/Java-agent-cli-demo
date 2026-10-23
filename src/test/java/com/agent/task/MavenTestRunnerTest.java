package com.agent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agent.task.TaskModels.TestAction;
import com.agent.task.TaskModels.TestStatus;
import com.agent.tool.ExecuteCommandTool;

class MavenTestRunnerTest {
    @Test
    void shouldRejectSuccessfulExitWithoutFreshTargetReport(@TempDir Path root) {
        MavenTestRunner runner = new MavenTestRunner(root, "demo.SampleTest",
                (directory, testClass, targetOnly, timeout) ->
                        new ExecuteCommandTool.CommandResult(0, false, "", ""));

        var result = runner.run(TestAction.target_test);

        assertEquals(TestStatus.NO_TESTS, result.status());
    }

    @Test
    void shouldClassifyCompilationFailure(@TempDir Path root) {
        MavenTestRunner runner = new MavenTestRunner(root, "demo.SampleTest",
                (directory, testClass, targetOnly, timeout) ->
                        new ExecuteCommandTool.CommandResult(1, false, "",
                                "[ERROR] COMPILATION ERROR\nSampleTest.java:[3,2] error"));

        assertEquals(TestStatus.COMPILATION_FAILED,
                runner.run(TestAction.target_test).status());
    }

    @Test
    void shouldParseOnlyFreshSurefireFailureReport(@TempDir Path root) {
        MavenTestRunner runner = new MavenTestRunner(root, "demo.SampleTest",
                (directory, testClass, targetOnly, timeout) -> {
                    Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
                    Files.writeString(reports.resolve("TEST-demo.SampleTest.xml"), """
                            <testsuite name="demo.SampleTest" tests="1" failures="1" errors="0" skipped="0">
                              <testcase classname="demo.SampleTest" name="fails">
                                <failure type="org.opentest4j.AssertionFailedError" message="expected 1 but was 2">
                                  at demo.SampleTest.fails(SampleTest.java:12)
                                </failure>
                              </testcase>
                            </testsuite>
                            """);
                    return new ExecuteCommandTool.CommandResult(1, false, "", "test failed");
                });

        var result = runner.run(TestAction.target_test);

        assertEquals(TestStatus.TEST_FAILED, result.status());
        assertEquals(1, result.tests());
        assertEquals(1, result.failures());
        assertTrue(result.failedTests().contains("demo.SampleTest#fails"));
        assertTrue(result.diagnostic().contains("expected 1 but was 2"));
    }
}
