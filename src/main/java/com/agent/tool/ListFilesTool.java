package com.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 提供递归文件列举、深度限制和树形展示能力 */
public class ListFilesTool {
    /** 递归列出根目录下所有未被忽略的普通文件 */
    public List<String> listFiles(Path root) throws IOException {
        return listFiles(root, Integer.MAX_VALUE);
    }

    /**
     * 在指定最大深度内列出文件，返回相对于 root 的相对路径，返回结果按字典序排序。
     */
    public List<String> listFiles(Path root, int maxDepth) throws IOException {
        validateRoot(root);
        validateMaxDepth(maxDepth);
        IgnoreRules ignoreRules = IgnoreRules.load(root);

        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !ignoreRules.isIgnored(root, path, false))
                    .map(root::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
    }

    public String tree(Path root) throws IOException {
        return tree(root, Integer.MAX_VALUE);
    }

    /**
     * 在指定深度内生成树形文本；目录以斜杠结尾，缩进表示层级。
     */
    public String tree(Path root, int maxDepth) throws IOException {
        validateRoot(root);
        validateMaxDepth(maxDepth);
        IgnoreRules ignoreRules = IgnoreRules.load(root);
        StringBuilder result = new StringBuilder(rootName(root)).append('/');

        // 递归渲染时保留父级竖线，并依据同级位置选择分支或末端连接符。
        renderChildren(root, root, "", 0, maxDepth, ignoreRules, result);
        return result.toString();
    }

    /** 核心函数；深度优先遍历，渲染当前目录的直接子节点，并为后续层级传递正确的连接线前缀。 */
    private static void renderChildren(
            Path root,
            Path directory,
            String prefix, // 父层级延申下来的"|"
            int depth,
            int maxDepth,
            IgnoreRules ignoreRules,
            StringBuilder result) throws IOException {
        if (depth >= maxDepth) {
            return;
        }

        List<Path> children = visibleChildren(root, directory, ignoreRules);
        for (int index = 0; index < children.size(); index++) {
            Path child = children.get(index);
            boolean last = index == children.size() - 1;
            result.append(System.lineSeparator())
                    .append(prefix)
                    .append(last ? "└── " : "├── ");

            if (isDirectory(child)) {
                // 仅有一个子目录且没有文件时合并路径
                DirectoryChain chain = collapseDirectoryChain(
                        root, child, depth + 1, maxDepth, ignoreRules);
                result.append(chain.display()).append('/');
                renderChildren(
                        root,
                        chain.end(),
                        prefix + (last ? "    " : "│   "),
                        chain.depth(),
                        maxDepth,
                        ignoreRules,
                        result);
            } else {
                result.append(child.getFileName());
            }
        }
    }

    /** 收集未被忽略的直接子节点，并以“文件优先、目录随后”的名称顺序输出。 */
    private static List<Path> visibleChildren(
            Path root, Path directory, IgnoreRules ignoreRules) throws IOException {
        Comparator<Path> treeOrder = Comparator
                .comparing(ListFilesTool::isDirectory)
                .thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(path -> path.getFileName().toString());

        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(path -> !ignoreRules.isIgnored(root, path, isDirectory(path)))
                    .sorted(treeOrder)
                    .toList();
        }
    }

    /** 压缩单目录链，同时严格遵守实际目录深度限制。 */
    private static DirectoryChain collapseDirectoryChain(
            Path root,
            Path start,
            int startDepth,
            int maxDepth,
            IgnoreRules ignoreRules) throws IOException {
        Path end = start;
        int depth = startDepth;
        StringBuilder display = new StringBuilder(start.getFileName().toString());

        while (depth < maxDepth) {
            List<Path> children = visibleChildren(root, end, ignoreRules);
            if (children.size() != 1 || !isDirectory(children.get(0))) {
                break;
            }
            end = children.get(0);
            depth++;
            display.append('/').append(end.getFileName());
        }
        return new DirectoryChain(end, display.toString(), depth);
    }

    /** 不跟随符号链接，避免树形递归进入目录环。 */
    private static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** Java record 封装目录链结构 */
    private record DirectoryChain(Path end, String display, int depth) {
    }

    /** 在遍历前给出明确的“不存在”或“不是目录”错误。 */
    private static void validateRoot(Path root) throws IOException {
        if (!Files.exists(root)) {
            throw new NoSuchFileException(root.toString());
        }
        if (!Files.isDirectory(root)) {
            throw new NotDirectoryException(root.toString());
        }
    }

    private static void validateMaxDepth(int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative");
        }
    }

    private static String rootName(Path root) {
        Path fileName = root.toAbsolutePath().normalize().getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }

}
