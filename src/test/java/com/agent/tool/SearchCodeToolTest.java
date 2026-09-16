package com.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 覆盖字面量、正则、忽略配置、二进制文件和单文件失败隔离。 */
class SearchCodeToolTest {
    @Test
    void shouldFindLiteralKeywordRecursivelyWithLineNumber(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "first line\n// TODO implement\n");

        List<SearchCodeTool.Match> matches = new SearchCodeTool().searchCode("TODO", root);

        assertEquals(1, matches.size());
        assertEquals(Path.of("src", "A.java").toString(), matches.get(0).file());
        assertEquals(2, matches.get(0).line());
        assertEquals("// TODO implement", matches.get(0).content());
    }

    @Test
    void shouldSupportRegularExpressions(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Tasks.java"), "TODO-12\nTODO-x\nFIXME-42\n");

        List<SearchCodeTool.Match> matches = new SearchCodeTool()
                .searchCode(Pattern.compile("(?:TODO|FIXME)-\\d+"), root);

        assertEquals(2, matches.size());
        assertEquals(1, matches.get(0).line());
        assertEquals(3, matches.get(1).line());
    }

    @Test
    void shouldApplyIgnoreConfigAndSkipBinaryContent(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".agentignore"), "generated/\n*.png\n");
        Files.writeString(root.resolve("Keep.java"), "TODO keep");
        Files.createDirectories(root.resolve("generated"));
        Files.writeString(root.resolve("generated/Skip.java"), "TODO ignored");
        Files.write(root.resolve("image.png"), new byte[]{0, 'T', 'O', 'D', 'O'});

        List<SearchCodeTool.Match> matches = new SearchCodeTool().searchCode("TODO", root);

        assertEquals(1, matches.size());
        assertEquals("Keep.java", matches.get(0).file());
    }

    @Test
    void shouldContinueWhenOneTextFileIsMalformed(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Good.java"), "TODO good");
        Files.write(root.resolve("Malformed.txt"), new byte[]{(byte) 0xC3, 0x28});

        List<SearchCodeTool.Match> matches = new SearchCodeTool().searchCode("TODO", root);

        assertEquals(1, matches.size());
        assertEquals("Good.java", matches.get(0).file());
    }

    @Test
    void shouldTreatLiteralKeywordAsLiteralText(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Symbols.txt"), "a.b\naxb\n");

        List<SearchCodeTool.Match> matches = new SearchCodeTool().searchCode("a.b", root);

        assertEquals(1, matches.size());
        assertEquals("a.b", matches.get(0).content());
    }

    @Test
    void shouldRejectEmptyKeyword(@TempDir Path root) {
        assertThrows(IllegalArgumentException.class,
                () -> new SearchCodeTool().searchCode("", root));
    }

    @Test
    void shouldNotReturnIgnoredFiles(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".agentignore"), "*.log\n");
        Files.writeString(root.resolve("debug.log"), "TODO hidden");

        List<SearchCodeTool.Match> matches = new SearchCodeTool().searchCode("TODO", root);

        assertFalse(matches.stream().anyMatch(match -> match.file().endsWith(".log")));
    }
}
