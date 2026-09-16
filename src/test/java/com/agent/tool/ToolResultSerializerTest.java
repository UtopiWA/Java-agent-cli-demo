package com.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证三类 Tool 返回值能够稳定序列化为 JSON 文本。 */
class ToolResultSerializerTest {
    @Test
    void shouldSerializeEachToolResultAsJsonText() {
        assertEquals("[\"src/Main.java\",\"README.md\"]",
                ToolResultSerializer.serializePaths(List.of("src/Main.java", "README.md")));
        assertEquals("\"line 1\\nline 2\"",
                ToolResultSerializer.serializeText("line 1\nline 2"));
        assertEquals(
                "[{\"file\":\"A.java\",\"line\":2,\"content\":\"// \\\"TODO\\\"\"}]",
                ToolResultSerializer.serializeMatches(List.of(
                        new SearchCodeTool.Match("A.java", 2, "// \"TODO\""))));
    }
}
