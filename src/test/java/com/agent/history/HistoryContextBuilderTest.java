package com.agent.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agent.llm.Message;

class HistoryContextBuilderTest {
    @Test
    void shouldKeepNewestWholeTurnsWithinBudget() {
        HistoryContextBuilder builder = new HistoryContextBuilder(new Utf8TokenEstimator(), 300);
        List<Message> history = List.of(
                Message.system("system"),
                Message.user("old-" + "x".repeat(180)),
                Message.assistant("old-answer"),
                Message.user("new"),
                Message.assistant("new-answer"));

        List<Message> result = builder.build(history);

        assertEquals("system", result.get(0).role());
        assertTrue(result.stream().anyMatch(message -> "new".equals(message.content())));
        assertTrue(result.stream().noneMatch(message -> message.content() != null
                && message.content().startsWith("old-")));
        assertTrue(builder.estimate(result) <= 300);
    }

    @Test
    void shouldMarkOversizedNewestTurnAsTruncated() {
        HistoryContextBuilder builder = new HistoryContextBuilder(new Utf8TokenEstimator(), 350);
        List<Message> result = builder.build(List.of(
                Message.system("system"),
                Message.user("中".repeat(1_000)),
                Message.assistant("done")));

        assertTrue(result.stream().anyMatch(message -> message.content() != null
                && message.content().contains(HistoryContextBuilder.TRUNCATION_MARKER)));
        assertTrue(builder.estimate(result) <= 350);
    }
}
