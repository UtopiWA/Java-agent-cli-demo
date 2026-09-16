package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 覆盖完整读取、分页边界、UTF-8 内容和异常路径。 */
class ReadFileToolTest {
    @Test
    void shouldReadUtf8File(@TempDir Path root) throws Exception {
        Path file = root.resolve("message.txt");
        Files.writeString(file, "你好，Agent！");

        assertEquals("你好，Agent！", new ReadFileTool().readFile(file));
    }

    @Test
    void shouldReadPageByLineOffsetAndLimit(@TempDir Path root) throws Exception {
        Path file = root.resolve("lines.txt");
        Files.writeString(file, "zero\none\ntwo\nthree\n");

        assertEquals(
                "one" + System.lineSeparator() + "two",
                new ReadFileTool().readFile(file, 1, 2));
    }

    @Test
    void shouldReturnEmptyPagePastEndOfFile(@TempDir Path root) throws Exception {
        Path file = root.resolve("short.txt");
        Files.writeString(file, "only");

        assertEquals("", new ReadFileTool().readFile(file, 5, 2));
    }

    @Test
    void shouldThrowWhenFileDoesNotExist(@TempDir Path root) {
        assertThrows(NoSuchFileException.class,
                () -> new ReadFileTool().readFile(root.resolve("missing.txt")));
    }

    @Test
    void shouldValidatePageArguments(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("file.txt"), "content");
        ReadFileTool tool = new ReadFileTool();

        assertThrows(IllegalArgumentException.class, () -> tool.readFile(file, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> tool.readFile(file, 0, 0));
    }
}
