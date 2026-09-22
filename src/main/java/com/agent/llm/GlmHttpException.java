package com.agent.llm;

import java.io.IOException;

/** 携带状态码和错误类别，使调用方能区分鉴权、限流与服务端故障。 */
public final class GlmHttpException extends IOException {
    public enum Kind {
        AUTHENTICATION,
        RATE_LIMIT,
        SERVER,
        OTHER
    }

    private static final int MAX_BODY_CHARS = 1_000;

    private final int statusCode;
    private final Kind kind;
    private final String responseBody;

    public GlmHttpException(int statusCode, String responseBody) {
        super(message(statusCode, responseBody));
        this.statusCode = statusCode;
        this.kind = classify(statusCode);
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public Kind kind() {
        return kind;
    }

    public String responseBody() {
        return responseBody;
    }

    private static Kind classify(int statusCode) {
        if (statusCode == 401) {
            return Kind.AUTHENTICATION;
        }
        if (statusCode == 429) {
            return Kind.RATE_LIMIT;
        }
        if (statusCode >= 500 && statusCode <= 599) {
            return Kind.SERVER;
        }
        return Kind.OTHER;
    }

    private static String message(int statusCode, String body) {
        String label = switch (classify(statusCode)) {
            case AUTHENTICATION -> "鉴权失败，请检查 API Key";
            case RATE_LIMIT -> "请求受限，请稍后重试或检查额度";
            case SERVER -> "模型服务暂时异常";
            case OTHER -> "HTTP 请求失败";
        };
        String safeBody = body == null ? "" : body;
        if (safeBody.length() > MAX_BODY_CHARS) {
            safeBody = safeBody.substring(0, MAX_BODY_CHARS) + "...";
        }
        return "GLM " + label + "（HTTP " + statusCode + "）: " + safeBody;
    }
}
