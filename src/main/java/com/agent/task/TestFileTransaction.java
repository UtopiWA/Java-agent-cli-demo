package com.agent.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.agent.task.TaskModels.Transaction;
import com.agent.tool.WriteFileTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/** 管理单个 CHANGE 轮次的测试文件写前日志、候选替换和回滚。 */
public final class TestFileTransaction {
    private static final String TRANSACTION_FILE = "transaction.json";
    private static final String BASELINE_FILE = "baseline-test.java";

    private final TaskStore store;
    private final TaskModels.Task task;
    private final int round;
    private final Path testFile;
    private final Path roundDirectory;
    private final ObjectMapper mapper;
    private final WriteFileTool writer = new WriteFileTool();
    private Transaction transaction;

    public TestFileTransaction(TaskStore store, TaskModels.Task task, int round) {
        this.store = store;
        this.task = task;
        this.round = round;
        this.testFile = store.projectRoot().resolve(task.testFile()).normalize();
        this.roundDirectory = store.roundDirectory(task.id(), round);
        this.mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    public Transaction begin() throws IOException {
        if (transaction != null) {
            return transaction;
        }
        Files.createDirectories(roundDirectory);
        boolean existed = Files.exists(testFile, LinkOption.NOFOLLOW_LINKS);
        if (existed && (!Files.isRegularFile(testFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(testFile))) {
            throw new IOException("测试目标不是可写普通文件");
        }
        String baselineHash = existed ? FileHashes.sha256(testFile) : null;
        if (existed) {
            Files.copy(testFile, roundDirectory.resolve(BASELINE_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        transaction = new Transaction(
                TaskModels.SCHEMA_VERSION, task.id(), round, task.testFile(), existed,
                baselineHash, null, "PREPARED", true);
        save(transaction);
        return transaction;
    }

    public WriteOutcome write(String content) throws IOException {
        Transaction current = begin();
        String actualHash = FileHashes.sha256(testFile);
        String expectedHash = current.candidateHash() == null
                ? current.baselineHash() : current.candidateHash();
        if (!java.util.Objects.equals(actualHash, expectedHash)) {
            throw new RollbackConflictException(
                    "测试文件在候选写入前被外部修改: " + task.testFile());
        }

        WriteFileTool.WriteResult result = writer.writeFile(testFile, content);
        String candidateHash = FileHashes.sha256(testFile);
        transaction = new Transaction(
                current.schemaVersion(), current.taskId(), current.round(), current.testFile(),
                current.baselineExisted(), current.baselineHash(), candidateHash,
                "CANDIDATE_WRITTEN", result.atomic());
        save(transaction);
        return new WriteOutcome(candidateHash, result.bytesWritten(), result.created(), result.atomic());
    }

    public void markCommitReady() throws IOException {
        Transaction current = requireTransaction();
        transaction = new Transaction(
                current.schemaVersion(), current.taskId(), current.round(), current.testFile(),
                current.baselineExisted(), current.baselineHash(), current.candidateHash(),
                "COMMIT_READY", current.atomicWrite());
        save(transaction);
    }

    public void finishCommit() throws IOException {
        Transaction current = requireTransaction();
        if (!java.util.Objects.equals(FileHashes.sha256(testFile), current.candidateHash())) {
            throw new RollbackConflictException("提交前测试文件内容发生变化");
        }
        cleanup();
    }

    public RollbackResult rollback() throws IOException {
        Transaction current = requireTransaction();
        String diskHash = FileHashes.sha256(testFile);
        if (current.candidateHash() != null
                && !java.util.Objects.equals(diskHash, current.candidateHash())) {
            throw new RollbackConflictException("测试文件已被外部修改，拒绝用基线覆盖");
        }

        if (current.baselineExisted()) {
            Path backup = roundDirectory.resolve(BASELINE_FILE);
            if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
                    || !java.util.Objects.equals(FileHashes.sha256(backup), current.baselineHash())) {
                throw new IOException("事务基线备份缺失或损坏: " + backup);
            }
            writer.writeFile(testFile, Files.readString(backup, StandardCharsets.UTF_8));
        } else {
            Files.deleteIfExists(testFile);
        }
        cleanup();
        return new RollbackResult(current.baselineExisted(), current.baselineHash());
    }

    public static RecoveryResult recover(TaskStore store, TaskModels.Task task, int round)
            throws IOException {
        Path directory = store.roundDirectory(task.id(), round);
        Path log = directory.resolve(TRANSACTION_FILE);
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            return new RecoveryResult(false, false, "没有未完成事务");
        }
        TestFileTransaction manager = new TestFileTransaction(store, task, round);
        manager.transaction = manager.mapper.readValue(
                Files.readString(log, StandardCharsets.UTF_8), Transaction.class);
        Transaction transaction = manager.transaction;
        String diskHash = FileHashes.sha256(manager.testFile);
        if ("COMMIT_READY".equals(transaction.phase())
                && java.util.Objects.equals(diskHash, transaction.candidateHash())) {
            manager.cleanup();
            return new RecoveryResult(true, false, "已补全候选提交记录清理");
        }
        try {
            manager.rollback();
            return new RecoveryResult(true, true, "已回滚未完成事务");
        } catch (RollbackConflictException e) {
            return new RecoveryResult(true, false, "ROLLBACK_CONFLICT: " + e.getMessage());
        }
    }

    public Transaction current() {
        return transaction;
    }

    private Transaction requireTransaction() {
        if (transaction == null) {
            throw new IllegalStateException("文件事务尚未开始");
        }
        return transaction;
    }

    private void save(Transaction value) throws IOException {
        Path target = roundDirectory.resolve(TRANSACTION_FILE);
        Files.createDirectories(roundDirectory);
        Path temporary = Files.createTempFile(roundDirectory, ".transaction-", ".tmp");
        try {
            Files.writeString(temporary,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void cleanup() throws IOException {
        Files.deleteIfExists(roundDirectory.resolve(TRANSACTION_FILE));
        Files.deleteIfExists(roundDirectory.resolve(BASELINE_FILE));
        transaction = null;
    }

    public record WriteOutcome(String hash, int bytes, boolean created, boolean atomic) {
    }

    public record RollbackResult(boolean restoredExistingFile, String baselineHash) {
    }

    public record RecoveryResult(boolean recovered, boolean rolledBack, String message) {
    }

    public static final class RollbackConflictException extends IOException {
        public RollbackConflictException(String message) {
            super(message);
        }
    }
}
