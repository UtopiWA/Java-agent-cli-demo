package com.agent.history;

import java.util.List;
import java.util.Objects;

import com.agent.llm.GlmHttpException;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;

/** 独立调用模型生成首轮标题，失败时安全降级为本地标题。 */
public final class TitleGenerator {
    private static final int MAX_TITLE_LENGTH = 30;
    private static final String TITLE_PROMPT =
            "根据用户任务生成简洁的中文会话标题。只输出单行标题，不加引号，不超过30个字符。";

    private final LLMClient llm;
    private final HistorySanitizer sanitizer;

    /** 标明标题来源；降级原因仅保留安全诊断，不包含响应正文。 */
    public record TitleResult(String title, boolean generatedByModel, String diagnostic) {
    }

    public TitleGenerator(LLMClient llm, HistorySanitizer sanitizer) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
    }

    /** 标题请求不携带工具定义，也不写入主对话历史。 */
    public TitleResult generate(String firstUserMessage) {
        String safeInput = sanitizer.sanitizeText(firstUserMessage);
        try {
            LLMResponse response = llm.chatForTitle(
                    List.of(Message.system(TITLE_PROMPT), Message.user(safeInput)));
            String title = normalize(sanitizer.sanitizeText(response.content()));
            if (title.isBlank()) {
                return new TitleResult(fallback(safeInput), false, "模型返回空标题");
            }
            return new TitleResult(title, true, null);
        } catch (Exception e) {
            return new TitleResult(fallback(safeInput), false, diagnostic(e));
        }
    }

    private static String normalize(String title) {
        if (title == null) {
            return "";
        }
        String normalized = title.replace('\r', ' ').replace('\n', ' ').trim()
                .replaceAll("\\s+", " ");
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("“") && normalized.endsWith("”"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        normalized = normalized.replaceFirst("^(?:标题|会话标题)\\s*[:：]\\s*", "");
        return truncate(normalized);
    }

    private static String fallback(String input) {
        String normalized = input == null ? "" : input.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? "未命名会话" : truncate(normalized);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TITLE_LENGTH
                ? value : value.substring(0, MAX_TITLE_LENGTH);
    }

    private static String diagnostic(Exception exception) {
        if (exception instanceof GlmHttpException httpException) {
            return "GLM HTTP " + httpException.statusCode() + "（"
                    + httpException.kind() + "）";
        }
        return exception.getClass().getSimpleName();
    }
}
