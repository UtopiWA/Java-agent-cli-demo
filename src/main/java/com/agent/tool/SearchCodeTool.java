package com.agent.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 在项目文本文件中执行字面量或正则代码搜索。 */
public class SearchCodeTool {
    private static final int BINARY_SAMPLE_SIZE = 8192;

    public record Match(String file, int line, String content) {
    }

    /** 将 keyword 作为普通文本搜索 */
    public List<Match> searchCode(String keyword, Path root) throws IOException {
        if (keyword == null || keyword.isEmpty()) {
            throw new IllegalArgumentException("keyword must not be empty");
        }
        return searchCode(Pattern.compile(Pattern.quote(keyword)), root);
    }

    /**
     * 使用正则表达式递归搜索，并返回相对路径、行号和命中行内容。
     */
    public List<Match> searchCode(Pattern pattern, Path root) throws IOException {
        Objects.requireNonNull(pattern, "pattern must not be null");
        validateRoot(root);
        IgnoreRules ignoreRules = IgnoreRules.load(root);
        List<Match> matches = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(root)) {
            // 先过滤和排序候选文件，再逐个读取；单文件失败不会中断整体搜索。
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !ignoreRules.isIgnored(root, path, false))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList()) {
                if (!isProbablyText(path)) {
                    continue;
                }
                searchFile(pattern, root, path, matches);
            }
        }
        return List.copyOf(matches);
    }

    /** 按行匹配单个文件，并将 IOException 隔离在文件粒度。 */
    private static void searchFile(Pattern pattern, Path root, Path path, List<Match> matches) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String content;
            int line = 0;
            while ((content = reader.readLine()) != null) {
                line++;
                if (pattern.matcher(content).find()) {
                    matches.add(new Match(
                            root.relativize(path).toString(),
                            line,
                            content.trim()));
                }
            }
        } catch (IOException ignored) {
            // 单个文件不可读或编码错误时跳过
        }
    }

    /**
     * 启发式判断：抽样检查 NUL 和控制字符比例，避免将未知扩展名的二进制文件当作文本读取。
     */
    private static boolean isProbablyText(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] sample = input.readNBytes(BINARY_SAMPLE_SIZE);
            if (sample.length == 0) {
                return true;
            }

            int controlCharacters = 0;
            for (byte value : sample) {
                int unsigned = Byte.toUnsignedInt(value);
                // NUL 是常见二进制信号；少量换行、制表等文本控制字符允许存在。
                if (unsigned == 0) {
                    return false;
                }
                if (unsigned < 0x20 && unsigned != '\n' && unsigned != '\r' && unsigned != '\t' && unsigned != '\f') {
                    controlCharacters++;
                }
            }
            return controlCharacters * 10 <= sample.length * 3;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void validateRoot(Path root) throws IOException {
        if (!Files.exists(root)) {
            throw new NoSuchFileException(root.toString());
        }
        if (!Files.isDirectory(root)) {
            throw new NotDirectoryException(root.toString());
        }
    }
}
