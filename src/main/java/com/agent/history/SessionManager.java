package com.agent.history;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.agent.Agent;
import com.agent.llm.Message;

/** 编排当前会话、单轮提交、自动保存以及本地历史命令。 */
public final class SessionManager {
    private final Agent agent;
    private final ChatHistoryStore store;
    private final HistorySanitizer sanitizer;
    private final HistoryContextBuilder contextBuilder;
    private final TitleGenerator titleGenerator;
    private ChatSession current;

    public SessionManager(
            Agent agent,
            ChatHistoryStore store,
            HistorySanitizer sanitizer,
            HistoryContextBuilder contextBuilder,
            TitleGenerator titleGenerator) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.store = Objects.requireNonNull(store, "store");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.titleGenerator = Objects.requireNonNull(titleGenerator, "titleGenerator");
        this.current = newSession();
    }

    /** 成功轮次写入磁盘后才提交；保存失败会恢复内存历史。 */
    public String runTurn(String input) {
        List<Message> before = agent.historySnapshot();
        Agent.TurnResult result = agent.runTurn(input);
        if (!result.completed()) {
            return result.answer();
        }

        try {
            List<Message> after = agent.historySnapshot();
            List<Message> delta = after.subList(before.size(), after.size());
            List<StoredMessage> messages = new ArrayList<>(current.messages());
            messages.addAll(sanitizer.toStoredMessages(delta, messages.size()));
            String now = OffsetDateTime.now().toString();
            TitleGenerator.TitleResult titleResult = current.turnCount() == 0
                    ? titleGenerator.generate(input)
                    : new TitleGenerator.TitleResult(current.title(), true, null);
            ChatSession prospective = new ChatSession(
                    ChatHistoryStore.SCHEMA_VERSION,
                    current.id(), titleResult.title(), current.createdAt(), now,
                    current.turnCount() + 1, Agent.SYSTEM_PROMPT_VERSION, messages);
            store.save(prospective);
            current = prospective;
            if (!titleResult.generatedByModel()) {
                return result.answer() + System.lineSeparator()
                        + "提示：模型标题生成失败，已使用本地标题（"
                        + titleResult.diagnostic() + "）。";
            }
            return result.answer();
        } catch (Exception e) {
            agent.replaceHistory(before);
            return result.answer()
                    + System.lineSeparator()
                    + "警告：本轮未保存且已从会话历史回滚；已发生的文件或命令副作用不会撤销。";
        }
    }

    /** 清空只开始新会话，不删除任何旧会话文件。 */
    public void clear() {
        agent.clearHistory();
        current = newSession();
    }

    /** 加载成功后整体替换当前状态；失败时保持原会话不变。 */
    public String load(String id) {
        try {
            ChatSession loaded = sanitizer.sanitizeSession(
                    store.load(id), Agent.systemPrompt(), Agent.SYSTEM_PROMPT_VERSION);
            List<Message> restored = sanitizer.toMessages(
                    loaded, Agent.systemPrompt(), Agent.SYSTEM_PROMPT_VERSION);
            List<Message> bounded = contextBuilder.build(restored);
            agent.replaceHistory(bounded);
            current = loaded;
            return "已加载会话: " + loaded.id() + "  " + loaded.title();
        } catch (Exception e) {
            return "加载会话失败: " + safeMessage(e);
        }
    }

    /** 删除指定历史文件；若删除当前会话，则立即切换到新的未保存会话。 */
    public String delete(String id) {
        try {
            boolean deletingCurrent = current.id().equals(id);
            if (!store.delete(id)) {
                return "删除会话失败: 未找到会话 " + id;
            }
            if (deletingCurrent) {
                clear();
                return "已删除会话: " + id + "；已开始新会话。";
            }
            return "已删除会话: " + id;
        } catch (Exception e) {
            return "删除会话失败: " + safeMessage(e);
        }
    }

    /** 展示当前会话摘要，不泄露工具参数和 Observation 正文。 */
    public String history() {
        StringBuilder output = new StringBuilder();
        output.append("会话 ID: ").append(current.id())
                .append("\n标题: ").append(current.title())
                .append("\n创建时间: ").append(current.createdAt())
                .append("\n更新时间: ").append(current.updatedAt())
                .append("\n已完成轮次: ").append(current.turnCount());
        if (current.turnCount() == 0) {
            return output.append("\n当前会话尚未保存").toString();
        }

        output.append("\n消息摘要:");
        for (StoredMessage message : current.messages()) {
            if ("user".equals(message.role())
                    || ("assistant".equals(message.role())
                            && (message.toolCalls() == null || message.toolCalls().isEmpty()))) {
                output.append("\n- ").append(message.role()).append(": ")
                        .append(abbreviate(message.content()));
            }
            if ("tool".equals(message.role()) && message.toolEvent() != null) {
                output.append("\n- tool: ").append(message.toolEvent().name())
                        .append(" [").append(message.toolEvent().status()).append("] id=")
                        .append(message.toolCallId());
            }
        }
        return output.toString();
    }

    /** 列出全部有效会话；损坏文件只计数告警，不阻断其他结果。 */
    public String sessions() {
        try {
            ChatHistoryStore.SessionList result = store.list();
            StringBuilder output = new StringBuilder();
            if (result.sessions().isEmpty()) {
                output.append("暂无历史会话");
            } else {
                result.sessions().forEach(session -> output.append(session.id()).append("  ")
                        .append(session.title()).append("  ")
                        .append(session.updatedAt()).append("  ")
                        .append(session.turnCount()).append('\n'));
                output.setLength(output.length() - 1);
            }
            if (result.skippedFiles() > 0) {
                output.append("\n警告：已跳过 ").append(result.skippedFiles()).append(" 个损坏的历史文件");
            }
            return output.toString();
        } catch (IOException e) {
            return "列出历史会话失败: " + safeMessage(e);
        }
    }

    public ChatSession currentSession() {
        return current;
    }

    private ChatSession newSession() {
        String now = OffsetDateTime.now().toString();
        List<StoredMessage> system = sanitizer.toStoredMessages(
                List.of(Message.system(Agent.systemPrompt())), 0);
        return new ChatSession(ChatHistoryStore.SCHEMA_VERSION, UUID.randomUUID().toString(),
                "未命名会话", now, now, 0, Agent.SYSTEM_PROMPT_VERSION, system);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
