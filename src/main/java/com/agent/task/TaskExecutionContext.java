package com.agent.task;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.agent.history.HistorySanitizer;
import com.agent.task.ProjectInspector.TargetDescriptor;
import com.agent.task.TaskModels.Attempt;
import com.agent.task.TaskModels.FileState;
import com.agent.task.TaskModels.Round;
import com.agent.task.TaskModels.RoundStatus;
import com.agent.task.TaskModels.RoundType;
import com.agent.task.TaskModels.Task;
import com.agent.task.TaskModels.TestAction;
import com.agent.task.TaskModels.TestExecution;
import com.agent.task.TaskModels.TestStatus;
import com.agent.task.TaskModels.ToolEvent;
import com.agent.task.TaskModels.ValidationRecord;
import com.agent.tool.WriteFileTool;

/** 当前轮次的唯一可变执行上下文，集中实施工具权限、状态机和回滚。 */
public final class TaskExecutionContext {
    public static final int MAX_MODEL_CALLS = 15;
    public static final int MAX_TOOL_CALLS = 30;
    public static final int MAX_ATTEMPTS = 3;

    private final TaskStore store;
    private final HistorySanitizer sanitizer = new HistorySanitizer();
    private final CandidateValidator validator = new CandidateValidator();
    private final MavenTestRunner testRunner;
    private final PrintWriter output;
    private final Path targetSource;
    private final Path testFile;
    private final Path pom;
    private final String initialTargetHash;
    private final String initialPomHash;
    private final String baselineTestContent;
    private final boolean baselineTestTooLarge;
    private final Map<String, TestExecution> executionCache = new LinkedHashMap<>();
    private final TestFileTransaction transaction;
    private Task task;
    private Round round;
    private boolean targetRead;
    private boolean testStateConfirmed;
    private boolean frameworkConfirmed = true;
    private String fatalMessage;

    public TaskExecutionContext(
            TaskStore store,
            Task task,
            Round round,
            TargetDescriptor descriptor,
            PrintWriter output) throws IOException {
        this.store = store;
        this.task = task;
        this.round = round;
        this.output = output;
        this.targetSource = store.projectRoot().resolve(task.targetSource()).normalize();
        this.testFile = store.projectRoot().resolve(task.testFile()).normalize();
        this.pom = store.projectRoot().resolve("pom.xml");
        this.initialTargetHash = FileHashes.sha256(targetSource);
        this.initialPomHash = FileHashes.sha256(pom);
        this.baselineTestTooLarge = Files.isRegularFile(testFile, LinkOption.NOFOLLOW_LINKS)
                && Files.size(testFile) > CandidateValidator.MAX_BYTES;
        this.baselineTestContent = Files.isRegularFile(testFile, LinkOption.NOFOLLOW_LINKS)
                && !baselineTestTooLarge
                        ? Files.readString(testFile, StandardCharsets.UTF_8) : null;
        this.testStateConfirmed = !Files.exists(testFile, LinkOption.NOFOLLOW_LINKS);
        this.testRunner = new MavenTestRunner(store.projectRoot(), descriptor.qualifiedTestClass());
        this.transaction = round.type() == RoundType.CHANGE
                ? new TestFileTransaction(store, task, round.number()) : null;
        if (round.status() == RoundStatus.PAUSED) {
            setStatus(RoundStatus.EXPLORING, "已恢复暂停轮次");
        } else if (round.status() == RoundStatus.PROJECT_CHECKED) {
            setStatus(RoundStatus.EXPLORING, "项目检查完成，开始收集测试证据");
        }
    }

    public Path projectRoot() {
        return store.projectRoot();
    }

    public Task task() {
        return task;
    }

    public Round round() {
        return round;
    }

    public boolean isTerminal() {
        return isTerminal(round.status());
    }

    public boolean isSuccessful() {
        return round.status() == RoundStatus.ANALYZED
                || round.status() == RoundStatus.VERIFIED
                || round.status() == RoundStatus.SUCCEEDED;
    }

    public boolean beginModelCall() throws IOException {
        if (round.modelCallCount() >= MAX_MODEL_CALLS || isTerminal()) {
            return false;
        }
        round = TaskManager.copyRound(round, round.status(), round.outcomeSummary(),
                round.attempts(), round.baselineResult(), round.targetResult(),
                round.regressionResult(), round.fileState(), round.modelCallCount() + 1,
                round.toolCallCount(), round.toolEvents());
        persist(false, null, null);
        return true;
    }

    public boolean beginToolCall() throws IOException {
        if (round.toolCallCount() >= MAX_TOOL_CALLS || isTerminal()) {
            return false;
        }
        round = TaskManager.copyRound(round, round.status(), round.outcomeSummary(),
                round.attempts(), round.baselineResult(), round.targetResult(),
                round.regressionResult(), round.fileState(), round.modelCallCount(),
                round.toolCallCount() + 1, round.toolEvents());
        persist(false, null, null);
        return true;
    }

    public void recordTokenUsage(int promptTokens, int completionTokens) throws IOException {
        round = TaskManager.addTokenUsage(round, promptTokens, completionTokens);
        persist(false, null, null);
    }

    public void recordToolEvent(
            String name,
            String parameters,
            String result,
            long durationMs,
            boolean blocked) throws IOException {
        List<ToolEvent> events = new ArrayList<>(round.toolEvents());
        String normalized = result == null ? "" : result;
        String status = blocked ? "blocked"
                : normalized.contains("失败") || normalized.contains("拒绝")
                        || normalized.contains("ERROR") ? "failure" : "success";
        events.add(new ToolEvent(
                events.size() + 1, name, OffsetDateTime.now().toString(), durationMs,
                TaskManager.bounded(sanitizer.sanitizeText(parameters), 500), status,
                normalized.length(), summarizeToolResult(name, normalized), true));
        round = TaskManager.copyRound(round, round.status(), round.outcomeSummary(),
                round.attempts(), round.baselineResult(), round.targetResult(),
                round.regressionResult(), round.fileState(), round.modelCallCount(),
                round.toolCallCount(), events);
        persist(isTerminal(), null, currentValidation(validationAppliesToDisk()));
    }

    /** 批量调用中前序动作终止轮次后，仍为剩余调用保存配对的 blocked 事件。 */
    public void recordSkippedToolCall(String name, String parameters, String reason) throws IOException {
        if (round.toolCallCount() < MAX_TOOL_CALLS) {
            round = TaskManager.copyRound(round, round.status(), round.outcomeSummary(),
                    round.attempts(), round.baselineResult(), round.targetResult(),
                    round.regressionResult(), round.fileState(), round.modelCallCount(),
                    round.toolCallCount() + 1, round.toolEvents());
        }
        List<ToolEvent> events = new ArrayList<>(round.toolEvents());
        if (events.size() < MAX_TOOL_CALLS) {
            events.add(new ToolEvent(events.size() + 1, name, OffsetDateTime.now().toString(),
                    0, TaskManager.bounded(parameters, 500), "blocked", reason.length(),
                    TaskManager.bounded(reason, 1_000), true));
        }
        round = TaskManager.copyRound(round, round.status(), round.outcomeSummary(),
                round.attempts(), round.baselineResult(), round.targetResult(),
                round.regressionResult(), round.fileState(), round.modelCallCount(),
                round.toolCallCount(), events);
        persist(true, null, currentValidation(validationAppliesToDisk()));
    }

    public void noteRead(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(targetSource)) {
            targetRead = true;
        }
        if (normalized.equals(testFile)) {
            testStateConfirmed = true;
        }
        if (normalized.equals(pom)) {
            frameworkConfirmed = true;
        }
    }

    public String writeCandidate(String content) {
        try {
            verifyCriticalFiles();
            if (content == null || content.isBlank()) {
                return "写入文件失败: 候选内容不能为空或只含空白；本次未计入候选次数";
            }
            if (round.type() != RoundType.CHANGE) {
                return "写入文件失败: 当前 " + round.type() + " 轮次没有写入权限";
            }
            if (round.status() != RoundStatus.BASELINE_ACCEPTED
                    && round.status() != RoundStatus.REPAIRING) {
                return "写入文件失败: 当前阶段必须先接受基线测试，实际为 " + round.status();
            }
            if (!targetRead || !testStateConfirmed || !frameworkConfirmed) {
                return "写入文件失败: 写入前必须读取目标源码、确认测试文件状态和测试框架";
            }
            if (round.attemptCount() >= MAX_ATTEMPTS) {
                failAndRollback("候选次数已经达到 3 次上限", false);
                return "写入文件失败: 候选次数已经达到 3 次上限";
            }
            boolean baselineExists = round.fileState() != null && round.fileState().baselineExisted();
            if (baselineExists && task.committedTestHash() == null && !task.overwriteAllowed()) {
                return "写入文件失败: 已有测试文件未获得 --overwrite 许可";
            }
            if (baselineTestTooLarge) {
                failAndRollback("已有测试文件超过 24 KiB，第一版拒绝修改", false);
                return "写入文件失败: 已有测试文件超过 24 KiB";
            }

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String hash = FileHashes.sha256(bytes);
            if (round.attempts().stream().anyMatch(attempt -> attempt.sha256().equals(hash))) {
                failAndRollback("检测到完全相同的候选，已停止本轮", false);
                return "写入文件失败: 与本轮已有候选完全相同，已回滚";
            }

            int number = round.attemptCount() + 1;
            Path attachment = store.roundDirectory(task.id(), round.number())
                    .resolve("attempts/attempt-%02d.java".formatted(number));
            WriteFileTool.WriteResult attachmentResult = new WriteFileTool().writeFile(attachment, content);
            String relativeAttachment = normalize(store.taskDirectory(task.id()).relativize(attachment));
            String targetSimpleName = task.targetClass().substring(task.targetClass().lastIndexOf('.') + 1);
            String packageName = task.targetClass().contains(".")
                    ? task.targetClass().substring(0, task.targetClass().lastIndexOf('.')) : "";
            CandidateValidator.Validation validation = validator.validate(
                    content, packageName, task.testClass(), targetSimpleName,
                    baselineTestContent);
            Attempt attempt = new Attempt(
                    number, relativeAttachment, hash, attachmentResult.bytesWritten(),
                    validation.passed(), validation.summary(), validation.removedMethods(), null,
                    validation.passed() ? "" : validation.summary());
            List<Attempt> attempts = new ArrayList<>(round.attempts());
            attempts.add(attempt);

            if (!validation.passed()) {
                RoundStatus next = validation.dangerous() || number >= MAX_ATTEMPTS
                        ? RoundStatus.FAILED : RoundStatus.REPAIRING;
                round = TaskManager.copyRound(round, next, validation.summary(), attempts,
                        round.baselineResult(), round.targetResult(), round.regressionResult(),
                        round.fileState(), round.modelCallCount(), round.toolCallCount(),
                        round.toolEvents());
                if (next == RoundStatus.FAILED) {
                    failAndRollback(validation.summary(), validation.dangerous());
                } else {
                    persist(false, null, currentValidation(false));
                }
                return "候选静态校验失败"
                        + "\nround: " + round.number()
                        + "\nattempt: " + number
                        + "\ndangerous: " + validation.dangerous()
                        + "\nreason: " + validation.summary();
            }

            TestFileTransaction.WriteOutcome written = transaction.write(content);
            FileState fileState = new FileState(
                    round.fileState().baselineExisted(), round.fileState().baselineHash(),
                    written.hash(), "CANDIDATE_WRITTEN", written.atomic());
            round = TaskManager.copyRound(round, RoundStatus.CANDIDATE_WRITTEN,
                    "候选 " + number + " 已通过静态校验并写入", attempts,
                    round.baselineResult(), null, null, fileState,
                    round.modelCallCount(), round.toolCallCount(), round.toolEvents());
            executionCache.clear();
            persist(false, null, currentValidation(false));
            output.println("[candidate] round=" + round.number() + " attempt=" + number
                    + " bytes=" + written.bytes() + " static=passed atomic=" + written.atomic());
            return "写入候选成功"
                    + "\npath: " + task.testFile()
                    + "\nround: " + round.number()
                    + "\nattempt: " + number
                    + "\ncreated: " + written.created()
                    + "\nbytes: " + written.bytes()
                    + "\nsha256: " + written.hash()
                    + "\natomic: " + written.atomic()
                    + "\nremoved_methods: " + validation.removedMethods()
                    + "\nstatic_validation: PASSED";
        } catch (TestFileTransaction.RollbackConflictException e) {
            markRollbackConflict(e.getMessage());
            return "写入文件失败: ROLLBACK_CONFLICT: " + e.getMessage();
        } catch (Exception e) {
            safeFail("候选写入异常: " + safeMessage(e));
            return "写入文件失败: " + safeMessage(e);
        }
    }

    public String execute(TestAction action) {
        try {
            verifyCriticalFiles();
            String policyError = validateAction(action);
            if (policyError != null) {
                return "执行测试失败: " + policyError;
            }
            String cacheKey = action + ":" + FileHashes.sha256(testFile) + ':'
                    + FileHashes.sha256(targetSource) + ':' + FileHashes.sha256(pom);
            TestExecution cached = executionCache.get(cacheKey);
            if (cached != null) {
                return formatExecution(cached, true);
            }

            output.println("[test] action=" + action + " status=running");
            TestExecution result = testRunner.run(action);
            executionCache.put(cacheKey, result);
            output.println("[test] action=" + action + " status=" + result.status()
                    + " tests=" + result.tests() + " failures=" + result.failures()
                    + " errors=" + result.errors());
            applyExecution(action, result);
            return formatExecution(result, false);
        } catch (TestFileTransaction.RollbackConflictException e) {
            markRollbackConflict(e.getMessage());
            return "执行测试失败: ROLLBACK_CONFLICT: " + e.getMessage();
        } catch (Exception e) {
            safeFail("测试执行异常: " + safeMessage(e));
            return "执行测试失败: " + safeMessage(e);
        }
    }

    public boolean completeAnalysis(String answer) throws IOException {
        if (round.type() != RoundType.ANALYSIS || !targetRead) {
            return false;
        }
        round = TaskManager.copyRound(round, RoundStatus.ANALYZED,
                TaskManager.bounded(sanitizer.sanitizeText(answer), 4_000),
                round.attempts(), null, null, null, round.fileState(),
                round.modelCallCount(), round.toolCallCount(), round.toolEvents());
        persist(true, null, null);
        return true;
    }

    public String completionRequirement() {
        return switch (round.type()) {
            case ANALYSIS -> targetRead
                    ? "请基于已经读取的证据给出分析结论，并说明未读取内容；不要写文件或运行 Maven。"
                    : "尚未读取目标源码，必须先调用 read_file 读取 " + task.targetSource();
            case VERIFY -> "验证轮次只有 target_test 和 regression_test 都在本轮通过后才能结束；当前阶段="
                    + round.status();
            case CHANGE -> "修改轮次必须依次完成目标源码读取、baseline_test、write_file、target_test 和 regression_test；当前阶段="
                    + round.status() + "，候选=" + round.attemptCount() + '/' + MAX_ATTEMPTS;
        };
    }

    public void finishAtLimit(String reason) {
        try {
            if (transaction != null && transaction.current() != null) {
                failAndRollback(reason, false);
            } else {
                round = TaskManager.copyRound(round, RoundStatus.PAUSED, reason,
                        round.attempts(), round.baselineResult(), round.targetResult(),
                        round.regressionResult(), round.fileState(), round.modelCallCount(),
                        round.toolCallCount(), round.toolEvents());
                persist(false, null, currentValidation(false));
            }
        } catch (Exception e) {
            fatalMessage = reason + "；保存/回滚失败: " + safeMessage(e);
        }
    }

    public void abort(String reason) {
        try {
            if (transaction != null && transaction.current() != null) {
                transaction.rollback();
            }
            round = TaskManager.copyRound(round, RoundStatus.ABORTED, reason,
                    round.attempts(), round.baselineResult(), round.targetResult(),
                    round.regressionResult(), rolledBackFileState(), round.modelCallCount(),
                    round.toolCallCount(), round.toolEvents());
            persist(true, null, currentValidation(false));
        } catch (TestFileTransaction.RollbackConflictException e) {
            markRollbackConflict(e.getMessage());
        } catch (Exception e) {
            fatalMessage = "中断处理失败: " + safeMessage(e);
        }
    }

    public String finalReport(String modelAnswer) {
        StringBuilder report = new StringBuilder()
                .append(modelAnswer == null ? "" : modelAnswer.strip())
                .append("\n\n任务结果:")
                .append("\n任务 ID: ").append(task.id())
                .append("\n轮次: ").append(round.number())
                .append("\n类型/状态: ").append(round.type()).append('/').append(round.status())
                .append("\n目标类: ").append(task.targetClass())
                .append("\n测试文件: ").append(task.testFile())
                .append("\n候选次数: ").append(round.attemptCount())
                .append("\n目标测试: ").append(testLabel(round.targetResult()))
                .append("\n完整回归: ").append(testLabel(round.regressionResult()))
                .append("\n文件状态: ").append(round.fileState() == null ? "未知" : round.fileState().state())
                .append("\nToken 总量: 输入=").append(round.promptTokenCount())
                .append("，输出=").append(round.completionTokenCount())
                .append("，合计=").append(round.totalTokenCount())
                .append("\n工具调用: ").append(round.toolEvents().stream()
                        .map(ToolEvent::name).distinct().toList());
        if (round.type() == RoundType.ANALYSIS) {
            report.append("\n文件修改: 无\nMaven 测试: 未执行");
        } else if (round.type() == RoundType.VERIFY) {
            report.append("\n候选生成: 无");
        }
        report.append("\n人工复核: 测试通过只能证明已执行断言通过，仍需检查断言是否准确表达业务要求。")
                .append("\n安全边界: Maven 与 JUnit 在本机执行，本程序不是操作系统级沙箱。");
        List<String> removedMethods = round.status() == RoundStatus.SUCCEEDED
                && !round.attempts().isEmpty()
                        ? round.attempts().get(round.attempts().size() - 1).removedMethods()
                        : List.of();
        if (!removedMethods.isEmpty()) {
            report.append("\n本轮移除的已有测试方法: ")
                    .append(String.join(", ", removedMethods));
        }
        if (fatalMessage != null) {
            report.append("\n警告: ").append(fatalMessage);
        }
        return report.toString();
    }

    private void applyExecution(TestAction action, TestExecution result) throws IOException {
        switch (action) {
            case baseline_test -> applyBaseline(result);
            case target_test -> applyTarget(result);
            case regression_test -> applyRegression(result);
        }
    }

    private void applyBaseline(TestExecution result) throws IOException {
        TestExecution stored = persistable(result);
        boolean accepted = result.status() == TestStatus.PASSED || acceptableTargetBaseline(result);
        if (accepted) {
            String message = result.status() == TestStatus.PASSED
                    ? "干净基线通过" : "基线只涉及当前目标测试失败，允许修正";
            round = TaskManager.copyRound(round, RoundStatus.BASELINE_ACCEPTED, message,
                    round.attempts(), stored, null, null, round.fileState(),
                    round.modelCallCount(), round.toolCallCount(), round.toolEvents());
            persist(false, null, currentValidation(false));
        } else {
            round = TaskManager.copyRound(round, RoundStatus.FAILED,
                    "基线不满足写入条件: " + result.status(), round.attempts(), stored,
                    null, null, round.fileState(), round.modelCallCount(),
                    round.toolCallCount(), round.toolEvents());
            persist(true, null, currentValidation(false));
        }
    }

    private void applyTarget(TestExecution result) throws IOException {
        TestExecution stored = persistable(result);
        if (result.status() == TestStatus.PASSED) {
            round = TaskManager.copyRound(round, RoundStatus.TARGET_TEST_PASSED,
                    "目标测试通过", updateLastAttempt(stored, ""), round.baselineResult(),
                    stored, null, round.fileState(), round.modelCallCount(),
                    round.toolCallCount(), round.toolEvents());
            persist(false, null, currentValidation(false));
            return;
        }
        if (round.type() == RoundType.CHANGE && isRepairable(result)
                && round.attemptCount() < MAX_ATTEMPTS) {
            round = TaskManager.copyRound(round, RoundStatus.REPAIRING,
                    "目标测试失败，等待修正: " + result.status(),
                    updateLastAttempt(stored, stored.diagnostic()), round.baselineResult(),
                    stored, null, round.fileState(), round.modelCallCount(),
                    round.toolCallCount(), round.toolEvents());
            persist(false, null, currentValidation(false));
        } else {
            round = TaskManager.copyRound(round, RoundStatus.FAILED,
                    "目标测试失败: " + result.status(), updateLastAttempt(stored, stored.diagnostic()),
                    round.baselineResult(), stored, null, round.fileState(),
                    round.modelCallCount(), round.toolCallCount(), round.toolEvents());
            if (round.type() == RoundType.CHANGE) {
                failAndRollback(round.outcomeSummary(), false);
            } else {
                persist(true, null, currentValidation(true));
            }
        }
    }

    private void applyRegression(TestExecution result) throws IOException {
        TestExecution stored = persistable(result);
        if (result.status() == TestStatus.PASSED) {
            if (round.type() == RoundType.CHANGE) {
                verifyCriticalFiles();
                String committedHash = FileHashes.sha256(testFile);
                round = TaskManager.copyRound(round, RoundStatus.REGRESSION_PASSED,
                        "目标测试和完整回归均通过，准备提交候选", round.attempts(),
                        round.baselineResult(), round.targetResult(), stored,
                        new FileState(round.fileState().baselineExisted(),
                                round.fileState().baselineHash(), committedHash, "COMMIT_PENDING",
                                round.fileState().atomicWrite()),
                        round.modelCallCount(), round.toolCallCount(), round.toolEvents());
                persist(false, null, currentValidation(true));
                transaction.markCommitReady();
                round = TaskManager.copyRound(round, RoundStatus.SUCCEEDED,
                        "目标测试和完整回归均通过，候选已提交", round.attempts(),
                        round.baselineResult(), round.targetResult(), stored,
                        new FileState(round.fileState().baselineExisted(),
                                round.fileState().baselineHash(), committedHash, "COMMITTED",
                                round.fileState().atomicWrite()),
                        round.modelCallCount(), round.toolCallCount(), round.toolEvents());
                persist(true, committedHash, currentValidation(true));
                transaction.finishCommit();
            } else {
                round = TaskManager.copyRound(round, RoundStatus.VERIFIED,
                        "目标测试和完整回归均通过", round.attempts(), null,
                        round.targetResult(), stored, round.fileState(), round.modelCallCount(),
                        round.toolCallCount(), round.toolEvents());
                persist(true, null, currentValidation(true));
            }
            return;
        }
        if (round.type() == RoundType.CHANGE && isRepairable(result)
                && round.attemptCount() < MAX_ATTEMPTS) {
            round = TaskManager.copyRound(round, RoundStatus.REPAIRING,
                    "完整回归失败，等待修正: " + result.status(),
                    updateLastAttempt(stored, stored.diagnostic()), round.baselineResult(),
                    round.targetResult(), stored, round.fileState(), round.modelCallCount(),
                    round.toolCallCount(), round.toolEvents());
            persist(false, null, currentValidation(false));
        } else {
            round = TaskManager.copyRound(round, RoundStatus.FAILED,
                    "完整回归失败: " + result.status(), updateLastAttempt(stored, stored.diagnostic()),
                    round.baselineResult(), round.targetResult(), stored, round.fileState(),
                    round.modelCallCount(), round.toolCallCount(), round.toolEvents());
            if (round.type() == RoundType.CHANGE) {
                failAndRollback(round.outcomeSummary(), false);
            } else {
                persist(true, null, currentValidation(true));
            }
        }
    }

    private String validateAction(TestAction action) {
        return switch (action) {
            case baseline_test -> round.type() != RoundType.CHANGE
                    ? "baseline_test 只允许 CHANGE 轮次"
                    : round.attemptCount() > 0 || round.status() != RoundStatus.EXPLORING
                            ? "基线只能在第一个候选写入前执行" : null;
            case target_test -> round.type() == RoundType.ANALYSIS
                    ? "ANALYSIS 轮次不能执行 Maven"
                    : round.type() == RoundType.CHANGE
                            && round.status() != RoundStatus.CANDIDATE_WRITTEN
                                    ? "CHANGE 轮次必须先写入通过静态校验的候选" : null;
            case regression_test -> round.status() != RoundStatus.TARGET_TEST_PASSED
                    ? "只有本轮目标测试通过后才能执行完整回归" : null;
        };
    }

    private boolean acceptableTargetBaseline(TestExecution result) {
        if (!round.fileState().baselineExisted()) {
            return false;
        }
        if (result.status() == TestStatus.TEST_FAILED && !result.failedTests().isEmpty()) {
            String qualified = task.targetClass().contains(".")
                    ? task.targetClass().substring(0, task.targetClass().lastIndexOf('.') + 1)
                            + task.testClass()
                    : task.testClass();
            return result.failedTests().stream().allMatch(name -> name.startsWith(qualified + '#'));
        }
        if (result.status() == TestStatus.COMPILATION_FAILED) {
            String normalized = result.diagnostic().replace('\\', '/');
            String fileName = Path.of(task.testFile()).getFileName().toString();
            return normalized.contains(task.testFile()) || normalized.contains(fileName)
                    && !normalized.contains(task.targetSource());
        }
        return false;
    }

    private void verifyCriticalFiles() throws IOException {
        if (!Objects.equals(initialTargetHash, FileHashes.sha256(targetSource))) {
            throw new IOException("目标源码在轮次中途发生变化");
        }
        if (!Objects.equals(initialPomHash, FileHashes.sha256(pom))) {
            throw new IOException("pom.xml 在轮次中途发生变化");
        }
        if (transaction != null && transaction.current() != null) {
            String expected = transaction.current().candidateHash() == null
                    ? transaction.current().baselineHash() : transaction.current().candidateHash();
            if (!Objects.equals(expected, FileHashes.sha256(testFile))) {
                throw new TestFileTransaction.RollbackConflictException(
                        "测试文件在轮次中途被外部修改");
            }
        }
    }

    private void failAndRollback(String reason, boolean dangerous) throws IOException {
        FileState fileState = round.fileState();
        if (transaction != null && transaction.current() != null) {
            round = TaskManager.copyRound(round, RoundStatus.ROLLING_BACK, reason,
                    round.attempts(), round.baselineResult(), round.targetResult(),
                    round.regressionResult(), fileState, round.modelCallCount(),
                    round.toolCallCount(), round.toolEvents());
            persist(false, null, currentValidation(false));
            try {
                transaction.rollback();
                fileState = rolledBackFileState();
            } catch (TestFileTransaction.RollbackConflictException e) {
                markRollbackConflict(e.getMessage());
                return;
            }
        }
        String outcome = (dangerous ? "危险候选已拒绝；" : "") + reason;
        round = TaskManager.copyRound(round, RoundStatus.FAILED, outcome,
                round.attempts(), round.baselineResult(), round.targetResult(),
                round.regressionResult(), fileState, round.modelCallCount(),
                round.toolCallCount(), round.toolEvents());
        persist(true, null, currentValidation(false));
    }

    private void safeFail(String reason) {
        try {
            failAndRollback(reason, false);
        } catch (Exception e) {
            fatalMessage = reason + "；回滚失败: " + safeMessage(e);
        }
    }

    private void markRollbackConflict(String reason) {
        try {
            round = TaskManager.copyRound(round, RoundStatus.ROLLBACK_CONFLICT,
                    "ROLLBACK_CONFLICT: " + reason, round.attempts(), round.baselineResult(),
                    round.targetResult(), round.regressionResult(),
                    new FileState(round.fileState().baselineExisted(),
                            round.fileState().baselineHash(), FileHashes.sha256(testFile),
                            "ROLLBACK_CONFLICT", round.fileState().atomicWrite()),
                    round.modelCallCount(), round.toolCallCount(), round.toolEvents());
            persist(true, null, currentValidation(false));
        } catch (Exception e) {
            fatalMessage = "回滚冲突且记录保存失败: " + safeMessage(e);
        }
    }

    private void setStatus(RoundStatus status, String outcome) throws IOException {
        round = TaskManager.copyRound(round, status, outcome, round.attempts(),
                round.baselineResult(), round.targetResult(), round.regressionResult(),
                round.fileState(), round.modelCallCount(), round.toolCallCount(), round.toolEvents());
        persist(false, null, null);
    }

    private void persist(boolean terminal, String committedHash, ValidationRecord validation)
            throws IOException {
        store.saveRound(task.id(), round);
        boolean validationSuccess = validation != null && validation.status() == TestStatus.PASSED;
        ValidationRecord lastSuccessful = validationSuccess
                ? validation : task.lastSuccessfulValidation();
        ValidationRecord latest = validation == null ? task.latestValidationAttempt() : validation;
        task = TaskManager.copyTask(task, Math.max(task.roundCount(), round.number()),
                terminal ? null : round.number(), round.type(), round.status(),
                committedHash == null ? task.committedTestHash() : committedHash,
                committedHash == null ? task.latestCommittedRound() : Integer.valueOf(round.number()),
                lastSuccessful, latest, round.outcomeSummary());
        store.saveTask(task);
    }

    private ValidationRecord currentValidation(boolean applicable) throws IOException {
        TestExecution latest = round.regressionResult() != null
                ? round.regressionResult() : round.targetResult();
        if (latest == null) {
            return null;
        }
        TestStatus status = round.regressionResult() != null
                && round.targetResult() != null
                && round.regressionResult().status() == TestStatus.PASSED
                && round.targetResult().status() == TestStatus.PASSED
                        ? TestStatus.PASSED : latest.status();
        return new ValidationRecord(
                round.number(), OffsetDateTime.now().toString(), FileHashes.sha256(targetSource),
                FileHashes.sha256(testFile), FileHashes.sha256(pom), status,
                status + " target=" + testLabel(round.targetResult())
                        + " regression=" + testLabel(round.regressionResult()), applicable);
    }

    private List<Attempt> updateLastAttempt(TestExecution result, String failure) {
        if (round.attempts().isEmpty()) {
            return round.attempts();
        }
        List<Attempt> attempts = new ArrayList<>(round.attempts());
        Attempt old = attempts.get(attempts.size() - 1);
        attempts.set(attempts.size() - 1, new Attempt(
                old.number(), old.attachment(), old.sha256(), old.bytes(),
                old.staticValidationPassed(), old.validationSummary(), old.removedMethods(), result,
                TaskManager.bounded(sanitizer.sanitizeText(failure), 1_000)));
        return attempts;
    }

    /** 为 rollback 等确定性命令终止修正状态，并恢复命令执行前的测试文件。 */
    public void terminateAndRollback(String reason) {
        safeFail(reason);
    }

    private FileState rolledBackFileState() throws IOException {
        return new FileState(round.fileState().baselineExisted(), round.fileState().baselineHash(),
                FileHashes.sha256(testFile), "ROLLED_BACK", round.fileState().atomicWrite());
    }

    private static boolean isRepairable(TestExecution result) {
        return result.status() == TestStatus.COMPILATION_FAILED
                || result.status() == TestStatus.TEST_FAILED
                || result.status() == TestStatus.NO_TESTS;
    }

    private static boolean isTerminal(RoundStatus status) {
        return status == RoundStatus.ANALYZED || status == RoundStatus.VERIFIED
                || status == RoundStatus.SUCCEEDED || status == RoundStatus.FAILED
                || status == RoundStatus.ABORTED || status == RoundStatus.ROLLBACK_CONFLICT;
    }

    private boolean validationAppliesToDisk() {
        return round.type() == RoundType.VERIFY
                || round.status() == RoundStatus.SUCCEEDED
                || round.status() == RoundStatus.VERIFIED;
    }

    private static String formatExecution(TestExecution result, boolean cached) {
        return "测试执行结果:"
                + "\naction: " + result.action()
                + "\nstatus: " + result.status()
                + "\nexit_code: " + result.exitCode()
                + "\ntests: " + result.tests()
                + "\nfailures: " + result.failures()
                + "\nerrors: " + result.errors()
                + "\nskipped: " + result.skipped()
                + "\nfailed_tests: " + result.failedTests()
                + "\ncached: " + cached
                + "\ndiagnostic:\n" + result.diagnostic();
    }

    private TestExecution persistable(TestExecution result) {
        List<String> useful = result.diagnostic().lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .filter(line -> line.contains("ERROR") || line.contains("error")
                        || line.contains("expected") || line.contains("actual")
                        || line.contains("Exception") || line.contains("Caused by")
                        || line.contains(".java:") || line.startsWith("测试"))
                .limit(8)
                .toList();
        String diagnostic = useful.isEmpty()
                ? (result.status() == TestStatus.PASSED ? "测试执行通过。" : "无可持久化的结构化诊断")
                : String.join(System.lineSeparator(), useful);
        return new TestExecution(
                result.action(), result.status(), result.exitCode(), result.tests(),
                result.failures(), result.errors(), result.skipped(), result.failedTests(),
                TaskManager.bounded(sanitizer.sanitizeText(diagnostic), 1_000),
                result.outputTruncated(), result.executedAt());
    }

    private String summarizeToolResult(String tool, String result) {
        List<String> allowed = switch (tool) {
            case "list_files" -> List.of("path:", "max_depth:", "returned_entries:",
                    "has_more:", "next_offset:", "scan_limited:", "visited_entries:");
            case "read_file" -> List.of("cache_id:", "path:", "offset:", "limit:",
                    "total_lines:", "returned_range:", "has_more:", "next_offset:",
                    "fingerprint:", "line:", "line_length:");
            case "search_code" -> List.of("returned_matches:", "has_more:",
                    "next_offset:", "scan_limited:", "scanned_files:", "scanned_bytes:");
            case "write_file" -> List.of("path:", "round:", "attempt:", "created:",
                    "bytes:", "sha256:", "atomic:", "removed_methods:",
                    "static_validation:", "reason:");
            case "execute_command" -> List.of("action:", "status:", "exit_code:",
                    "tests:", "failures:", "errors:", "skipped:", "failed_tests:", "cached:");
            default -> List.of();
        };
        List<String> selected = result.lines()
                .map(String::strip)
                .filter(line -> allowed.stream().anyMatch(line::startsWith))
                .limit(20)
                .toList();
        if (!selected.isEmpty()) {
            return TaskManager.bounded(sanitizer.sanitizeText(String.join("; ", selected)), 1_000);
        }
        String firstLine = result.lines().findFirst().orElse("").strip();
        return TaskManager.bounded(sanitizer.sanitizeText(firstLine), 500);
    }

    private static String testLabel(TestExecution result) {
        return result == null ? "未执行" : result.status() + " tests=" + result.tests()
                + " failures=" + result.failures() + " errors=" + result.errors();
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
