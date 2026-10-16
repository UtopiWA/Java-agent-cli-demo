package com.agent.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agent.llm.Message;
import com.agent.llm.ToolCall;

class HistorySanitizerTest {
    private final HistorySanitizer sanitizer = new HistorySanitizer();

    @Test
    void shouldRedactCommonCredentialForms() {
        String input = "GLM_API_KEY=abc Authorization: Bearer xyz password='p' "
                + "{\"access_token\":\"secret-value\"}";

        String result = sanitizer.sanitizeText(input);

        assertFalse(result.contains("abc"));
        assertFalse(result.contains("xyz"));
        assertFalse(result.contains("secret-value"));
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void shouldOmitToolArgumentsAndResultsButKeepPairing() {
        List<Message> messages = List.of(
                Message.assistant("", List.of(
                        call("one", "read_file", "{\"path\":\"secret.txt\"}"),
                        call("two", "execute_command", "{\"program\":\"java\"}"))),
                Message.tool("one", "文件正文 secret-body"),
                Message.tool("two", "执行命令失败: bad output"));

        List<StoredMessage> stored = sanitizer.toStoredMessages(messages, 1);

        assertEquals("{}", stored.get(0).toolCalls().get(0).function().arguments());
        assertEquals("one", stored.get(1).toolCallId());
        assertEquals(HistorySanitizer.TOOL_SUMMARY, stored.get(1).content());
        assertEquals("success", stored.get(1).toolEvent().status());
        assertEquals("failure", stored.get(2).toolEvent().status());
        assertFalse(stored.toString().contains("secret-body"));
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, new ToolCall.Function(name, arguments));
    }
}
