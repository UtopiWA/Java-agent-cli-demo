package com.agent.history;

import java.util.ArrayList;
import java.util.List;

import com.agent.llm.Message;
import com.agent.llm.ToolCall;

/** 按完整轮次构造受预算约束的模型上下文，不修改磁盘历史。 */
public final class HistoryContextBuilder {
    public static final int DEFAULT_MAX_TOKENS = 32_000;
    public static final String TRUNCATION_MARKER = "[历史内容因 Token 上限已截断]";

    private final TokenEstimator estimator;
    private final int maxTokens;

    public HistoryContextBuilder() {
        this(new Utf8TokenEstimator(), DEFAULT_MAX_TOKENS);
    }

    public HistoryContextBuilder(TokenEstimator estimator, int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须为正数");
        }
        this.estimator = estimator;
        this.maxTokens = maxTokens;
    }

    /** 始终保留 system，并从最新轮次向前纳入可完整容纳的轮次。 */
    public List<Message> build(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        Message system = history.get(0);
        List<List<Message>> turns = splitTurns(history.subList(1, history.size()));
        List<List<Message>> selected = new ArrayList<>();

        for (int index = turns.size() - 1; index >= 0; index--) {
            List<List<Message>> candidateTurns = new ArrayList<>();
            candidateTurns.add(turns.get(index));
            candidateTurns.addAll(selected);
            List<Message> candidate = flatten(system, candidateTurns);
            if (estimator.estimate(candidate) <= maxTokens) {
                selected = candidateTurns;
            } else if (selected.isEmpty()) {
                selected = List.of(compactNewestTurn(system, turns.get(index)));
            } else {
                break;
            }
        }
        return List.copyOf(flatten(system, selected));
    }

    public int estimate(List<Message> messages) {
        return estimator.estimate(messages);
    }

    private List<Message> compactNewestTurn(Message system, List<Message> turn) {
        List<Message> compacted = new ArrayList<>(turn);
        while (estimator.estimate(flatten(system, List.of(compacted))) > maxTokens) {
            int index = longestReducibleMessage(compacted);
            if (index < 0) {
                break;
            }
            Message current = compacted.get(index);
            String content = current.content() == null ? "" : current.content();
            int markerAt = content.indexOf(TRUNCATION_MARKER);
            String originalPart = markerAt >= 0 ? content.substring(0, markerAt) : content;
            int keep = Math.max(0, originalPart.length() / 2);
            compacted.set(index, copyWithContent(current,
                    originalPart.substring(0, keep) + TRUNCATION_MARKER));
        }

        // 极大的工具参数不会写入磁盘；若当前轮超限，也先摘要化后再发送给模型。
        if (estimator.estimate(flatten(system, List.of(compacted))) > maxTokens) {
            compacted = compacted.stream().map(this::omitToolArguments).toList();
        }
        while (estimator.estimate(flatten(system, List.of(compacted))) > maxTokens) {
            int index = longestReducibleMessage(compacted);
            if (index < 0) {
                break;
            }
            Message current = compacted.get(index);
            compacted.set(index, copyWithContent(current, TRUNCATION_MARKER));
        }
        return List.copyOf(compacted);
    }

    private static List<List<Message>> splitTurns(List<Message> messages) {
        List<List<Message>> turns = new ArrayList<>();
        List<Message> current = null;
        for (Message message : messages) {
            if ("user".equals(message.role()) || current == null) {
                current = new ArrayList<>();
                turns.add(current);
            }
            current.add(message);
        }
        return turns;
    }

    private static List<Message> flatten(Message system, List<List<Message>> turns) {
        List<Message> result = new ArrayList<>();
        result.add(system);
        turns.forEach(result::addAll);
        return result;
    }

    private static int longestReducibleMessage(List<Message> messages) {
        int selected = -1;
        int maxLength = TRUNCATION_MARKER.length();
        for (int index = 0; index < messages.size(); index++) {
            String content = messages.get(index).content();
            int length = content == null ? 0 : content.length();
            if (length > maxLength) {
                maxLength = length;
                selected = index;
            }
        }
        return selected;
    }

    private Message omitToolArguments(Message message) {
        if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
            return message;
        }
        List<ToolCall> calls = message.toolCalls().stream()
                .map(call -> new ToolCall(call.id(), new ToolCall.Function(call.name(), "{}")))
                .toList();
        return Message.assistant(message.content(), calls);
    }

    private static Message copyWithContent(Message message, String content) {
        return switch (message.role()) {
            case "user" -> Message.user(content);
            case "assistant" -> message.toolCalls() == null
                    ? Message.assistant(content)
                    : Message.assistant(content, message.toolCalls());
            case "tool" -> Message.tool(message.toolCallId(), content);
            default -> message;
        };
    }
}
