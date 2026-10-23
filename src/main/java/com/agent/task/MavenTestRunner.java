package com.agent.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.agent.task.TaskModels.TestAction;
import com.agent.task.TaskModels.TestExecution;
import com.agent.task.TaskModels.TestStatus;
import com.agent.tool.ExecuteCommandTool;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** 执行固定 Maven 测试动作，并且只解析本次新建或更新的 Surefire 报告。 */
public final class MavenTestRunner {
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_DIAGNOSTIC_CHARS = 6_000;
    private static final Pattern COMPILATION = Pattern.compile(
            "(?is)(COMPILATION ERROR|Compilation failure|\\.java:\\[?\\d+[, :]\\d*\\]?\\s+error)");
    private static final Pattern NO_TESTS = Pattern.compile(
            "(?is)(No tests (?:were )?executed|No tests found|No tests to run)");

    private final Path projectRoot;
    private final String qualifiedTestClass;
    private final CommandExecutor executor;

    public MavenTestRunner(Path projectRoot, String qualifiedTestClass) {
        this(projectRoot, qualifiedTestClass,
                (root, testClass, targetOnly, timeout) -> new ExecuteCommandTool()
                        .executeMavenTests(root, testClass, targetOnly, timeout));
    }

    MavenTestRunner(Path projectRoot, String qualifiedTestClass, CommandExecutor executor) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.qualifiedTestClass = qualifiedTestClass;
        this.executor = executor;
    }

    public TestExecution run(TestAction action) {
        Map<Path, String> before = reportFingerprints();
        ExecuteCommandTool.CommandResult command;
        try {
            boolean targetOnly = action == TestAction.target_test;
            command = executor.execute(
                    projectRoot, qualifiedTestClass, targetOnly, TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(action, TestStatus.EXECUTION_FAILED, -1,
                    List.of(), 0, 0, 0, 0, "Maven 执行被中断", false);
        } catch (Exception e) {
            return result(action, TestStatus.EXECUTION_FAILED, -1,
                    List.of(), 0, 0, 0, 0, "无法启动 Maven: " + safeMessage(e), false);
        }

        String raw = joinOutput(command.standardOutput(), command.standardError());
        boolean truncated = raw.contains("命令输出已截断");
        if (command.timedOut()) {
            return result(action, TestStatus.TIMED_OUT, command.exitCode(),
                    List.of(), 0, 0, 0, 0, bounded(raw), truncated);
        }

        List<Suite> suites = readFreshReports(before);
        int tests = suites.stream().mapToInt(Suite::tests).sum();
        int failures = suites.stream().mapToInt(Suite::failures).sum();
        int errors = suites.stream().mapToInt(Suite::errors).sum();
        int skipped = suites.stream().mapToInt(Suite::skipped).sum();
        List<String> failedTests = suites.stream().flatMap(suite -> suite.failedTests().stream()).toList();

        if (COMPILATION.matcher(raw).find()) {
            return result(action, TestStatus.COMPILATION_FAILED, command.exitCode(),
                    failedTests, tests, failures, errors, skipped, bounded(raw), truncated);
        }
        if (action == TestAction.target_test) {
            boolean targetReport = suites.stream().anyMatch(
                    suite -> qualifiedTestClass.equals(suite.name()));
            int executed = tests - skipped;
            if (!targetReport || executed < 1 || NO_TESTS.matcher(raw).find()) {
                return result(action, TestStatus.NO_TESTS, command.exitCode(),
                        failedTests, tests, failures, errors, skipped,
                        "没有发现本次生成的新鲜目标测试报告。\n" + bounded(raw), truncated);
            }
        }
        if (command.exitCode() == 0 && failures == 0 && errors == 0) {
            return result(action, TestStatus.PASSED, 0, failedTests,
                    tests, failures, errors, skipped,
                    tests == 0 ? "Maven 执行成功；当前动作没有需要统计的 Surefire 测试。" : "测试执行通过。",
                    truncated);
        }
        if (failures > 0 || errors > 0) {
            String reportDiagnostic = suites.stream()
                    .map(Suite::diagnostic).filter(value -> !value.isBlank())
                    .reduce((left, right) -> left + System.lineSeparator() + right).orElse("");
            return result(action, TestStatus.TEST_FAILED, command.exitCode(),
                    failedTests, tests, failures, errors, skipped,
                    bounded(reportDiagnostic + System.lineSeparator() + raw), truncated);
        }
        TestStatus status = NO_TESTS.matcher(raw).find()
                ? TestStatus.NO_TESTS : TestStatus.EXECUTION_FAILED;
        return result(action, status, command.exitCode(), failedTests,
                tests, failures, errors, skipped, bounded(raw), truncated);
    }

    private Map<Path, String> reportFingerprints() {
        Map<Path, String> result = new HashMap<>();
        Path directory = projectRoot.resolve("target/surefire-reports");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return result;
        }
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(file -> file.getFileName().toString().startsWith("TEST-")
                    && file.getFileName().toString().endsWith(".xml")).toList()) {
                try {
                    result.put(path, FileHashes.sha256(path));
                } catch (IOException ignored) {
                    // 无法读取的旧报告不作为本轮证据。
                }
            }
        } catch (IOException ignored) {
            // 报告目录不存在或不可读时交由执行结果分类。
        }
        return result;
    }

    private List<Suite> readFreshReports(Map<Path, String> before) {
        Path directory = projectRoot.resolve("target/surefire-reports");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Suite> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(file -> file.getFileName().toString().startsWith("TEST-")
                    && file.getFileName().toString().endsWith(".xml")).sorted().toList()) {
                try {
                    String current = FileHashes.sha256(path);
                    if (!current.equals(before.get(path))) {
                        result.add(parseSuite(path));
                    }
                } catch (Exception ignored) {
                    // 损坏报告不作为成功证据，原始 Maven 结果仍用于错误分类。
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return List.copyOf(result);
    }

    private static Suite parseSuite(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        int tests = integer(root, "tests");
        int failures = integer(root, "failures");
        int errors = integer(root, "errors");
        int skipped = integer(root, "skipped");
        List<String> failed = new ArrayList<>();
        StringBuilder diagnostic = new StringBuilder();
        NodeList cases = root.getElementsByTagName("testcase");
        for (int index = 0; index < cases.getLength(); index++) {
            Element testCase = (Element) cases.item(index);
            Element failure = firstChild(testCase, "failure");
            if (failure == null) {
                failure = firstChild(testCase, "error");
            }
            if (failure != null) {
                String name = testCase.getAttribute("classname") + '#'
                        + testCase.getAttribute("name");
                failed.add(name);
                diagnostic.append(name).append(": ")
                        .append(failure.getAttribute("type")).append(' ')
                        .append(failure.getAttribute("message")).append(System.lineSeparator());
                String body = failure.getTextContent();
                if (body != null && !body.isBlank()) {
                    diagnostic.append(firstUsefulLines(body, 8)).append(System.lineSeparator());
                }
            }
        }
        return new Suite(root.getAttribute("name"), tests, failures, errors, skipped,
                List.copyOf(failed), bounded(diagnostic.toString()));
    }

    private static Element firstChild(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static int integer(Element element, String name) {
        try {
            return Integer.parseInt(element.getAttribute(name));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstUsefulLines(String text, int limit) {
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (String line : text.lines().toList()) {
            if (!line.isBlank()) {
                if (count++ >= limit) {
                    break;
                }
                result.append(line.strip()).append(System.lineSeparator());
            }
        }
        return result.toString().stripTrailing();
    }

    private static TestExecution result(
            TestAction action,
            TestStatus status,
            int exitCode,
            List<String> failed,
            int tests,
            int failures,
            int errors,
            int skipped,
            String diagnostic,
            boolean truncated) {
        return new TestExecution(action.name(), status, exitCode, tests, failures, errors, skipped,
                failed, bounded(diagnostic), truncated, OffsetDateTime.now().toString());
    }

    private static String joinOutput(String stdout, String stderr) {
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;
        return (out + System.lineSeparator() + err).strip();
    }

    private static String bounded(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_DIAGNOSTIC_CHARS) {
            return value;
        }
        return value.substring(0, MAX_DIAGNOSTIC_CHARS - 32)
                + "\n...[诊断已截断，原始字符数=" + value.length() + ']';
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private record Suite(
            String name,
            int tests,
            int failures,
            int errors,
            int skipped,
            List<String> failedTests,
            String diagnostic) {
    }

    @FunctionalInterface
    interface CommandExecutor {
        ExecuteCommandTool.CommandResult execute(
                Path projectRoot,
                String qualifiedTestClass,
                boolean targetOnly,
                Duration timeout) throws IOException, InterruptedException;
    }
}
