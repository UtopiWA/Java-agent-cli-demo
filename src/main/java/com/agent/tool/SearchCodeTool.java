package com.agent.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    public static final int MAX_JAVA_FILES = 2_000;
    public static final long MAX_FILE_BYTES = 1024L * 1024;
    public static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    public static final int MAX_PAGE_CHARS = 8_000;
    private static final int MAX_RENDERED_LINE_CHARS = 1_000;

    public record Match(String file, int line, String content) {
    }

    public record SearchPage(
            String content,
            int returnedMatches,
            boolean hasMore,
            Integer nextOffset,
            boolean scanLimited,
            String scanLimitReason,
            int scannedFiles,
            long scannedBytes) {
    }

    /** 仅在 Java 文件中执行大小写敏感的字面量搜索。 */
    public SearchPage searchPage(
            Path projectRoot,
            Path searchRoot,
            String keyword,
            int offset,
            int limit,
            int contextLines) throws IOException {
        if (keyword == null || keyword.isEmpty()) {
            throw new IllegalArgumentException("keyword must not be empty");
        }
        if (offset < 0 || limit < 1 || contextLines < 0 || contextLines > 3) {
            throw new IllegalArgumentException("invalid search page arguments");
        }
        validateRoot(projectRoot);
        validateRoot(searchRoot);

        IgnoreRules ignoreRules = IgnoreRules.load(projectRoot);
        List<Path> candidates = new ArrayList<>();
        CandidateState candidateState = new CandidateState();
        collectJavaFiles(projectRoot, searchRoot, ignoreRules, candidates, candidateState);
        candidates.sort(Comparator.comparing(path -> normalize(projectRoot.relativize(path))));

        boolean scanLimited = candidateState.limited || candidates.size() > MAX_JAVA_FILES;
        String scanLimitReason = scanLimited ? "JAVA_FILE_LIMIT" : null;
        int candidateCount = Math.min(candidates.size(), MAX_JAVA_FILES);
        StringBuilder result = new StringBuilder();
        int matched = 0;
        int returned = 0;
        int scannedFiles = 0;
        long scannedBytes = 0;
        boolean hasMore = false;

        search:
        for (int fileIndex = 0; fileIndex < candidateCount; fileIndex++) {
            Path path = candidates.get(fileIndex);
            long size;
            try {
                size = Files.size(path);
            } catch (IOException e) {
                scanLimited = true;
                continue;
            }
            if (size > MAX_FILE_BYTES) {
                scanLimited = true;
                scanLimitReason = appendReason(scanLimitReason, "FILE_SIZE_LIMIT");
                continue;
            }
            if (scannedBytes + size > MAX_TOTAL_BYTES) {
                scanLimited = true;
                scanLimitReason = appendReason(scanLimitReason, "TOTAL_BYTES_LIMIT");
                break;
            }

            List<String> lines;
            try {
                lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                scanLimited = true;
                scanLimitReason = appendReason(scanLimitReason, "UNREADABLE_FILE");
                continue;
            }
            scannedFiles++;
            scannedBytes += size;

            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                if (!lines.get(lineIndex).contains(keyword)) {
                    continue;
                }
                if (matched++ < offset) {
                    continue;
                }
                if (returned >= limit) {
                    hasMore = true;
                    break search;
                }

                String entry = formatEntry(
                        normalize(projectRoot.relativize(path)), lines, lineIndex, contextLines);
                int separatorLength = result.isEmpty() ? 0 : System.lineSeparator().length();
                if (result.length() + separatorLength + entry.length() > MAX_PAGE_CHARS) {
                    hasMore = true;
                    break search;
                }
                if (!result.isEmpty()) {
                    result.append(System.lineSeparator());
                }
                result.append(entry);
                returned++;
            }
        }

        int nextOffset = offset + returned;
        if (!hasMore && scanLimited) {
            hasMore = true;
        }
        Integer cursor = hasMore && (!scanLimited || returned > 0)
                ? Integer.valueOf(nextOffset) : null;
        return new SearchPage(
                result.toString(), returned, hasMore,
                cursor,
                scanLimited, scanLimitReason, scannedFiles, scannedBytes);
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
                    .filter(path -> !ignoreRules.isExcluded(root, path, false))
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

    private static String formatEntry(
            String file, List<String> lines, int matchIndex, int contextLines) {
        StringBuilder result = new StringBuilder(file)
                .append(':').append(matchIndex + 1);
        int start = Math.max(0, matchIndex - contextLines);
        int end = Math.min(lines.size() - 1, matchIndex + contextLines);
        for (int index = start; index <= end; index++) {
            result.append(System.lineSeparator())
                    .append(index == matchIndex ? "> " : "  ")
                    .append(index + 1).append(" | ")
                    .append(abbreviate(lines.get(index)));
        }
        return result.toString();
    }

    private static String abbreviate(String line) {
        if (line.length() <= MAX_RENDERED_LINE_CHARS) {
            return line;
        }
        return line.substring(0, MAX_RENDERED_LINE_CHARS)
                + "...[本行已省略，原始字符数=" + line.length() + ']';
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String appendReason(String current, String reason) {
        if (current == null) {
            return reason;
        }
        return current.contains(reason) ? current : current + ',' + reason;
    }

    private static void collectJavaFiles(
            Path projectRoot,
            Path directory,
            IgnoreRules ignoreRules,
            List<Path> candidates,
            CandidateState state) throws IOException {
        if (state.limited) {
            return;
        }
        List<Path> children;
        try (Stream<Path> stream = Files.list(directory)) {
            children = stream.sorted(Comparator.comparing(
                    path -> normalize(projectRoot.relativize(path)))).toList();
        }
        for (Path child : children) {
            boolean directoryChild = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
            if (ignoreRules.isExcluded(projectRoot, child, directoryChild)) {
                continue;
            }
            if (directoryChild) {
                collectJavaFiles(projectRoot, child, ignoreRules, candidates, state);
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                    && child.getFileName().toString().endsWith(".java")) {
                candidates.add(child);
                if (candidates.size() > MAX_JAVA_FILES) {
                    state.limited = true;
                    return;
                }
            }
            if (state.limited) {
                return;
            }
        }
    }

    private static final class CandidateState {
        private boolean limited;
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
