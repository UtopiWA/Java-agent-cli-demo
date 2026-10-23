package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖面向 Java 源码的字面量搜索、上下文、忽略规则和分页。 */
class SearchCodeToolTest {
    @Test
    void shouldRejectEmptyKeyword(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCodeTool().searchPage(
                        root, root.resolve("src"), "", 0, 10, 0));
    }

    @Test
    void shouldSearchOnlyJavaFilesWithContextAndPagination(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".agentignore"), "generated/\n");
        Files.createDirectories(root.resolve("src/generated"));
        Files.writeString(root.resolve("src/A.java"), "before\nTODO first\nafter\n");
        Files.writeString(root.resolve("src/B.java"), "TODO second\n");
        Files.writeString(root.resolve("src/notes.txt"), "TODO text file\n");
        Files.writeString(root.resolve("src/generated/Hidden.java"), "TODO hidden\n");
        SearchCodeTool tool = new SearchCodeTool();

        SearchCodeTool.SearchPage first = tool.searchPage(
                root, root.resolve("src"), "TODO", 0, 1, 1);
        SearchCodeTool.SearchPage second = tool.searchPage(
                root, root.resolve("src"), "TODO", first.nextOffset(), 1, 0);

        assertEquals(1, first.returnedMatches());
        assertTrue(first.hasMore());
        assertTrue(first.content().contains("src/A.java:2"));
        assertTrue(first.content().contains("1 | before"));
        assertTrue(first.content().contains("3 | after"));
        assertTrue(second.content().contains("src/B.java:1"));
        assertFalse(second.content().contains("notes.txt"));
        assertFalse(second.content().contains("Hidden.java"));
        assertFalse(second.hasMore());
    }
}
