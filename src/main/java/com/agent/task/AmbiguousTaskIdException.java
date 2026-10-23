package com.agent.task;

import java.io.IOException;
import java.util.List;

/** 任务 ID 前缀命中多个任务时返回候选，不允许调用方自行猜测。 */
public final class AmbiguousTaskIdException extends IOException {
    private final String input;
    private final List<Candidate> candidates;

    public AmbiguousTaskIdException(String input, List<Candidate> candidates) {
        super(message(input, candidates, null));
        this.input = input;
        this.candidates = List.copyOf(candidates);
    }

    public String userMessage(String command) {
        return message(input, candidates, command);
    }

    private static String message(String input, List<Candidate> candidates, String command) {
        int idWidth = candidates.stream().mapToInt(candidate -> candidate.displayId().length())
                .max().orElse(8);
        StringBuilder output = new StringBuilder("任务 ID 前缀不唯一: ").append(input)
                .append("\n可选任务:");
        for (Candidate candidate : candidates) {
            output.append("\n")
                    .append(padRight(candidate.displayId(), idWidth)).append("  ")
                    .append(candidate.targetClass()).append("  ")
                    .append(candidate.state());
        }
        output.append("\n请使用更长的唯一前缀重试。");
        if (command != null && !command.isBlank() && !candidates.isEmpty()) {
            output.append("\n例如: ").append(command).append(' ')
                    .append(candidates.get(0).displayId());
        }
        return output.toString();
    }

    private static String padRight(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    public record Candidate(String displayId, String targetClass, String state) {
    }
}
