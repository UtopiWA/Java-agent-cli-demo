package com.agent.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 覆盖递归列举、忽略规则、最大深度和树形输出。 */
class ListFilesToolTest {
    @Test
    void shouldListFilesRecursivelyAndApplyIgnoreConfig(@TempDir Path root) throws Exception {
        // 在临时目录下创建大量临时文件结构进行测试
        Files.writeString(root.resolve(".agentignore"), "target/\n.git/\n*.class\n");
        Files.writeString(root.resolve("A.java"), "class A {}");
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/B.java"), "class B {}");
        Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("target/classes/A.class"), "ignored");
        Files.createDirectories(root.resolve(".git/objects"));
        Files.writeString(root.resolve(".git/objects/index"), "ignored");

        List<String> files = new ListFilesTool().listFiles(root);

        assertTrue(files.contains("A.java"));
        assertTrue(files.contains(Path.of("src", "main", "B.java").toString()));
        assertFalse(files.stream().anyMatch(path -> path.contains("target")));
        assertFalse(files.stream().anyMatch(path -> path.contains(".git")));
    }

    @Test
    void shouldLimitTraversalDepth(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("top.txt"), "top");
        Files.createDirectories(root.resolve("one/two"));
        Files.writeString(root.resolve("one/nested.txt"), "nested");
        Files.writeString(root.resolve("one/two/deep.txt"), "deep");

        List<String> files = new ListFilesTool().listFiles(root, 1);

        assertTrue(files.contains("top.txt"));
        assertFalse(files.stream().anyMatch(path -> path.contains("nested.txt")));
        assertFalse(files.stream().anyMatch(path -> path.contains("deep.txt")));
    }

    @Test
    void shouldRenderBranchConnectorsAndCompactDirectoryChains(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("README.md"), "demo");
        Files.createDirectories(root.resolve("src/main/java/com/agent"));
        Files.writeString(root.resolve("src/main/java/com/agent/Main.java"), "class Main {}");
        Files.createDirectories(root.resolve("src/test/java/com/agent"));
        Files.writeString(root.resolve("src/test/java/com/agent/AgentTest.java"), "class AgentTest {}");

        String tree = new ListFilesTool().tree(root);
        String lineSeparator = System.lineSeparator();
        String treeBody = tree.substring(tree.indexOf(lineSeparator) + lineSeparator.length());

        assertEquals(String.join(lineSeparator,
                "├── README.md",
                "└── src/",
                "    ├── main/java/com/agent/",
                "    │   └── Main.java",
                "    └── test/java/com/agent/",
                "        └── AgentTest.java"), treeBody);
    }

    @Test
    void shouldRejectNegativeDepth(@TempDir Path root) {
        assertThrows(IllegalArgumentException.class,
                () -> new ListFilesTool().listFiles(root, -1));
    }
}
