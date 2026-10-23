package com.agent.task;

import java.util.List;

/** 测试维护任务持久化使用的领域模型。 */
public final class TaskModels {
    public static final int SCHEMA_VERSION = 1;

    private TaskModels() {
    }

    public enum RoundType {
        ANALYSIS, VERIFY, CHANGE
    }

    public enum RoundStatus {
        NONE, CREATED, TARGET_VALIDATED, PROJECT_CHECKED, EXPLORING,
        BASELINE_ACCEPTED, CANDIDATE_WRITTEN, TARGET_TEST_PASSED,
        REGRESSION_PASSED, REPAIRING, ROLLING_BACK, PAUSED,
        ANALYZED, VERIFIED, SUCCEEDED, FAILED, ABORTED, ROLLBACK_CONFLICT
    }

    public enum TestAction {
        baseline_test, target_test, regression_test
    }

    public enum TestStatus {
        PASSED, NO_TESTS, COMPILATION_FAILED, TEST_FAILED, TIMED_OUT, EXECUTION_FAILED
    }

    public record TestExecution(
            String action,
            TestStatus status,
            int exitCode,
            int tests,
            int failures,
            int errors,
            int skipped,
            List<String> failedTests,
            String diagnostic,
            boolean outputTruncated,
            String executedAt) {
        public TestExecution {
            failedTests = failedTests == null ? List.of() : List.copyOf(failedTests);
        }
    }

    public record ValidationRecord(
            int round,
            String validatedAt,
            String targetSourceHash,
            String testFileHash,
            String pomHash,
            TestStatus status,
            String summary,
            boolean applicable) {
    }

    public record Task(
            int schemaVersion,
            String id,
            String projectRoot,
            String targetClass,
            String targetSource,
            String testClass,
            String testFile,
            String createdAt,
            String updatedAt,
            int roundCount,
            Integer activeRound,
            RoundType latestRoundType,
            RoundStatus latestRoundStatus,
            boolean overwriteAllowed,
            String persistentConstraints,
            String committedTestHash,
            Integer latestCommittedRound,
            ValidationRecord lastSuccessfulValidation,
            ValidationRecord latestValidationAttempt,
            String latestRoundSummary) {
    }

    public record ToolEvent(
            int sequence,
            String name,
            String calledAt,
            long durationMs,
            String parameters,
            String status,
            int resultSize,
            String summary,
            boolean detailsOmitted) {
    }

    public record Attempt(
            int number,
            String attachment,
            String sha256,
            int bytes,
            boolean staticValidationPassed,
            String validationSummary,
            List<String> removedMethods,
            TestExecution targetResult,
            String failureSummary) {
        public Attempt {
            removedMethods = removedMethods == null ? List.of() : List.copyOf(removedMethods);
        }
    }

    public record FileState(
            boolean baselineExisted,
            String baselineHash,
            String currentHash,
            String state,
            boolean atomicWrite) {
    }

    public record Round(
            int schemaVersion,
            int number,
            String createdAt,
            String updatedAt,
            RoundStatus status,
            RoundType type,
            String requestSummary,
            String roundConstraints,
            String outcomeSummary,
            int attemptCount,
            int modelCallCount,
            int toolCallCount,
            long promptTokenCount,
            long completionTokenCount,
            List<ToolEvent> toolEvents,
            List<Attempt> attempts,
            TestExecution baselineResult,
            TestExecution targetResult,
            TestExecution regressionResult,
            FileState fileState) {
        public Round {
            toolEvents = toolEvents == null ? List.of() : List.copyOf(toolEvents);
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }

        public long totalTokenCount() {
            return promptTokenCount + completionTokenCount;
        }
    }

    public record Transaction(
            int schemaVersion,
            String taskId,
            int round,
            String testFile,
            boolean baselineExisted,
            String baselineHash,
            String candidateHash,
            String phase,
            boolean atomicWrite) {
    }
}
