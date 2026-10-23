package com.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.IntentClassifier.IntentType;

class IntentClassifierTest {
    @Test
    void shouldAcceptOnlyAPlainIntentEnum() {
        assertEquals(IntentType.ANALYSIS, IntentClassifier.parse(" analysis \n"));
        assertEquals(IntentType.VERIFY, IntentClassifier.parse("VERIFY"));
        assertEquals(IntentType.CHANGE, IntentClassifier.parse("change"));
        assertEquals(IntentType.CONSTRAINT, IntentClassifier.parse("constraint"));
        assertNull(IntentClassifier.parse("建议选择 ANALYSIS"));
        assertNull(IntentClassifier.parse("```ANALYSIS```"));
        assertNull(IntentClassifier.parse(null));
    }

    @Test
    void shouldSendWholeRequestAndNegationRulesToModel() {
        AtomicReference<List<Message>> captured = new AtomicReference<>();
        LLMClient client = new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages, List<ToolDef> tools) {
                throw new AssertionError("意图分类应使用轻量入口");
            }

            @Override
            public LLMResponse chatForIntent(List<Message> messages) {
                captured.set(messages);
                return new LLMResponse("ANALYSIS", null, 25, 1);
            }
        };

        String request = "只分析边界，不生成或运行测试";
        IntentClassifier.Result result = new IntentClassifier(client).classify(request);

        assertEquals(IntentType.ANALYSIS, result.type());
        assertEquals(false, result.fallback());
        assertEquals(request, captured.get().get(1).content());
        assertTrue(captured.get().get(0).content().contains("否定表达优先"));
        assertTrue(captured.get().get(0).content().contains("CONSTRAINT"));
        assertTrue(result.terminalLine().contains("prompt_tokens=25"));
    }

    @Test
    void shouldFallBackToAnalysisForInvalidOutputOrFailure() {
        LLMClient invalid = intentClient(new LLMResponse(
                "我认为是 CHANGE", null, 10, 5));
        IntentClassifier.Result invalidResult = new IntentClassifier(invalid).classify("生成测试");
        LLMClient toolCalling = intentClient(new LLMResponse("", List.of(new ToolCall(
                "unexpected", new ToolCall.Function("read_file", "{}"))), 12, 3));
        IntentClassifier.Result toolResult = new IntentClassifier(toolCalling).classify("分析测试");
        IntentClassifier.Result emptyResult = new IntentClassifier(intentClient(null))
                .classify("验证测试");

        LLMClient failing = new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages, List<ToolDef> tools)
                    throws IOException {
                throw new IOException("offline");
            }

            @Override
            public LLMResponse chatForIntent(List<Message> messages) throws IOException {
                throw new IOException("offline");
            }
        };
        IntentClassifier.Result failedResult = new IntentClassifier(failing).classify("生成测试");

        assertEquals(IntentType.ANALYSIS, invalidResult.type());
        assertEquals(true, invalidResult.fallback());
        assertTrue(invalidResult.terminalLine().contains("prompt_tokens=10"));
        assertEquals(IntentType.ANALYSIS, toolResult.type());
        assertEquals(true, toolResult.fallback());
        assertEquals(IntentType.ANALYSIS, emptyResult.type());
        assertEquals(true, emptyResult.fallback());
        assertEquals(IntentType.ANALYSIS, failedResult.type());
        assertEquals(true, failedResult.fallback());
    }

    private static LLMClient intentClient(LLMResponse response) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(List<Message> messages, List<ToolDef> tools) {
                return response;
            }

            @Override
            public LLMResponse chatForIntent(List<Message> messages) {
                return response;
            }
        };
    }
}
