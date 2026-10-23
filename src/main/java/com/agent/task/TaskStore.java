package com.agent.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.agent.task.TaskModels.Round;
import com.agent.task.TaskModels.Task;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/** 在项目 .agent/tasks 下原子保存任务、轮次和候选附件。 */
public final class TaskStore {
    private final Path projectRoot;
    private final Path tasksDirectory;
    private final ObjectMapper mapper;

    public TaskStore(Path projectRoot) {
        try {
            this.projectRoot = projectRoot.toAbsolutePath().normalize().toRealPath();
            Path agentDirectory = this.projectRoot.resolve(".agent");
            Path taskDirectory = agentDirectory.resolve("tasks");
            if (Files.isSymbolicLink(agentDirectory) || Files.isSymbolicLink(taskDirectory)) {
                throw new IOException(".agent 或 tasks 目录不能是符号链接");
            }
            if (Files.exists(agentDirectory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(agentDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(".agent 不是普通目录");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("项目根目录不可访问: " + projectRoot, e);
        }
        this.tasksDirectory = this.projectRoot.resolve(".agent/tasks");
        this.mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public void create(Task task) throws IOException {
        validateTask(task);
        Path directory = taskDirectory(task.id());
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("任务目录已经存在: " + task.id());
        }
        Files.createDirectories(directory.resolve("rounds"));
        saveTask(task);
    }

    public void saveTask(Task task) throws IOException {
        validateTask(task);
        Path directory = taskDirectory(task.id());
        Files.createDirectories(directory);
        writeJson(directory.resolve("task.json"), task);
    }

    public Task load(String idOrPrefix) throws IOException {
        return readTask(resolveTaskDirectory(idOrPrefix));
    }

    public ListResult list(int offset, int limit) throws IOException {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("history 分页参数无效");
        }
        if (!Files.isDirectory(tasksDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return new ListResult(List.of(), 0, false, null);
        }

        List<Task> tasks = new ArrayList<>();
        int skipped = 0;
        try (var children = Files.list(tasksDirectory)) {
            for (Path directory : children.toList()) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    tasks.add(readTask(directory));
                } catch (Exception e) {
                    skipped++;
                }
            }
        }
        tasks.sort(Comparator.<Task, OffsetDateTime>comparing(
                task -> OffsetDateTime.parse(task.updatedAt()), Comparator.reverseOrder())
                .thenComparing(Task::id));
        int start = Math.min(offset, tasks.size());
        int end = Math.min(start + limit, tasks.size());
        boolean hasMore = end < tasks.size();
        return new ListResult(List.copyOf(tasks.subList(start, end)), skipped,
                hasMore, hasMore ? end : null);
    }

    public List<Task> findByTarget(String targetSource) throws IOException {
        List<Task> matches = new ArrayList<>();
        int offset = 0;
        while (true) {
            ListResult page = list(offset, 100);
            matches.addAll(page.tasks().stream()
                    .filter(task -> targetSource.equals(task.targetSource())).toList());
            if (!page.hasMore()) {
                return List.copyOf(matches);
            }
            offset = page.nextOffset();
        }
    }

    public void saveRound(String taskId, Round round) throws IOException {
        validateRound(round);
        Path directory = roundDirectory(taskId, round.number());
        Files.createDirectories(directory.resolve("attempts"));
        writeJson(directory.resolve("round.json"), round);
    }

    public Round loadRound(String taskId, int number) throws IOException {
        if (number < 1) {
            throw new IllegalArgumentException("轮次编号必须为正数");
        }
        Path path = roundDirectory(taskId, number).resolve("round.json");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("未找到轮次: " + number);
        }
        Round round = mapper.readValue(Files.readString(path, StandardCharsets.UTF_8), Round.class);
        validateRound(round);
        if (round.number() != number) {
            throw new IOException("轮次目录与记录编号不一致");
        }
        return round;
    }

    public List<Round> recentRounds(String taskId, int count) throws IOException {
        Task task = readTask(taskDirectory(taskId));
        List<Round> result = new ArrayList<>();
        int first = Math.max(1, task.roundCount() - count + 1);
        for (int number = task.roundCount(); number >= first; number--) {
            result.add(loadRound(taskId, number));
        }
        return List.copyOf(result);
    }

    public void delete(String idOrPrefix) throws IOException {
        Path directory = resolveTaskDirectory(idOrPrefix);
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(tasksDirectory.toAbsolutePath().normalize())
                || normalized.equals(tasksDirectory.toAbsolutePath().normalize())) {
            throw new SecurityException("拒绝删除任务目录之外的路径");
        }
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public Path taskDirectory(String taskId) {
        requireUuid(taskId);
        return tasksDirectory.resolve(taskId);
    }

    public Path roundDirectory(String taskId, int round) {
        if (round < 1) {
            throw new IllegalArgumentException("轮次编号必须为正数");
        }
        return taskDirectory(taskId).resolve("rounds")
                .resolve("round-%04d".formatted(round));
    }

    public Path tasksDirectory() {
        return tasksDirectory;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    /** 返回至少八位、且能在当前项目任务目录中唯一定位任务的最短前缀。 */
    public String displayId(String taskId) throws IOException {
        requireUuid(taskId);
        List<String> ids = taskDirectoryIds();
        if (!ids.contains(taskId)) {
            throw new IOException("未找到任务: " + taskId);
        }
        return shortestUniquePrefix(taskId, ids);
    }

    private static String shortestUniquePrefix(String taskId, List<String> ids) {
        int length = Math.min(8, taskId.length());
        while (length < taskId.length()) {
            String prefix = taskId.substring(0, length);
            long matches = ids.stream().filter(id -> id.startsWith(prefix)).count();
            if (matches == 1) {
                return prefix;
            }
            length++;
        }
        return taskId;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    private Task readTask(Path directory) throws IOException {
        Path path = directory.resolve("task.json");
        Task task = mapper.readValue(Files.readString(path, StandardCharsets.UTF_8), Task.class);
        validateTask(task);
        if (!directory.getFileName().toString().equals(task.id())) {
            throw new IOException("任务目录与记录 ID 不一致");
        }
        return task;
    }

    private Path resolveTaskDirectory(String idOrPrefix) throws IOException {
        if (idOrPrefix == null || idOrPrefix.isBlank()
                || !idOrPrefix.matches("[0-9a-fA-F-]{4,36}")) {
            throw new IllegalArgumentException("任务 ID 或前缀格式无效");
        }
        if (!Files.isDirectory(tasksDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("未找到任务: " + idOrPrefix);
        }
        List<Path> directories;
        try (var children = Files.list(tasksDirectory)) {
            directories = children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList();
        }
        List<Path> matches = directories.stream()
                .filter(path -> path.getFileName().toString().startsWith(idOrPrefix))
                .toList();
        if (matches.isEmpty()) {
            throw new IOException("未找到任务: " + idOrPrefix);
        }
        if (matches.size() > 1) {
            List<String> ids = directories.stream()
                    .map(path -> path.getFileName().toString()).toList();
            List<AmbiguousTaskIdException.Candidate> candidates = new ArrayList<>();
            for (Path match : matches) {
                String id = match.getFileName().toString();
                String targetClass = "[记录不可读]";
                String state = "UNKNOWN";
                try {
                    Task task = readTask(match);
                    targetClass = task.targetClass();
                    state = (task.latestRoundType() == null ? "NONE" : task.latestRoundType())
                            + "/" + task.latestRoundStatus();
                } catch (Exception ignored) {
                    // 损坏记录仍作为冲突候选展示，不能因此错误选择其他任务。
                }
                candidates.add(new AmbiguousTaskIdException.Candidate(
                        shortestUniquePrefix(id, ids), targetClass, state));
            }
            throw new AmbiguousTaskIdException(idOrPrefix, candidates);
        }
        return matches.get(0);
    }

    private List<String> taskDirectoryIds() throws IOException {
        if (!Files.isDirectory(tasksDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var children = Files.list(tasksDirectory)) {
            return children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private void writeJson(Path target, Object value) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".task-", ".tmp");
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validateTask(Task task) throws IOException {
        if (task == null || task.schemaVersion() != TaskModels.SCHEMA_VERSION) {
            throw new IOException("不支持的任务记录版本");
        }
        requireUuid(task.id());
        if (!projectRoot.toString().equals(task.projectRoot())) {
            throw new IOException("任务所属项目根目录不一致");
        }
        requireText(task.targetClass(), "target_class");
        requireText(task.targetSource(), "target_source");
        requireText(task.testClass(), "test_class");
        requireText(task.testFile(), "test_file");
        parseTime(task.createdAt(), "created_at");
        parseTime(task.updatedAt(), "updated_at");
        if (task.roundCount() < 0 || task.latestRoundStatus() == null) {
            throw new IOException("任务轮次元数据无效");
        }
        if (task.activeRound() != null
                && (task.activeRound() < 1 || task.activeRound() > task.roundCount())) {
            throw new IOException("活动轮次编号无效");
        }
    }

    private static void validateRound(Round round) throws IOException {
        if (round == null || round.schemaVersion() != TaskModels.SCHEMA_VERSION) {
            throw new IOException("不支持的轮次记录版本");
        }
        if (round.number() < 1 || round.type() == null || round.status() == null
                || round.attemptCount() < 0 || round.attemptCount() > 3
                || round.modelCallCount() < 0 || round.toolCallCount() < 0
                || round.promptTokenCount() < 0 || round.completionTokenCount() < 0
                || round.promptTokenCount() > Long.MAX_VALUE - round.completionTokenCount()
                || round.toolCallCount() > 30) {
            throw new IOException("轮次元数据无效");
        }
        parseTime(round.createdAt(), "round.created_at");
        parseTime(round.updatedAt(), "round.updated_at");
        if (round.toolEvents().size() > 30 || round.attempts().size() > 3) {
            throw new IOException("轮次事件或候选数量超限");
        }
    }

    private static void requireUuid(String id) {
        try {
            UUID.fromString(id);
        } catch (Exception e) {
            throw new IllegalArgumentException("任务 ID 不是有效 UUID", e);
        }
    }

    private static void requireText(String value, String field) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("缺少任务字段: " + field);
        }
    }

    private static void parseTime(String value, String field) throws IOException {
        try {
            OffsetDateTime.parse(value);
        } catch (Exception e) {
            throw new IOException("无效时间字段: " + field, e);
        }
    }

    public record ListResult(
            List<Task> tasks,
            int skippedTasks,
            boolean hasMore,
            Integer nextOffset) {
    }
}
