package com.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证无 Shell 命令执行、真实退出状态、超时和输出边界。 */
class ExecuteCommandToolTest {
    @Test
    void shouldCaptureStdoutStderrAndWorkingDirectory(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("CommandProbe.java"), """
                class CommandProbe {
                    public static void main(String[] args) {
                        System.out.print(System.getProperty("user.dir"));
                        System.err.print("stderr-ok");
                    }
                }
                """);

        ExecuteCommandTool.CommandResult result = new ExecuteCommandTool().execute(
                root, "java", List.of("CommandProbe.java"), Duration.ofSeconds(20));

        assertEquals(0, result.exitCode());
        assertTrue(result.standardOutput().contains(root.toString()));
        assertEquals("stderr-ok", result.standardError());
    }

    @Test
    void shouldReturnNonZeroExitCode(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("ExitProbe.java"), """
                class ExitProbe {
                    public static void main(String[] args) {
                        System.exit(7);
                    }
                }
                """);

        ExecuteCommandTool.CommandResult result = new ExecuteCommandTool().execute(
                root, "java", List.of("ExitProbe.java"), Duration.ofSeconds(20));

        assertEquals(7, result.exitCode());
        assertFalse(result.timedOut());
    }

    @Test
    void shouldTerminateTimedOutCommand(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("SleepProbe.java"), """
                class SleepProbe {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(10_000);
                    }
                }
                """);

        ExecuteCommandTool.CommandResult result = new ExecuteCommandTool().execute(
                root, "java", List.of("SleepProbe.java"), Duration.ofMillis(500));

        assertEquals(-1, result.exitCode());
        assertTrue(result.timedOut());
    }

    @Test
    void shouldDrainButTruncateLargeOutput(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("OutputProbe.java"), """
                class OutputProbe {
                    public static void main(String[] args) {
                        System.out.print("x".repeat(10_000));
                    }
                }
                """);

        ExecuteCommandTool.CommandResult result = new ExecuteCommandTool().execute(
                root, "java", List.of("OutputProbe.java"), Duration.ofSeconds(20));

        assertEquals(0, result.exitCode());
        assertTrue(result.standardOutput().contains("命令输出已截断"));
        assertTrue(result.standardOutput().length() < 10_000);
    }

    @Test
    void shouldRejectUnapprovedOrPathBasedProgram(@TempDir Path root) {
        ExecuteCommandTool tool = new ExecuteCommandTool();

        assertThrows(SecurityException.class, () -> tool.execute(
                root, "powershell", List.of(), Duration.ofSeconds(1)));
        assertThrows(SecurityException.class, () -> tool.execute(
                root, "../java", List.of("-version"), Duration.ofSeconds(1)));
        assertThrows(SecurityException.class, () -> tool.execute(
                root, "java", List.of("../Outside.java"), Duration.ofSeconds(1)));
        assertThrows(SecurityException.class, () -> tool.execute(
                root, "mvn", List.of("exec:exec"), Duration.ofSeconds(1)));
    }

    @Test
    void shouldRunMavenVersionThroughPlatformAdapter(@TempDir Path root) throws Exception {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");

        ExecuteCommandTool.CommandResult result = new ExecuteCommandTool().execute(
                root,
                windows ? "mvn.cmd" : "mvn",
                List.of("--version"),
                Duration.ofSeconds(20));

        assertEquals(0, result.exitCode());
        assertTrue(result.standardOutput().contains("Apache Maven"));
    }
}
