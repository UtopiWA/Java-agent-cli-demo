package com.agent.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/** 负责会话 JSON 的校验、原子写入、读取和容错列表。 */
public class ChatHistoryStore {
    public static final int SCHEMA_VERSION = 1;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path historyDirectory;
    private final ObjectMapper mapper;

    public ChatHistoryStore(Path projectRoot) {
        this.historyDirectory = projectRoot.toAbsolutePath().normalize()
                .resolve(".agent").resolve("history");
        this.mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** 使用同目录临时文件和原子替换保存，避免中断破坏已有会话。 */
    public void save(ChatSession session) throws IOException {
        validate(session);
        Files.createDirectories(historyDirectory);
        Path target = findPath(session.id());
        if (target == null) {
            String prefix = FILE_TIME.format(OffsetDateTime.parse(session.createdAt()));
            target = historyDirectory.resolve(prefix + "-" + session.id() + ".json");
        }
        Path temporary = Files.createTempFile(historyDirectory, ".history-", ".tmp");
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(session);
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

    /** 精确按完整 ID 加载；任意格式或协议错误都会整体拒绝。 */
    public ChatSession load(String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        Path path = findPath(id);
        if (path == null) {
            throw new IOException("未找到会话: " + id);
        }
        ChatSession session = read(path);
        if (!id.equals(session.id())) {
            throw new IOException("会话 ID 与文件内容不一致");
        }
        return session;
    }

    /** 精确删除一个会话文件；不存在时返回 false，不接受路径或模糊 ID。 */
    public boolean delete(String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        Path path = findPath(id);
        return path != null && Files.deleteIfExists(path);
    }

    /** 跳过损坏文件并按更新时间倒序返回其他有效会话。 */
    public SessionList list() throws IOException {
        if (!Files.isDirectory(historyDirectory)) {
            return new SessionList(List.of(), 0);
        }
        List<ChatSession> sessions = new ArrayList<>();
        int skipped = 0;
        try (var files = Files.list(historyDirectory)) {
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .toList()) {
                try {
                    sessions.add(read(path));
                } catch (Exception e) {
                    skipped++;
                }
            }
        }
        sessions.sort(Comparator.comparing(
                session -> OffsetDateTime.parse(session.updatedAt()), Comparator.reverseOrder()));
        return new SessionList(List.copyOf(sessions), skipped);
    }

    public Path historyDirectory() {
        return historyDirectory;
    }

    private ChatSession read(Path path) throws IOException {
        ChatSession session = mapper.readValue(
                Files.readString(path, StandardCharsets.UTF_8), ChatSession.class);
        validate(session);
        return session;
    }

    private Path findPath(String id) throws IOException {
        if (!Files.isDirectory(historyDirectory)) {
            return null;
        }
        try (var files = Files.list(historyDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith("-" + id + ".json"))
                    .findFirst().orElse(null);
        }
    }

    /** 校验版本、元数据、轮次数以及工具调用与 Observation 的一一配对。 */
    private static void validate(ChatSession session) throws IOException {
        if (session == null || session.schemaVersion() != SCHEMA_VERSION) {
            throw new IOException("不支持的历史记录版本");
        }
        requireText(session.id(), "id");
        requireText(session.title(), "title");
        parseTime(session.createdAt(), "created_at");
        parseTime(session.updatedAt(), "updated_at");
        if (session.turnCount() < 0 || session.messages() == null) {
            throw new IOException("无效的会话元数据");
        }
        if (session.messages().isEmpty()
                || !"system".equals(session.messages().get(0).role())) {
            throw new IOException("历史必须以 system 消息开始");
        }

        int userCount = 0;
        int expectedSequence = 0;
        Map<String, Integer> expectedCalls = new HashMap<>();
        Map<String, Integer> observations = new HashMap<>();
        Set<String> callIds = new HashSet<>();
        for (StoredMessage message : session.messages()) {
            if (message == null || message.sequence() != expectedSequence++) {
                throw new IOException("消息序号不连续");
            }
            if (!Set.of("system", "user", "assistant", "tool").contains(message.role())) {
                throw new IOException("无效的消息角色");
            }
            parseTime(message.timestamp(), "message.timestamp");
            if ("user".equals(message.role())) {
                userCount++;
            }
            if (message.toolCalls() != null) {
                if (!"assistant".equals(message.role())) {
                    throw new IOException("只有 assistant 消息可以包含 Tool Call");
                }
                for (StoredToolCall call : message.toolCalls()) {
                    if (call == null || call.id() == null || call.id().isBlank()
                            || call.function() == null || call.function().name() == null
                            || !callIds.add(call.id())) {
                        throw new IOException("无效或重复的 Tool Call ID");
                    }
                    expectedCalls.merge(call.id(), 1, Integer::sum);
                }
            }
            if ("tool".equals(message.role())) {
                requireText(message.toolCallId(), "tool_call_id");
                if (message.toolEvent() == null) {
                    throw new IOException("tool 消息缺少事件摘要");
                }
                observations.merge(message.toolCallId(), 1, Integer::sum);
            }
        }
        if (userCount != session.turnCount()) {
            throw new IOException("turn_count 与用户轮次数不一致");
        }
        if (!expectedCalls.equals(observations)) {
            throw new IOException("Tool Call 与 Observation 未正确配对");
        }
    }

    private static void requireText(String value, String field) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("缺少字段: " + field);
        }
    }

    private static void parseTime(String value, String field) throws IOException {
        try {
            OffsetDateTime.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IOException("无效时间字段: " + field, e);
        }
    }

    public record SessionList(List<ChatSession> sessions, int skippedFiles) {
    }
}
