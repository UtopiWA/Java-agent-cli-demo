package com.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 读取 UTF-8 文本文件，并支持按行分页。 */
public class ReadFileTool {
    /** 一次读取完整文本，适用于体积较小的文件。 */
    public String readFile(Path path) throws IOException {
        validateFile(path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 从 offset 行开始读取至多 limit 行，避免把大文件全部载入内存。
     */
    public String readFile(Path path, long offset, long limit) throws IOException {
        validateFile(path);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }

        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines
                    .skip(offset)
                    .limit(limit)
                    .collect(Collectors.joining(System.lineSeparator()));
        }
    }

    private static void validateFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException("Not a regular file or does not exist: " + path);
        }
    }
}
