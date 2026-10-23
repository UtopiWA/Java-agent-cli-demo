package com.agent.history;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 清理任务请求和摘要中的敏感文本。 */
public final class HistorySanitizer {
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
}
