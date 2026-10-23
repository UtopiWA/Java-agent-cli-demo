package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖带行号的有界读取、分页游标和异常路径。 */
class ReadFileToolTest {
    @Test
    void shouldThrowWhenFileDoesNotExist(@TempDir Path root) {
        assertThrows(NoSuchFileException.class,
                () -> new ReadFileTool().readPage(root.resolve("missing.txt"), 0, 10));
    }

    @Test
    void shouldValidatePageArguments(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("file.txt"), "content");
        ReadFileTool tool = new ReadFileTool();

        assertThrows(IllegalArgumentException.class, () -> tool.readPage(file, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> tool.readPage(file, 0, 0));
    }

    @Test
    void shouldReturnNumberedPageMetadataAndCursor(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("page.txt"), "zero\none\ntwo\n");

        ReadFileTool.ReadPage page = new ReadFileTool().readPage(file, 1, 1);

        assertEquals("2 | one", page.content());
        assertEquals(3, page.totalLines());
        assertEquals(2, page.startLine());
        assertEquals(2, page.endLine());
        assertTrue(page.hasMore());
        assertEquals(2, page.nextOffset());
        assertEquals(64, page.fingerprint().length());
    }

    @Test
    void shouldAdvancePastAnOversizedLine(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("long.txt"),
                "x".repeat(ReadFileTool.MAX_PAGE_CHARS + 1) + "\nafter");

        ReadFileTool.ReadPage page = new ReadFileTool().readPage(file, 0, 10);

        assertEquals("LINE_TOO_LONG", page.status());
        assertEquals(1, page.oversizedLine());
        assertEquals(1, page.nextOffset());
        assertTrue(page.hasMore());
    }
}
