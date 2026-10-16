package com.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 UTF-8 原子写入、覆盖和输入边界。 */
class WriteFileToolTest {
    @Test
    void shouldCreateParentDirectoriesAndWriteUtf8(@TempDir Path root) throws Exception {
        Path target = root.resolve("src/说明.txt");

        WriteFileTool.WriteResult result = new WriteFileTool()
                .writeFile(target, "你好，Agent！");

        assertTrue(result.created());
        assertEquals("你好，Agent！", Files.readString(target));
        assertEquals("你好，Agent！".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                result.bytesWritten());
    }

    @Test
    void shouldReplaceExistingFile(@TempDir Path root) throws Exception {
        Path target = Files.writeString(root.resolve("Demo.java"), "old");

        WriteFileTool.WriteResult result = new WriteFileTool()
                .writeFile(target, "class Demo {}");

        assertFalse(result.created());
        assertEquals("class Demo {}", Files.readString(target));
    }

    @Test
    void shouldRejectOversizedContentAndDirectoryTarget(@TempDir Path root) {
        WriteFileTool tool = new WriteFileTool();

        assertThrows(IllegalArgumentException.class, () -> tool.writeFile(
                root.resolve("large.txt"), "x".repeat(WriteFileTool.MAX_CONTENT_BYTES + 1)));
        assertThrows(IOException.class, () -> tool.writeFile(root, "content"));
    }
}
