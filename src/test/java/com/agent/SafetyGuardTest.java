package com.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agent.llm.ToolCall;

class SafetyGuardTest {
    @Test
    void shouldRejectMissingDuplicateAndReusedCallIds() {
        SafetyGuard guard = new SafetyGuard();

        assertTrue(guard.validateCalls(List.of(call("", "read_file", "{}")))
                .contains("ID 不能为空"));
        assertTrue(guard.validateCalls(List.of(
                call("same", "read_file", "{}"),
                call("same", "list_files", "{}"))).contains("ID 重复"));
        assertNull(guard.validateCalls(List.of(call("used", "read_file", "{}"))));
        assertTrue(guard.validateCalls(List.of(call("used", "read_file", "{}")))
                .contains("ID 重复"));
    }

    @Test
    void shouldRejectMissingToolName() {
        String result = new SafetyGuard().validateCalls(List.of(call("one", "", "{}")));

        assertTrue(result.contains("工具名称不能为空"));
    }

    @Test
    void shouldStopThirdConsecutiveEquivalentCall() {
        SafetyGuard guard = new SafetyGuard();

        assertNull(guard.checkRepeatedCall(call("one", "read_file", "{\"path\":\"A.java\",\"limit\":10}")));
        assertNull(guard.checkRepeatedCall(call("two", "read_file", "{\"limit\":10,\"path\":\"A.java\"}")));
        assertEquals("检测到连续 3 次相同工具调用: read_file",
                guard.checkRepeatedCall(call("three", "read_file", "{\"path\":\"A.java\",\"limit\":10}")));
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, new ToolCall.Function(name, arguments));
    }
}
