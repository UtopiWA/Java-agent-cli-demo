package com.agent.task;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.agent.history.HistorySanitizer;
import com.agent.task.ProjectInspector.TargetDescriptor;
import com.agent.task.TaskModels.Attempt;
import com.agent.task.TaskModels.FileState;
import com.agent.task.TaskModels.Round;
import com.agent.task.TaskModels.RoundStatus;
import com.agent.task.TaskModels.RoundType;
import com.agent.task.TaskModels.Task;
import com.agent.task.TaskModels.TestAction;
import com.agent.task.TaskModels.ValidationRecord;

/** 创建、列出、恢复和展示以目标类为单位的长期测试维护任务。 */
public final class TaskManager {
    private final TaskStore store;
    private final ProjectInspector inspector;
    private final HistorySanitizer sanitizer;

    public TaskManager(Path projectRoot) {
        this.store = new TaskStore(projectRoot);
        this.inspector = new ProjectInspector(projectRoot);
        this.sanitizer = new HistorySanitizer();
    }

    public CreateResult create(
            String target,
            boolean overwrite,
            boolean fresh) throws IOException {
        TargetDescriptor descriptor = inspector.inspect(target);
        List<Task> existing = store.findByTarget(descriptor.targetSource());
        if (!fresh && !existing.isEmpty()) {
            Task task = existing.get(0);
            String displayId = displayId(task.id());
            return new CreateResult(task, descriptor, false,
                    "目标类已有任务 " + displayId + "，请使用 resume " + displayId);
        }

        String now = OffsetDateTime.now().toString();
        Task task = new Task(
                TaskModels.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                store.projectRoot().toString(),
                descriptor.targetClass(),
                descriptor.targetSource(),
                descriptor.testClass(),
                descriptor.testFile(),
                now, now, 0, null, null, RoundStatus.NONE,
                overwrite, "", null, null, null, null, "尚未开始执行轮次");
        store.create(task);
        String message = "已创建任务 " + displayId(task.id())
                + "\n目标类: " + task.targetClass()
                + "\n测试文件: " + task.testFile()
                + "\n记录目录: " + normalize(store.taskDirectory(task.id()))
                + (descriptor.testExists() && !overwrite
                        ? "\n注意：测试文件已经存在，本任务默认只读；如需接管请重新 new 并使用 --overwrite --fresh。"
                        : "");
        return new CreateResult(task, descriptor, true, message);
    }

    public Task load(String idOrPrefix) throws IOException {
        Task task = store.load(idOrPrefix);
        TargetDescriptor descriptor = inspector.inspect(task.targetSource());
        if (!descriptor.targetClass().equals(task.targetClass())
                || !descriptor.testFile().equals(task.testFile())) {
            throw new IOException("任务目标与当前项目结构不一致");
        }
        if (task.activeRound() != null) {
            TestFileTransaction.RecoveryResult recovery = TestFileTransaction.recover(
                    store, task, task.activeRound());
            if (recovery.message().startsWith("ROLLBACK_CONFLICT")) {
                Round round = store.loadRound(task.id(), task.activeRound());
                Round conflict = copyRound(round, RoundStatus.ROLLBACK_CONFLICT,
                        recovery.message(), round.attempts(), round.baselineResult(),
                        round.targetResult(), round.regressionResult(), round.fileState(),
                        round.modelCallCount(), round.toolCallCount(), round.toolEvents());
                saveRoundAndTask(task, conflict, recovery.message(), true, null, null);
                throw new IOException(recovery.message());
            }
            if (recovery.recovered()) {
                Round recoveredRound = store.loadRound(task.id(), task.activeRound());
                if (recovery.rolledBack()) {
                    Round failed = copyRound(recoveredRound, RoundStatus.FAILED,
                            recovery.message(), recoveredRound.attempts(),
                            recoveredRound.baselineResult(), recoveredRound.targetResult(),
                            recoveredRound.regressionResult(),
                            new FileState(recoveredRound.fileState().baselineExisted(),
                                    recoveredRound.fileState().baselineHash(),
                                    FileHashes.sha256(store.projectRoot().resolve(task.testFile())),
                                    "ROLLED_BACK", recoveredRound.fileState().atomicWrite()),
                            recoveredRound.modelCallCount(), recoveredRound.toolCallCount(),
                            recoveredRound.toolEvents());
                    saveRoundAndTask(task, failed, recovery.message(), true, null,
                            validation(task, failed, false));
                } else if (recoveredRound.status() == RoundStatus.REGRESSION_PASSED
                        || recoveredRound.status() == RoundStatus.SUCCEEDED) {
                    String committed = FileHashes.sha256(store.projectRoot().resolve(task.testFile()));
                    Round succeeded = copyRound(recoveredRound, RoundStatus.SUCCEEDED,
                            "崩溃恢复后补全候选提交", recoveredRound.attempts(),
                            recoveredRound.baselineResult(), recoveredRound.targetResult(),
                            recoveredRound.regressionResult(),
                            new FileState(recoveredRound.fileState().baselineExisted(),
                                    recoveredRound.fileState().baselineHash(), committed,
                                    "COMMITTED", recoveredRound.fileState().atomicWrite()),
                            recoveredRound.modelCallCount(), recoveredRound.toolCallCount(),
                            recoveredRound.toolEvents());
                    saveRoundAndTask(task, succeeded, succeeded.outcomeSummary(), true,
                            committed, validation(task, succeeded, true));
                }
                task = store.load(task.id());
            }
        }
        validateRoundConsistency(task);
        validateAttachments(task);
        return store.load(task.id());
    }

    public Round startRound(Task task, RoundType type, String request) throws IOException {
        return startRound(task, type, request, 0, 0);
    }

    public Round startRound(
            Task task,
            RoundType type,
            String request,
            int promptTokens,
            int completionTokens) throws IOException {
        if (task.activeRound() != null) {
            Round active = store.loadRound(task.id(), task.activeRound());
            if (active.status() == RoundStatus.PAUSED) {
                if (promptTokens > 0 || completionTokens > 0) {
                    active = addTokenUsage(active, promptTokens, completionTokens);
                    store.saveRound(task.id(), active);
                }
                return active;
            }
            throw new IOException("任务存在未结束轮次: " + active.number() + '/' + active.status());
        }
        int number = task.roundCount() + 1;
        String now = OffsetDateTime.now().toString();
        Path test = store.projectRoot().resolve(task.testFile());
        boolean existed = Files.exists(test, LinkOption.NOFOLLOW_LINKS);
        String hash = existed ? FileHashes.sha256(test) : null;
        Round round = new Round(
                TaskModels.SCHEMA_VERSION, number, now, now,
                RoundStatus.PROJECT_CHECKED, type,
                bounded(sanitizer.sanitizeText(request), 2_000), "", "",
                0, 0, 0, Math.max(0, promptTokens), Math.max(0, completionTokens),
                List.of(), List.of(), null, null, null,
                new FileState(existed, hash, hash, "UNCHANGED", true));
        store.saveRound(task.id(), round);
        Task updated = copyTask(task, number, number, type, round.status(),
                task.committedTestHash(), task.latestCommittedRound(),
                task.lastSuccessfulValidation(), task.latestValidationAttempt(),
                "轮次 " + number + " 已创建");
        store.saveTask(updated);
        return round;
    }

    public String history(int offset, int limit) throws IOException {
        TaskStore.ListResult page = store.list(offset, limit);
        List<HistoryRow> rows = new ArrayList<>();
        for (Task task : page.tasks()) {
            rows.add(new HistoryRow(
                    displayId(task.id()),
                    (task.latestRoundType() == null ? "NONE" : task.latestRoundType())
                            + "/" + task.latestRoundStatus(),
                    validationState(task),
                    Integer.toString(task.roundCount()),
                    task.targetClass()));
        }
        int idWidth = columnWidth("ID", rows.stream().map(HistoryRow::id).toList());
        int roundWidth = columnWidth("ROUND", rows.stream().map(HistoryRow::round).toList());
        int validationWidth = columnWidth(
                "VALIDATION", rows.stream().map(HistoryRow::validation).toList());
        int countWidth = columnWidth("COUNT", rows.stream().map(HistoryRow::count).toList());
        StringBuilder output = new StringBuilder()
                .append(padRight("ID", idWidth)).append("  ")
                .append(padRight("ROUND", roundWidth)).append("  ")
                .append(padRight("VALIDATION", validationWidth)).append("  ")
                .append(padLeft("COUNT", countWidth)).append("  TARGET");
        for (HistoryRow row : rows) {
            output.append('\n').append(padRight(row.id(), idWidth)).append("  ")
                    .append(padRight(row.round(), roundWidth)).append("  ")
                    .append(padRight(row.validation(), validationWidth)).append("  ")
                    .append(padLeft(row.count(), countWidth)).append("  ")
                    .append(row.target());
        }
        output.append("\nhas_more: ").append(page.hasMore())
                .append("\nnext_offset: ").append(page.nextOffset() == null ? "null" : page.nextOffset());
        if (page.skippedTasks() > 0) {
            output.append("\n警告：跳过损坏任务 ").append(page.skippedTasks()).append(" 个");
        }
        return output.toString();
    }

    public String show(String idOrPrefix, Integer roundNumber) throws IOException {
        Task task = store.load(idOrPrefix);
        if (roundNumber != null) {
            return formatRound(task, store.loadRound(task.id(), roundNumber), true);
        }
        StringBuilder output = new StringBuilder()
                .append("任务 ID: ").append(task.id())
                .append("\n目标类: ").append(task.targetClass())
                .append("\n源码: ").append(task.targetSource())
                .append("\n测试文件: ").append(task.testFile())
                .append("\n最近提交哈希: ").append(value(task.committedTestHash()))
                .append("\n验证状态: ").append(validationState(task))
                .append("\n持久约束: ").append(task.persistentConstraints().isBlank()
                        ? "无" : task.persistentConstraints())
                .append("\n总轮次: ").append(task.roundCount());
        List<Round> rounds = store.recentRounds(task.id(), 10);
        for (Round round : rounds) {
            output.append("\n- #").append(round.number()).append(' ')
                    .append(round.type()).append('/').append(round.status())
                    .append(" 候选=").append(round.attemptCount())
                    .append(" 请求=").append(bounded(round.requestSummary(), 120));
        }
        if (!rounds.isEmpty()) {
            output.append("\n\n最新轮次详情:\n").append(formatRound(task, rounds.get(0), false));
        }
        output.append("\n下一步: 输入分析、验证或修改要求；切换目标请使用 new/resume。");
        return output.toString();
    }

    public void delete(String idOrPrefix) throws IOException {
        Task task = store.load(idOrPrefix);
        if (task.activeRound() != null) {
            TestFileTransaction.RecoveryResult recovery = TestFileTransaction.recover(
                    store, task, task.activeRound());
            if (recovery.message().startsWith("ROLLBACK_CONFLICT")) {
                throw new IOException(recovery.message());
            }
        }
        store.delete(task.id());
    }

    public Task addPersistentConstraint(Task task, String constraint) throws IOException {
        String sanitized = sanitizer.sanitizeText(constraint == null ? "" : constraint.strip());
        String combined = task.persistentConstraints().isBlank()
                ? sanitized : task.persistentConstraints() + System.lineSeparator() + sanitized;
        Task updated = new Task(
                task.schemaVersion(), task.id(), task.projectRoot(), task.targetClass(),
                task.targetSource(), task.testClass(), task.testFile(), task.createdAt(),
                OffsetDateTime.now().toString(), task.roundCount(), task.activeRound(),
                task.latestRoundType(), task.latestRoundStatus(), task.overwriteAllowed(),
                bounded(combined, 4_000), task.committedTestHash(), task.latestCommittedRound(),
                task.lastSuccessfulValidation(), task.latestValidationAttempt(),
                task.latestRoundSummary());
        store.saveTask(updated);
        return updated;
    }

    /** 把历史成功版本作为新 CHANGE 轮次的唯一候选，验证失败时恢复命令前版本。 */
    public String rollback(Task selectedTask, Integer requestedRound, PrintWriter output)
            throws IOException {
        Task task = load(selectedTask.id());
        if (task.activeRound() != null) {
            throw new IOException("当前任务存在未结束轮次: " + task.activeRound());
        }
        if (task.committedTestHash() == null || task.latestCommittedRound() == null) {
            throw new IOException("当前任务还没有可回滚的成功提交版本");
        }

        Path testFile = store.projectRoot().resolve(task.testFile()).normalize();
        String diskHash = FileHashes.sha256(testFile);
        if (!java.util.Objects.equals(diskHash, task.committedTestHash())) {
            throw new IOException("测试文件已在最近提交后被外部修改，拒绝回滚: " + task.testFile());
        }

        CommittedSnapshot snapshot = requestedRound == null
                ? previousCommittedSnapshot(task, diskHash)
                : committedSnapshot(task, requestedRound);
        if (java.util.Objects.equals(snapshot.attempt().sha256(), diskHash)) {
            throw new IOException("指定轮次与当前测试文件内容相同，无需回滚");
        }

        Path taskDirectory = store.taskDirectory(task.id()).toAbsolutePath().normalize();
        Path attachment = taskDirectory.resolve(snapshot.attempt().attachment()).normalize();
        if (!attachment.startsWith(taskDirectory)
                || !Files.isRegularFile(attachment, LinkOption.NOFOLLOW_LINKS)
                || !snapshot.attempt().sha256().equals(FileHashes.sha256(attachment))) {
            throw new IOException("回滚快照缺失、越界或哈希不一致: " + snapshot.attempt().attachment());
        }
        String restoredContent = Files.readString(attachment, StandardCharsets.UTF_8);

        String request = "rollback --round " + snapshot.round();
        Round rollbackRound = startRound(task, RoundType.CHANGE, request);
        task = store.load(task.id());
        TargetDescriptor descriptor = inspector.inspect(task.targetSource());
        TaskExecutionContext context = new TaskExecutionContext(
                store, task, rollbackRound, descriptor, output);
        context.noteRead(store.projectRoot().resolve(task.targetSource()));
        context.noteRead(testFile);
        context.noteRead(store.projectRoot().resolve("pom.xml"));

        output.println("[rollback] source_round=" + snapshot.round()
                + " target_round=" + rollbackRound.number() + " status=running");
        context.execute(TestAction.baseline_test);
        if (context.round().status() != RoundStatus.BASELINE_ACCEPTED) {
            return context.finalReport("回滚失败：命令执行前的完整基线未通过，测试文件未修改。");
        }

        context.writeCandidate(restoredContent);
        if (context.round().status() != RoundStatus.CANDIDATE_WRITTEN) {
            if (!context.isTerminal()) {
                context.terminateAndRollback("历史快照未通过当前静态校验");
            }
            return context.finalReport("回滚失败：历史快照未通过当前静态校验。");
        }

        context.execute(TestAction.target_test);
        if (context.round().status() != RoundStatus.TARGET_TEST_PASSED) {
            if (!context.isTerminal()) {
                context.terminateAndRollback("回滚版本的目标测试未通过");
            }
            return context.finalReport("回滚失败：目标测试未通过，已恢复命令执行前版本。");
        }

        context.execute(TestAction.regression_test);
        if (context.round().status() != RoundStatus.SUCCEEDED) {
            if (!context.isTerminal()) {
                context.terminateAndRollback("回滚版本的完整回归未通过");
            }
            return context.finalReport("回滚失败：完整回归未通过，已恢复命令执行前版本。");
        }
        output.println("[rollback] source_round=" + snapshot.round()
                + " target_round=" + rollbackRound.number() + " status=succeeded");
        return context.finalReport("已恢复轮次 " + snapshot.round()
                + " 的成功提交版本，目标测试和完整回归均通过。");
    }

    private CommittedSnapshot previousCommittedSnapshot(Task task, String currentHash)
            throws IOException {
        for (int number = task.latestCommittedRound() - 1; number >= 1; number--) {
            try {
                CommittedSnapshot snapshot = committedSnapshot(task, number);
                if (!snapshot.attempt().sha256().equals(currentHash)) {
                    return snapshot;
                }
            } catch (IllegalArgumentException ignored) {
                // 非成功 CHANGE 轮次不是可回滚版本，继续向前查找。
            }
        }
        throw new IOException("没有比当前版本更早的可回滚成功提交");
    }

    private CommittedSnapshot committedSnapshot(Task task, int number) throws IOException {
        if (number < 1 || number > task.roundCount()) {
            throw new IllegalArgumentException("回滚轮次不存在: " + number);
        }
        Round source = store.loadRound(task.id(), number);
        if (source.type() != RoundType.CHANGE || source.status() != RoundStatus.SUCCEEDED
                || source.fileState() == null || source.fileState().currentHash() == null) {
            throw new IllegalArgumentException("轮次 " + number + " 不是成功提交的 CHANGE 轮次");
        }
        Attempt committed = source.attempts().stream()
                .filter(Attempt::staticValidationPassed)
                .filter(attempt -> source.fileState().currentHash().equals(attempt.sha256()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IOException("轮次 " + number + " 缺少已提交候选快照"));
        return new CommittedSnapshot(number, committed);
    }

    public TaskStore store() {
        return store;
    }

    public ProjectInspector inspector() {
        return inspector;
    }

    public String displayId(String taskId) throws IOException {
        return store.displayId(taskId);
    }

    static Task copyTask(
            Task task,
            int roundCount,
            Integer activeRound,
            RoundType latestType,
            RoundStatus latestStatus,
            String committedHash,
            Integer committedRound,
            ValidationRecord successfulValidation,
            ValidationRecord latestValidation,
            String summary) {
        return new Task(
                task.schemaVersion(), task.id(), task.projectRoot(), task.targetClass(),
                task.targetSource(), task.testClass(), task.testFile(), task.createdAt(),
                OffsetDateTime.now().toString(), roundCount, activeRound, latestType, latestStatus,
                task.overwriteAllowed(), task.persistentConstraints(), committedHash,
                committedRound, successfulValidation, latestValidation,
                bounded(summary, 1_000));
    }

    static Round copyRound(
            Round round,
            RoundStatus status,
            String outcome,
            List<TaskModels.Attempt> attempts,
            TaskModels.TestExecution baseline,
            TaskModels.TestExecution target,
            TaskModels.TestExecution regression,
            FileState fileState,
            int modelCalls,
            int toolCalls,
            List<TaskModels.ToolEvent> events) {
        return new Round(
                round.schemaVersion(), round.number(), round.createdAt(),
                OffsetDateTime.now().toString(), status, round.type(), round.requestSummary(),
                round.roundConstraints(), bounded(outcome, 4_000), attempts.size(),
                modelCalls, toolCalls, round.promptTokenCount(), round.completionTokenCount(),
                events, attempts, baseline, target, regression, fileState);
    }

    static Round addTokenUsage(Round round, int promptTokens, int completionTokens) {
        return new Round(
                round.schemaVersion(), round.number(), round.createdAt(),
                OffsetDateTime.now().toString(), round.status(), round.type(),
                round.requestSummary(), round.roundConstraints(), round.outcomeSummary(),
                round.attemptCount(), round.modelCallCount(), round.toolCallCount(),
                Math.addExact(round.promptTokenCount(), Math.max(0, promptTokens)),
                Math.addExact(round.completionTokenCount(), Math.max(0, completionTokens)),
                round.toolEvents(), round.attempts(), round.baselineResult(),
                round.targetResult(), round.regressionResult(), round.fileState());
    }

    private void saveRoundAndTask(
            Task task,
            Round round,
            String summary,
            boolean terminal,
            String committedHash,
            ValidationRecord validation) throws IOException {
        store.saveRound(task.id(), round);
        boolean success = round.status() == RoundStatus.SUCCEEDED
                || round.status() == RoundStatus.VERIFIED;
        ValidationRecord lastSuccess = success ? validation : task.lastSuccessfulValidation();
        Task updated = copyTask(task, Math.max(task.roundCount(), round.number()),
                terminal ? null : round.number(), round.type(), round.status(),
                committedHash == null ? task.committedTestHash() : committedHash,
                committedHash == null ? task.latestCommittedRound() : Integer.valueOf(round.number()),
                lastSuccess, validation == null ? task.latestValidationAttempt() : validation,
                summary);
        store.saveTask(updated);
    }

    private String validationState(Task task) {
        try {
            ValidationRecord latest = task.latestValidationAttempt();
            if (latest != null && latest.applicable() && latest.status() != TaskModels.TestStatus.PASSED) {
                return "FAILED@" + latest.round();
            }
            ValidationRecord success = task.lastSuccessfulValidation();
            if (success == null) {
                return "UNVERIFIED";
            }
            boolean matches = java.util.Objects.equals(success.targetSourceHash(),
                            FileHashes.sha256(store.projectRoot().resolve(task.targetSource())))
                    && java.util.Objects.equals(success.testFileHash(),
                            FileHashes.sha256(store.projectRoot().resolve(task.testFile())))
                    && java.util.Objects.equals(success.pomHash(),
                            FileHashes.sha256(store.projectRoot().resolve("pom.xml")));
            return matches ? "VALIDATED@" + success.round() : "STALE";
        } catch (IOException e) {
            return "STALE";
        }
    }

    private void validateRoundConsistency(Task task) throws IOException {
        if (task.roundCount() == 0) {
            return;
        }
        Round latest = store.loadRound(task.id(), task.roundCount());
        if (latest.status() != task.latestRoundStatus()
                || latest.type() != task.latestRoundType()) {
            throw new IOException("task.json 与最新 round.json 状态不一致");
        }
    }

    private void validateAttachments(Task task) throws IOException {
        Path taskDirectory = store.taskDirectory(task.id()).toAbsolutePath().normalize();
        for (int number = 1; number <= task.roundCount(); number++) {
            Round round = store.loadRound(task.id(), number);
            for (TaskModels.Attempt attempt : round.attempts()) {
                Path attachment = taskDirectory.resolve(attempt.attachment()).normalize();
                if (!attachment.startsWith(taskDirectory)
                        || !Files.isRegularFile(attachment, LinkOption.NOFOLLOW_LINKS)
                        || !attempt.sha256().equals(FileHashes.sha256(attachment))) {
                    throw new IOException("候选附件缺失、越界或哈希不一致: " + attempt.attachment());
                }
            }
        }
    }

    private ValidationRecord validation(Task task, Round round, boolean applicable) throws IOException {
        TaskModels.TestExecution result = round.regressionResult() != null
                ? round.regressionResult() : round.targetResult();
        if (result == null) {
            return null;
        }
        TaskModels.TestStatus status = round.targetResult() != null
                && round.regressionResult() != null
                && round.targetResult().status() == TaskModels.TestStatus.PASSED
                && round.regressionResult().status() == TaskModels.TestStatus.PASSED
                        ? TaskModels.TestStatus.PASSED : result.status();
        return new ValidationRecord(round.number(), OffsetDateTime.now().toString(),
                FileHashes.sha256(store.projectRoot().resolve(task.targetSource())),
                FileHashes.sha256(store.projectRoot().resolve(task.testFile())),
                FileHashes.sha256(store.projectRoot().resolve("pom.xml")), status,
                round.outcomeSummary(), applicable);
    }

    private static String formatRound(Task task, Round round, boolean includeEvents) {
        StringBuilder output = new StringBuilder()
                .append("轮次: ").append(round.number())
                .append("\n类型/状态: ").append(round.type()).append('/').append(round.status())
                .append("\n用户要求: ").append(round.requestSummary())
                .append("\n结论: ").append(value(round.outcomeSummary()))
                .append("\n候选数: ").append(round.attemptCount())
                .append("\n模型/工具调用: ").append(round.modelCallCount())
                .append('/').append(round.toolCallCount())
                .append("\nToken 总量: 输入=").append(round.promptTokenCount())
                .append("，输出=").append(round.completionTokenCount())
                .append("，合计=").append(round.totalTokenCount())
                .append("\n基线: ").append(testSummary(round.baselineResult()))
                .append("\n目标测试: ").append(testSummary(round.targetResult()))
                .append("\n完整回归: ").append(testSummary(round.regressionResult()))
                .append("\n文件状态: ").append(round.fileState() == null
                        ? "未知" : round.fileState().state());
        for (TaskModels.Attempt attempt : round.attempts()) {
            output.append("\n- 候选 ").append(attempt.number())
                    .append(" 静态校验=").append(attempt.staticValidationPassed())
                    .append(" 哈希=").append(attempt.sha256(), 0, Math.min(12, attempt.sha256().length()))
                    .append(" 结果=").append(value(attempt.failureSummary()));
            if (!attempt.removedMethods().isEmpty()) {
                output.append(" 移除方法=").append(attempt.removedMethods());
            }
        }
        if (includeEvents) {
            for (TaskModels.ToolEvent event : round.toolEvents()) {
                output.append("\n- tool#").append(event.sequence()).append(' ')
                        .append(event.name()).append(' ').append(event.status())
                        .append(" ").append(event.parameters());
            }
        }
        return output.toString();
    }

    private static String testSummary(TaskModels.TestExecution result) {
        return result == null ? "未执行" : result.status() + " tests=" + result.tests()
                + " failures=" + result.failures() + " errors=" + result.errors();
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    static String bounded(String value, int maximum) {
        if (value == null) {
            return "";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum - 16) + "...[已截断]";
    }

    private static int columnWidth(String heading, List<String> values) {
        return Math.max(heading.length(),
                values.stream().mapToInt(String::length).max().orElse(0));
    }

    private static String padRight(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    private static String padLeft(String value, int width) {
        return " ".repeat(Math.max(0, width - value.length())) + value;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    public record CreateResult(
            Task task,
            TargetDescriptor descriptor,
            boolean created,
            String message) {
    }

    private record CommittedSnapshot(int round, Attempt attempt) {
    }

    private record HistoryRow(
            String id,
            String round,
            String validation,
            String count,
            String target) {
    }
}
