package com.agent.history;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.agent.llm.Message;
import com.agent.llm.ToolCall;

/** 集中完成敏感文本清理以及工具消息的摘要化。 */
public final class HistorySanitizer {
    public static final String TOOL_SUMMARY = "[工具已调用，参数和结果未持久化]";
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?\\b(?:glm_api_key|api[_-]?key|apikey|authorization|access[_-]?token|token|password|secret)\\b[\\\"']?\\s*[:=]\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern ENV_LINE = Pattern.compile(
            "(?im)^(\\s*[A-Z][A-Z0-9_]*(?:KEY|TOKEN|PASSWORD|SECRET)[A-Z0-9_]*\\s*=\\s*).*$");

    /** 清理普通消息和标题中的常见凭据形式，同时保留其余语义。 */
    public String sanitizeText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String sanitized = BEARER.matcher(text).replaceAll("$1[REDACTED]");
        sanitized = replaceValue(SENSITIVE_ASSIGNMENT, sanitized);
        return replaceValue(ENV_LINE, sanitized);
    }

    /** 把一段新增协议消息转换成可安全落盘的消息。 */
    public List<StoredMessage> toStoredMessages(List<Message> messages, int firstSequence) {
        List<StoredMessage> result = new ArrayList<>();
        Map<String, String> callNames = new HashMap<>();
        int sequence = firstSequence;

        for (Message message : messages) {
            List<StoredToolCall> calls = null;
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                calls = new ArrayList<>();
                for (ToolCall call : message.toolCalls()) {
                    callNames.put(call.id(), call.name());
                    calls.add(new StoredToolCall(call.id(), "function",
                            new StoredToolCall.StoredFunction(call.name(), "{}")));
                }
            }

            ToolEvent event = null;
            String content = sanitizeText(message.content());
            if ("tool".equals(message.role())) {
                String raw = message.content() == null ? "" : message.content();
                event = new ToolEvent(
                        callNames.getOrDefault(message.toolCallId(), "unknown"),
                        inferToolStatus(raw),
                        true);
                content = TOOL_SUMMARY;
            }
            result.add(new StoredMessage(sequence++, message.role(), content, calls,
                    message.toolCallId(), event, OffsetDateTime.now().toString()));
        }
        return List.copyOf(result);
    }

    /** 从磁盘 DTO 恢复模型协议消息，并在加载边界再次脱敏。 */
    public List<Message> toMessages(
            ChatSession session, String currentSystemPrompt, int currentPromptVersion) {
        List<Message> result = new ArrayList<>();
        for (StoredMessage stored : session.messages()) {
            String content = sanitizeText(stored.content());
            switch (stored.role()) {
                case "system" -> result.add(Message.system(
                        session.systemPromptVersion() == currentPromptVersion
                                ? content : currentSystemPrompt));
                case "user" -> result.add(Message.user(content));
                case "assistant" -> {
                    if (stored.toolCalls() == null || stored.toolCalls().isEmpty()) {
                        result.add(Message.assistant(content));
                    } else {
                        List<ToolCall> calls = stored.toolCalls().stream()
                                .map(call -> new ToolCall(call.id(), new ToolCall.Function(
                                        call.function().name(), "{}")))
                                .toList();
                        result.add(Message.assistant(content, calls));
                    }
                }
                case "tool" -> result.add(Message.tool(stored.toolCallId(), TOOL_SUMMARY));
                default -> throw new IllegalArgumentException("不支持的消息角色: " + stored.role());
            }
        }
        if (result.isEmpty() || !"system".equals(result.get(0).role())) {
            result.add(0, Message.system(currentSystemPrompt));
        }
        return List.copyOf(result);
    }

    /** 对手工修改或旧版本的会话再次清理，保证展示和后续保存都不带回敏感正文。 */
    public ChatSession sanitizeSession(
            ChatSession session, String currentSystemPrompt, int currentPromptVersion) {
        List<StoredMessage> messages = new ArrayList<>();
        for (StoredMessage message : session.messages()) {
            String content = sanitizeText(message.content());
            List<StoredToolCall> calls = null;
            if (message.toolCalls() != null) {
                calls = message.toolCalls().stream()
                        .map(call -> new StoredToolCall(call.id(), "function",
                                new StoredToolCall.StoredFunction(
                                        sanitizeText(call.function().name()), "{}")))
                        .toList();
            }
            ToolEvent event = message.toolEvent();
            if ("tool".equals(message.role())) {
                content = TOOL_SUMMARY;
                if (event != null) {
                    event = new ToolEvent(sanitizeText(event.name()), event.status(), true);
                }
            }
            if ("system".equals(message.role())
                    && session.systemPromptVersion() != currentPromptVersion) {
                content = sanitizeText(currentSystemPrompt);
            }
            messages.add(new StoredMessage(message.sequence(), message.role(), content, calls,
                    message.toolCallId(), event, message.timestamp()));
        }
        return new ChatSession(session.schemaVersion(), session.id(),
                sanitizeText(session.title()), session.createdAt(), session.updatedAt(),
                session.turnCount(), currentPromptVersion, messages);
    }

    private static String replaceValue(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(matcher.group(1) + "[REDACTED]"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String inferToolStatus(String content) {
        String normalized = content.toLowerCase();
        if (normalized.contains("安全守卫") || normalized.contains("未执行")
                || normalized.contains("blocked") || normalized.contains("refused")) {
            return "blocked";
        }
        if (normalized.contains("失败") || normalized.contains("未知工具")
                || normalized.contains("error") || normalized.contains("failed")) {
            return "failure";
        }
        return "success";
    }
}
