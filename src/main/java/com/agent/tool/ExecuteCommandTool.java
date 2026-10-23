package com.agent.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 在受控工作目录内执行允许的开发命令，并收集有界输出。 */
public final class ExecuteCommandTool {
    static final int MAX_STREAM_BYTES = 6_000;
    private static final int MAX_ARGUMENTS = 64;
    private static final int MAX_ARGUMENT_LENGTH = 4_096;
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(120);
    private static final Set<String> ALLOWED_PROGRAMS = Set.of(
            "java", "java.exe", "javac", "javac.exe", "mvn", "mvn.cmd");
    private static final Set<String> ALLOWED_MAVEN_ARGUMENTS = Set.of(
            "clean", "validate", "compile", "test", "package", "verify",
            "-q", "--quiet", "-b", "--batch-mode",
            "-v", "-version", "--version",
            "-dskiptests", "-dskiptests=true", "-dskiptests=false");

    public record CommandResult(
            int exitCode,
            boolean timedOut,
            String standardOutput,
            String standardError) {
    }

    /** 启动不经过 Shell 的参数化进程，并在超时后强制结束。 */
    public CommandResult execute(
            Path workingDirectory,
            String program,
            List<String> arguments,
            Duration timeout) throws IOException, InterruptedException {
        validateWorkingDirectory(workingDirectory);
        validateProgram(program);
        if (arguments == null) {
            throw new IllegalArgumentException("arguments 不能为空");
        }
        if (arguments.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("arguments 不能包含 null");
        }
        validateArguments(program, arguments);
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout 必须在 1 毫秒到 120 秒之间");
        }

        return executeProcess(workingDirectory, program, arguments, timeout);
    }

    /** 由任务状态机调用的固定 Maven 测试入口，模型不能提供任何 Maven 参数。 */
    public CommandResult executeMavenTests(
            Path workingDirectory,
            String qualifiedTestClass,
            boolean targetOnly,
            Duration timeout) throws IOException, InterruptedException {
        validateWorkingDirectory(workingDirectory);
        if (targetOnly && (qualifiedTestClass == null || !qualifiedTestClass.matches(
                "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
                        + "(?:\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*"))) {
            throw new IllegalArgumentException("定向测试类名无效");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout 必须在 1 毫秒到 120 秒之间");
        }
        List<String> arguments = new ArrayList<>();
        arguments.add("-q");
        if (targetOnly) {
            arguments.add("-Dtest=" + qualifiedTestClass);
        }
        arguments.add("test");
        String program = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "mvn.cmd" : "mvn";
        return executeProcess(workingDirectory, program, arguments, timeout);
    }

    private CommandResult executeProcess(
            Path workingDirectory,
            String program,
            List<String> arguments,
            Duration timeout) throws IOException, InterruptedException {

        List<String> command = buildCommand(program, arguments);
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile());
        removeSensitiveEnvironmentVariables(processBuilder.environment());
        Process process = processBuilder.start();

        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<String> stdout = readers.submit(
                () -> readLimited(process.getInputStream(), MAX_STREAM_BYTES));
        Future<String> stderr = readers.submit(
                () -> readLimited(process.getErrorStream(), MAX_STREAM_BYTES));

        boolean completed;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminateProcessTree(process);
            }
            return new CommandResult(
                    completed ? process.exitValue() : -1,
                    !completed,
                    awaitOutput(stdout),
                    awaitOutput(stderr));
        } finally {
            if (process.isAlive()) {
                terminateProcessTree(process);
            }
            readers.shutdownNow();
        }
    }

    /** 先正常结束后代和主进程，短暂等待后再强制清理残留进程。 */
    private static void terminateProcessTree(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private static void validateWorkingDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("工作目录不存在或不是目录: " + directory);
        }
    }

    /** 只允许 PATH 中的裸程序名，禁止用路径绕过允许列表。 */
    private static void validateProgram(String program) {
        if (program == null || program.isBlank()) {
            throw new IllegalArgumentException("program 不能为空");
        }
        if (program.contains("/") || program.contains("\\")) {
            throw new SecurityException("program 必须是允许列表中的裸程序名");
        }
        if (!ALLOWED_PROGRAMS.contains(program.toLowerCase(Locale.ROOT))) {
            throw new SecurityException("不允许执行程序: " + program);
        }
    }

    /** Windows 只能通过命令解释器启动 .cmd；固定 Maven 程序和白名单参数避免任意 Shell 输入。 */
    private static List<String> buildCommand(String program, List<String> arguments) {
        String normalizedProgram = program.toLowerCase(Locale.ROOT);
        boolean maven = normalizedProgram.equals("mvn") || normalizedProgram.equals("mvn.cmd");
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");

        List<String> command = new ArrayList<>();
        if (windows && maven) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/s");
            command.add("/c");
            command.add("mvn.cmd");
        } else {
            command.add(program);
        }
        command.addAll(arguments);
        return command;
    }

    /** 限制参数规模和路径逃逸；Maven 仅允许常用生命周期及无副作用选项。 */
    private static void validateArguments(String program, List<String> arguments) {
        if (arguments.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("命令参数数量超过上限（" + MAX_ARGUMENTS + "）");
        }
        for (String argument : arguments) {
            if (argument.length() > MAX_ARGUMENT_LENGTH) {
                throw new IllegalArgumentException(
                        "单个命令参数长度超过上限（" + MAX_ARGUMENT_LENGTH + "）");
            }
            if (argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("命令参数不能包含 NUL 字符");
            }
            if (isAbsoluteOrParentPath(argument)) {
                throw new SecurityException("命令参数不得引用项目外路径: " + argument);
            }
        }

        String normalizedProgram = program.toLowerCase(Locale.ROOT);
        if (normalizedProgram.equals("mvn") || normalizedProgram.equals("mvn.cmd")) {
            for (String argument : arguments) {
                if (!ALLOWED_MAVEN_ARGUMENTS.contains(argument.toLowerCase(Locale.ROOT))) {
                    throw new SecurityException("不允许使用 Maven 参数: " + argument);
                }
            }
        }
    }

    private static boolean isAbsoluteOrParentPath(String argument) {
        String normalized = argument.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            return true;
        }
        for (String part : normalized.split("/")) {
            if (part.equals("..")) {
                return true;
            }
        }
        return false;
    }

    /** 子进程不继承常见凭证变量，避免被项目代码或构建日志意外输出。 */
    private static void removeSensitiveEnvironmentVariables(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.contains("API_KEY")
                    || normalized.contains("TOKEN")
                    || normalized.contains("SECRET")
                    || normalized.contains("PASSWORD");
        });
    }

    /** 持续排空进程管道，只保留限定字节，避免大量输出阻塞子进程。 */
    private static String readLimited(InputStream stream, int maximumBytes) throws IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream(maximumBytes);
        byte[] buffer = new byte[1024];
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            int remaining = maximumBytes - retained.size();
            if (remaining > 0) {
                retained.write(buffer, 0, Math.min(remaining, read));
            }
            total += read;
        }

        String result = retained.toString(StandardCharsets.UTF_8);
        if (total > maximumBytes) {
            result += System.lineSeparator()
                    + "...[命令输出已截断，原始字节数=" + total + "]";
        }
        return result;
    }

    private static String awaitOutput(Future<String> output) throws IOException {
        try {
            return output.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取命令输出时被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("读取命令输出失败", cause);
        }
    }
}
