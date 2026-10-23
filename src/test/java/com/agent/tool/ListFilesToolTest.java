package com.agent.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 覆盖当前目录树分页、忽略规则和深度限制。 */
class ListFilesToolTest {
    @Test
    void shouldPageTreeWithProjectLevelIgnoreRules(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".agentignore"), "ignored/\n");
        Files.createDirectories(root.resolve("src/ignored"));
        Files.writeString(root.resolve("src/A.java"), "class A {}");
        Files.writeString(root.resolve("src/B.java"), "class B {}");
        Files.writeString(root.resolve("src/ignored/Hidden.java"), "class Hidden {}");

        ListFilesTool tool = new ListFilesTool();
        ListFilesTool.TreePage first = tool.treePage(root, root.resolve("src"), 2, 0, 1);
        ListFilesTool.TreePage second = tool.treePage(
                root, root.resolve("src"), 2, first.nextOffset(), 1);

        assertEquals(1, first.returnedEntries());
        assertTrue(first.hasMore());
        assertEquals(1, first.nextOffset());
        assertTrue(first.tree().contains("A.java"));
        assertTrue(second.tree().contains("B.java"));
        assertFalse(first.tree().contains("Hidden.java"));
        assertFalse(second.hasMore());
    }
}
